package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Partial quota-commit regression: the durable domain commit already
 * succeeded, so a failure while moving the second quota reservation from
 * reserved to committed must not leave a permanent reservation leak or an
 * under-counted committed total. The outcome degrades (observable, retryable
 * via startup recovery) with the logical reservations released and both
 * quota counters matching the durable truth.
 */
class ClaimSagaPartialQuotaCompleteTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    /** Economy seam that corrupts the chunk reservation between reserve and publish. */
    static final class CorruptingEconomy implements ClaimEconomy {
        final OwnerQuotaService quota;
        final OwnerRef owner;
        final List<LedgerEntry> refundCalls = Collections.synchronizedList(new ArrayList<>());

        CorruptingEconomy(OwnerQuotaService quota, OwnerRef owner) {
            this.quota = quota;
            this.owner = owner;
        }

        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
            // Reserve happened synchronously on the caller thread before this
            // async stage; publish runs later on the same chain, so rewriting
            // the counter here deterministically corrupts the publish guards
            // without any sleep or barrier.
            corruptChunkReserved(2, 1);
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

        private void corruptChunkReserved(int expect, int rewrite) {
            try {
                Field statesField = OwnerQuotaService.class.getDeclaredField("states");
                statesField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ConcurrentHashMap<String, Object> states =
                        (ConcurrentHashMap<String, Object>) statesField.get(quota);
                Object state = states.get(owner.key());
                Field counter = state.getClass().getDeclaredField("chunkReserved");
                counter.setAccessible(true);
                assertEquals(expect, counter.getInt(state), "test seam expects a 2-chunk reservation");
                counter.setInt(state, rewrite);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    @Test
    void landCompleteSucceedsChunkCompleteFailsLeavesNoLeak() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(actor);
        Set<ChunkKey> chunks = Set.of(new ChunkKey(world, 9, 9), new ChunkKey(world, 9, 10));
        ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claim-partial-quota-test");
            t.setDaemon(true);
            return t;
        });
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            OwnerQuotaService quota = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(100, 100, 128, 16), 0L, Map.of())));
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            LandRegistryStore registryStore = new LandRegistryStore();
            CorruptingEconomy economy = new CorruptingEconomy(quota, owner);
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(new SqliteLandRepository(store), registryStore);
            ClaimValidator validator = request -> new SnapshotClaimValidator(registryStore,
                    ClaimValidator.RevisionSource.none(), chunk -> 64,
                    quota::chunkCommitted).validate(request);
            PricingTable pricing = PricingTable.of(List.of(
                    PricingTier.of(5, new Money(100, EMC)),
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
            ClaimSaga saga = new ClaimSaga(validator, quota, pricing, reservations, ledger,
                    economy, rebuilder, Clock.fixed(NOW, ZoneOffset.UTC), async, 3);

            ClaimOutcome outcome = saga.claim(new ClaimRequest(owner, actor, world, chunks, "Home"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Observable degraded outcome, never compensation: the durable commit won.
            assertEquals(ClaimOutcome.Status.DEGRADED, outcome.status());
            assertEquals("claim.publish_failed", outcome.diagnosticKey());
            assertTrue(economy.refundCalls.isEmpty(), "post-commit quota failure must never refund");

            // Committed counters match the durable truth (1 land, 2 chunks); nothing stays reserved.
            assertEquals(1, quota.landCommitted(owner), "land commit must survive the partial failure");
            assertEquals(2, quota.chunkCommitted(owner), "chunk commit must be repaired to the durable truth");
            assertEquals(0, quota.landReserved(owner), "no land reservation may leak");
            assertEquals(0, quota.chunkReserved(owner), "no chunk reservation may leak");

            // Logical reservations are released so the same chunks are retry-safe.
            Set<String> keys = Set.of(world + ":9:9", world + ":9:10");
            assertTrue(reservations.tryAcquire(keys, UUID.randomUUID()),
                    "logical reservations must be released on the partial-commit path");
            reservations.release(keys, UUID.randomUUID());

            // Quota handles stay sane: a fresh reservation round-trips without wedging.
            assertTrue(quota.tryReserveLand(owner).isPresent(), "quota must stay usable after repair");
        } finally {
            async.shutdownNow();
        }
    }
}
