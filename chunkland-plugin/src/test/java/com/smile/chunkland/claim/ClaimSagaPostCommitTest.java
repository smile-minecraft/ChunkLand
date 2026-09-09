package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Post-commit callback registration hardening: once the durable domain commit
 * succeeded, no publish-side registration failure, callback body failure, or
 * null continuation may compensate, refund, or hang. Every case completes as
 * degraded with the ledger pinned at {@code DOMAIN_COMMITTED}.
 */
class ClaimSagaPostCommitTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeEconomy implements ClaimEconomy {
        final List<LedgerEntry> refundCalls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            return CompletableFuture.completedFuture(ChargeResult.ok());
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            refundCalls.add(entry);
            return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
        }

        @Override
        public String providerId() {
            return "test-economy";
        }
    }

    static final class FailingLands implements LandRepository {
        @Override
        public CompletionStage<Void> save(LandSnapshot land) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findAll() {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }

        @Override
        public CompletionStage<Void> delete(LandId id) {
            return CompletableFuture.failedFuture(new IllegalStateException("land store down"));
        }
    }

    /** Completed stage that throws from the continuation registration under test. */
    static final class RegistrationThrowingStage<T> extends CompletableFuture<T> {
        private final RuntimeException registrationFailure;

        RegistrationThrowingStage(T value, RuntimeException registrationFailure) {
            super.completedFuture(value);
            this.registrationFailure = registrationFailure;
        }

        @Override
        public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
            throw registrationFailure;
        }

        @Override
        public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
            throw registrationFailure;
        }
    }

    /** Stage that runs the callback and then throws out of the registration call. */
    static final class BodyThrowingStage extends CompletableFuture<ClaimOutcome> {
        private final RuntimeException bodyFailure;

        BodyThrowingStage(RuntimeException bodyFailure) {
            super.completedFuture(null);
            this.bodyFailure = bodyFailure;
        }

        @Override
        public CompletableFuture<ClaimOutcome> whenComplete(
                BiConsumer<? super ClaimOutcome, ? super Throwable> action) {
            action.accept(null, new IllegalStateException("injected publish body failure"));
            throw bodyFailure;
        }
    }

    /** Stage whose continuation registration returns null instead of a stage. */
    static final class NullContinutionStage extends CompletableFuture<Throwable> {
        NullContinutionStage() {
            super.completedFuture(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> CompletableFuture<U> thenCompose(Function<? super Throwable, ? extends CompletionStage<U>> fn) {
            return null;
        }
    }

    final class Fixture implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final OwnerQuotaService quota;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claim-post-commit-test");
            t.setDaemon(true);
            return t;
        });
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final ClaimSaga saga;
        final AtomicReference<ClaimSaga.ClaimAttempt> captured = new AtomicReference<>();

        Fixture() {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            LimitSettings limits = new LimitSettings(100, 100, 128, 16);
            quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), limits, 0L, Map.of())));
            PricingTable pricing = PricingTable.of(List.of(
                    PricingTier.of(5, new Money(100, EMC)),
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
            RuntimeRegistryRebuilder failingRebuilder =
                    new RuntimeRegistryRebuilder(new FailingLands(), registryStore);
            ClaimValidator validator = request -> new SnapshotClaimValidator(registryStore,
                    ClaimValidator.RevisionSource.none(), chunk -> 64,
                    owner -> quota.chunkCommitted(owner)).validate(request);
            saga = new ClaimSaga(validator, quota, pricing, reservations, ledger, economy,
                    failingRebuilder, clock, async, 3, attempt -> {
                        captured.set(attempt);
                        if (attempt.landReservation() != null) {
                            attempt.landReservation().complete();
                        }
                        if (attempt.chunkReservation() != null) {
                            attempt.chunkReservation().complete();
                        }
                        reservations.release(attempt.reservationKeys(), attempt.operationId());
                    });
        }

        ClaimSaga.ClaimAttempt committedAttempt() throws Exception {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            ClaimRequest request = new ClaimRequest(OwnerRef.player(actor), actor, world,
                    Set.of(new ChunkKey(world, 9, 9)), "Home");
            ClaimOutcome outcome = saga.claim(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            ClaimSaga.ClaimAttempt attempt = captured.get();
            assertNotNull(attempt, "capture claim must reach the publish guards");
            LedgerEntry row = ledger.find(attempt.operationId())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("DOMAIN_COMMITTED", row.state());
            assertTrue(economy.refundCalls.isEmpty());
            return attempt;
        }

        void assertPinnedDegraded(ClaimSaga.ClaimAttempt attempt) throws Exception {
            LedgerEntry row = ledger.find(attempt.operationId())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("DOMAIN_COMMITTED", row.state(),
                    "post-commit failure must keep the durable commit");
            assertEquals(0, row.compensationAttempts());
            assertTrue(economy.refundCalls.isEmpty(), "post-commit failure must never refund");
        }

        @Override
        public void close() {
            async.shutdownNow();
            try {
                async.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            store.close();
        }
    }

    @Test
    void commitSuccessThenComposeRegistrationThrowDegrades() throws Exception {
        try (Fixture f = new Fixture()) {
            ClaimSaga.ClaimAttempt attempt = f.committedAttempt();
            CompletionStage<Throwable> settled =
                    new RegistrationThrowingStage<>(null, new IllegalStateException("injected thenCompose throw"));

            ClaimOutcome outcome = f.saga.settleCommitted(attempt, settled)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            assertEquals(attempt.plan().landId(), outcome.landId());
            f.assertPinnedDegraded(attempt);
        }
    }

    @Test
    void commitSuccessThenComposeNullDegrades() throws Exception {
        try (Fixture f = new Fixture()) {
            ClaimSaga.ClaimAttempt attempt = f.committedAttempt();

            ClaimOutcome outcome = f.saga.settleCommitted(attempt, new NullContinutionStage())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            f.assertPinnedDegraded(attempt);
        }
    }

    @Test
    void publishStageWhenCompleteRegistrationThrowDegrades() throws Exception {
        try (Fixture f = new Fixture()) {
            ClaimSaga.ClaimAttempt attempt = f.committedAttempt();
            CompletableFuture<ClaimOutcome> published = new CompletableFuture<>();
            CompletionStage<ClaimOutcome> stage = new RegistrationThrowingStage<>(null,
                    new IllegalStateException("injected whenComplete throw"));

            f.saga.settlePublishStage(attempt, published, stage);

            ClaimOutcome outcome = published.get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            assertEquals(attempt.plan().landId(), outcome.landId());
            f.assertPinnedDegraded(attempt);
        }
    }

    @Test
    void publishStageCallbackBodyThrowCompletesDegradedExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            ClaimSaga.ClaimAttempt attempt = f.committedAttempt();
            CompletableFuture<ClaimOutcome> published = new CompletableFuture<>();

            f.saga.settlePublishStage(attempt, published,
                    new BodyThrowingStage(new IllegalStateException("injected body throw")));

            ClaimOutcome outcome = published.get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            f.assertPinnedDegraded(attempt);
        }
    }

    @Test
    void publishStageNullOutcomeDegrades() throws Exception {
        try (Fixture f = new Fixture()) {
            ClaimSaga.ClaimAttempt attempt = f.committedAttempt();
            CompletableFuture<ClaimOutcome> published = new CompletableFuture<>();

            f.saga.settlePublishStage(attempt, published, CompletableFuture.completedFuture(null));

            ClaimOutcome outcome = published.get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            f.assertPinnedDegraded(attempt);
        }
    }

    @Test
    void realSqliteRepositoryStillReachesActive() throws Exception {
        try (Fixture f = new Fixture()) {
            ClaimSaga.ClaimAttempt attempt = f.committedAttempt();
            RuntimeRegistryRebuilder live =
                    new RuntimeRegistryRebuilder(new SqliteLandRepository(f.store), f.registryStore);
            CompletableFuture<ClaimOutcome> published = new CompletableFuture<>();

            f.saga.settlePublishStage(attempt, published,
                    live.rebuild().thenApply(ignored -> ClaimOutcome.success(attempt.plan().landId())));

            ClaimOutcome outcome = published.get(10, TimeUnit.SECONDS);
            assertEquals(ClaimOutcome.Status.SUCCESS, outcome.status());
            assertEquals(attempt.plan().landId(), outcome.landId());
        }
    }
}
