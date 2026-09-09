package com.smile.chunkland.refund;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundCommit;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Malformed refund payload quarantine and ratio identity consistency.
 *
 * <p>Malformed stored payloads must never count as an identity match and must
 * never touch Economy, the domain commit, or the runtime rebuild. Terminal
 * rows stay terminal with a fail-closed result; non-terminal rows quarantine
 * to NEEDS_RECONCILIATION.
 */
class RefundMalformedPayloadTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeDeposits implements RefundEconomy {
        final List<LedgerEntry> deposits = Collections.synchronizedList(new ArrayList<>());
        volatile RefundOutcome next = RefundOutcome.REFUNDED;

        @Override
        public CompletionStage<RefundOutcome> deposit(LedgerEntry entry) {
            deposits.add(entry);
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public String providerId() {
            return "test-economy";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final SqliteLandRepository lands;
        final SqliteChunkRepository chunks;
        final SqliteAuditRepository audits;
        final LogicalReservationRegistry reservations = new LogicalReservationRegistry();
        final LandRegistryStore registryStore = new LandRegistryStore();
        final RuntimeRegistryRebuilder rebuilder;
        final FakeDeposits economy = new FakeDeposits();
        final RefundSaga saga;
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "refund-malformed-test");
            t.setDaemon(true);
            return t;
        });
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        Harness() {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            lands = new SqliteLandRepository(store);
            chunks = new SqliteChunkRepository(store);
            audits = new SqliteAuditRepository(store);
            rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            RefundValidator validator = new DurableRefundValidator(lands, chunks, EMC);
            saga = new RefundSaga(validator, reservations, ledger, economy, rebuilder,
                    clock, async, 3);
        }

        RefundResult run(RefundRequest request) throws Exception {
            return saga.refund(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        RefundResult retry(UUID operationId) throws Exception {
            return saga.retry(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    private record ClaimedLand(LandId landId, UUID actor, UUID world, List<ChunkKey> chunkKeys) {
    }

    private ClaimedLand claimLand(Harness h, UUID actor, UUID world, String name, long... bases)
            throws Exception {
        LandId landId = new LandId(UUID.randomUUID());
        Set<ChunkKey> keys = new java.util.LinkedHashSet<>();
        for (int i = 0; i < bases.length; i++) {
            keys.add(new ChunkKey(world, i, 0));
        }
        LandSnapshot snap = new LandSnapshot(landId, name, LandName.normalize(name),
                OwnerRef.player(actor), world, keys, List.of(), 0, 0, NOW, NOW);
        h.lands.save(snap).toCompletableFuture().get(10, TimeUnit.SECONDS);
        int index = 0;
        for (ChunkKey key : keys) {
            h.chunks.addChunk(landId, key, 64, UUID.randomUUID(), bases[index++])
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        return new ClaimedLand(landId, actor, world, List.copyOf(keys));
    }

    private static LedgerEntry malformedRow(UUID operationId, LedgerState state) {
        return new LedgerEntry(operationId, RefundSaga.REFUND_OPERATION_TYPE, state.name(),
                UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()),
                50L, "test-economy", state == LedgerState.COMPENSATION_PENDING ? "refund:" + operationId : null,
                "{broken-payload", OperationPayload.CURRENT_SCHEMA_VERSION, NOW, NOW, 0);
    }

    private static int auditRefundCount(Harness h) {
        return h.audits.findByAction(RefundCommit.REFUND_AUDIT_ACTION, 100)
                .toCompletableFuture().join().size();
    }

    @Test
    void compensatedMalformedPayloadNeverSucceedsOrTouchesEconomy() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            h.ledger.insert(malformedRow(operationId, LedgerState.COMPENSATED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            RefundResult result = h.retry(operationId);

            assertNotEquals(RefundResult.Status.SUCCESS, result.status(),
                    "a malformed COMPENSATED row must never report success");
            assertEquals("COMPENSATED", h.ledger.find(operationId).toCompletableFuture().join().state(),
                    "terminal rows stay terminal");
            assertTrue(h.economy.deposits.isEmpty(), "malformed payload must never deposit");
            assertEquals(0, auditRefundCount(h), "malformed payload must not write refund audit");
        }
    }

    @Test
    void domainCommittedMalformedPayloadQuarantinesWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            h.ledger.insert(malformedRow(operationId, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            RefundResult result = h.retry(operationId);

            assertEquals(RefundResult.Status.NEEDS_RECONCILIATION, result.status());
            assertEquals("NEEDS_RECONCILIATION",
                    h.ledger.find(operationId).toCompletableFuture().join().state());
            assertTrue(h.economy.deposits.isEmpty());
            assertEquals(0, auditRefundCount(h));
        }
    }

    @Test
    void compensationPendingMalformedPayloadQuarantinesWithoutRetryDeposit() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            h.ledger.insert(malformedRow(operationId, LedgerState.COMPENSATION_PENDING))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            RefundResult result = h.retry(operationId);

            assertEquals(RefundResult.Status.NEEDS_RECONCILIATION, result.status());
            assertEquals("NEEDS_RECONCILIATION",
                    h.ledger.find(operationId).toCompletableFuture().join().state());
            assertTrue(h.economy.deposits.isEmpty(), "quarantined rows must not retry the deposit");
        }
    }

    @Test
    void exactValidRetryStillIdempotent() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            ClaimedLand land = claimLand(h, actor, world, "Home", 100L);
            UUID operationId = UUID.randomUUID();
            RefundRequest request = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 2);

            RefundResult first = h.run(request);
            RefundResult second = h.run(request);

            assertEquals(RefundResult.Status.SUCCESS, first.status());
            assertEquals(RefundResult.Status.SUCCESS, second.status());
            assertEquals(50L, second.refundMinorUnits());
            assertEquals(1, h.economy.deposits.size());
        }
    }

    @Test
    void equivalentFractionsShareIdentityWhileDifferentRatiosConflict() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            ClaimedLand land = claimLand(h, actor, world, "Home", 100L);
            UUID operationId = UUID.randomUUID();
            RefundRequest half = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 2);

            RefundResult first = h.run(half);
            assertEquals(RefundResult.Status.SUCCESS, first.status());

            // 2/4 is mathematically equal to 1/2 and must behave the same way.
            RefundRequest equivalent = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 2, 4);
            RefundResult resumed = h.run(equivalent);
            assertEquals(RefundResult.Status.SUCCESS, resumed.status(),
                    "equivalent fractions must share the same refund identity");
            assertEquals(1, h.economy.deposits.size());
        }
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            ClaimedLand land = claimLand(h, actor, world, "Home", 100L);
            UUID operationId = UUID.randomUUID();
            RefundRequest half = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 2);
            RefundResult first = h.run(half);
            assertEquals(RefundResult.Status.SUCCESS, first.status());

            RefundRequest full = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 1);
            RefundResult conflict = h.run(full);
            assertEquals(RefundResult.Status.REJECTED, conflict.status());
            assertEquals(RefundSaga.IDEMPOTENCY_CONFLICT_KEY, conflict.diagnosticKey());
            assertEquals(1, h.economy.deposits.size(), "a conflicting ratio must not deposit again");
        }
    }

    @Test
    void malformedPathsWriteNoEconomyRefundAudit() throws Exception {
        try (Harness h = new Harness()) {
            UUID pending = UUID.randomUUID();
            UUID committed = UUID.randomUUID();
            h.ledger.insert(malformedRow(pending, LedgerState.COMPENSATION_PENDING))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            h.ledger.insert(malformedRow(committed, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            h.retry(pending);
            h.retry(committed);

            assertEquals(0, auditRefundCount(h));
            assertTrue(h.economy.deposits.isEmpty());
        }
    }

    @Test
    void sameAmountDifferentRatiosStillConflict() throws Exception {
        // Total basis 3: 1/2 rounds half-up to 2 and 2/3 is exactly 2, so an
        // amount-only gate would mistake them for the same identity. The
        // canonical ratio check must keep them apart.
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            ClaimedLand land = claimLand(h, actor, world, "Home", 3L);
            UUID operationId = UUID.randomUUID();
            RefundRequest half = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 2);
            RefundResult first = h.run(half);
            assertEquals(RefundResult.Status.SUCCESS, first.status());
            assertEquals(2L, first.refundMinorUnits());

            RefundRequest other = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 2, 3);
            RefundResult conflict = h.run(other);
            assertEquals(RefundResult.Status.REJECTED, conflict.status());
            assertEquals(RefundSaga.IDEMPOTENCY_CONFLICT_KEY, conflict.diagnosticKey());
            assertEquals(1, h.economy.deposits.size());
        }
    }

    @Test
    void refundWithSameIdOnMalformedRowQuarantinesInsteadOfDepositing() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            h.ledger.insert(malformedRow(operationId, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            ClaimedLand land = claimLand(h, actor, world, "Home", 100L);
            RefundRequest request = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 2);
            RefundResult result = h.run(request);

            assertEquals(RefundResult.Status.NEEDS_RECONCILIATION, result.status(),
                    "a malformed existing row must quarantine even when the new request looks valid");
            assertEquals("NEEDS_RECONCILIATION",
                    h.ledger.find(operationId).toCompletableFuture().join().state());
            assertTrue(h.economy.deposits.isEmpty());
            assertEquals(0, auditRefundCount(h));
        }
    }

    @Test
    void refundRatioSurvivesPayloadRoundTripAndLegacyRowsStayReadable() {
        UUID operationId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        OperationPayload.Chunk chunk =
                new OperationPayload.Chunk(new ChunkKey(world, 0, 0), 64, UUID.randomUUID(), 100L);
        OperationPayload withRatio = new OperationPayload(operationId, RefundSaga.REFUND_OPERATION_TYPE,
                actor, world, new LandId(UUID.randomUUID()), List.of(chunk), 50L, "test-economy",
                null, NOW, NOW, OperationPayload.CURRENT_SCHEMA_VERSION, "Home", 1L, 2L);
        OperationPayload decoded = OperationPayload.fromJson(withRatio.toJson());
        assertEquals(1L, decoded.refundNumerator());
        assertEquals(2L, decoded.refundDenominator());

        // Legacy JSON without the new fields keeps parsing with a null ratio.
        String legacy = withRatio.toJson().replace(",\"refundNumerator\":1,\"refundDenominator\":2", "");
        OperationPayload legacyDecoded = OperationPayload.fromJson(legacy);
        assertNull(legacyDecoded.refundNumerator());
        assertNull(legacyDecoded.refundDenominator());
    }
}
