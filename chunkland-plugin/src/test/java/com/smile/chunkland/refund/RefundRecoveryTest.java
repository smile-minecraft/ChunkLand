package com.smile.chunkland.refund;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.persistence.CrashRecoveryScanner;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.RecoveryResult;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteLandRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup recovery over refund rows: every refund-path ledger state has a
 * deterministic outcome through the shared scanner, and the claim path is
 * unchanged by the refund routing.
 */
class RefundRecoveryTest {

    @TempDir Path temp;

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    final class Harness implements AutoCloseable {
        final PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
        final OperationLedger ledger = new OperationLedger(store);
        final AtomicReference<RefundOutcome> refundOutcome =
                new AtomicReference<>(RefundOutcome.REFUNDED);
        final AtomicInteger refundCalls = new AtomicInteger();
        final AtomicInteger rebuildCalls = new AtomicInteger();

        RecoveryHandlers handlers() {
            return RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(
                            com.smile.chunkland.persistence.PaymentLookup.unknown()),
                    entry -> {
                        refundCalls.incrementAndGet();
                        RefundOutcome outcome = refundOutcome.get();
                        if (outcome == null) {
                            return CompletableFuture.failedFuture(new IllegalStateException("no outcome"));
                        }
                        return CompletableFuture.completedFuture(outcome);
                    },
                    (entry, payload) -> CompletableFuture.failedFuture(
                            new IllegalStateException("claim replay is not configured")),
                    (entry, payload) -> {
                        rebuildCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });
        }

        List<RecoveryResult> scan(int retryLimit) throws Exception {
            return new CrashRecoveryScanner(ledger, handlers(), CLOCK, retryLimit)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        String state(UUID operationId) {
            return ledger.find(operationId).toCompletableFuture().join().state();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static OperationPayload.Chunk payloadChunk(UUID world, int x, long basis) {
        return new OperationPayload.Chunk(new ChunkKey(world, x, 0), 64, UUID.randomUUID(), basis);
    }

    private static OperationPayload refundPayload(UUID operationId, UUID actor, UUID world,
            LandId landId, long priceMinorUnits, String provider) {
        return new OperationPayload(operationId, RefundSaga.REFUND_OPERATION_TYPE, actor, world,
                landId, List.of(payloadChunk(world, 0, 100L)), priceMinorUnits, provider, null,
                NOW, NOW, OperationPayload.CURRENT_SCHEMA_VERSION, "Home");
    }

    private void insert(Harness h, LedgerEntry entry) throws Exception {
        h.ledger.insert(entry).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void createdRefundRowFailsUncharged() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            insert(h, LedgerEntry.fromPayload(
                    refundPayload(operationId, UUID.randomUUID(), UUID.randomUUID(),
                            new LandId(UUID.randomUUID()), 50L, "test-economy"),
                    LedgerState.CREATED));

            List<RecoveryResult> results = h.scan(3);

            assertEquals(1, results.size());
            assertEquals("FAILED", results.get(0).resultingState());
            assertEquals(LedgerState.RecoveryClassification.FAIL_UNCHARGED, results.get(0).classification());
            assertEquals("FAILED", h.state(operationId));
            assertEquals(0, h.refundCalls.get(), "an unstarted refund never calls Economy");
        }
    }

    @Test
    void domainCommittedRefundRetriesDepositToCompensated() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            insert(h, LedgerEntry.fromPayload(
                    refundPayload(operationId, UUID.randomUUID(), UUID.randomUUID(),
                            new LandId(UUID.randomUUID()), 50L, "test-economy"),
                    LedgerState.DOMAIN_COMMITTED));

            List<RecoveryResult> results = h.scan(3);

            assertEquals(1, results.size());
            assertEquals("COMPENSATED", results.get(0).resultingState());
            assertEquals(LedgerState.RecoveryClassification.RETRY_COMPENSATION,
                    results.get(0).classification());
            assertEquals("COMPENSATED", h.state(operationId));
            assertEquals(1, h.refundCalls.get());
            assertEquals(1, h.rebuildCalls.get(),
                    "a compensated refund must republish the runtime so released chunks leave the snapshot");
        }
    }

    @Test
    void domainCommittedRefundFailureStaysPendingWithAttempts() throws Exception {
        try (Harness h = new Harness()) {
            h.refundOutcome.set(RefundOutcome.FAILED);
            UUID operationId = UUID.randomUUID();
            insert(h, LedgerEntry.fromPayload(
                    refundPayload(operationId, UUID.randomUUID(), UUID.randomUUID(),
                            new LandId(UUID.randomUUID()), 50L, "test-economy"),
                    LedgerState.DOMAIN_COMMITTED));

            List<RecoveryResult> results = h.scan(3);

            assertEquals("COMPENSATION_PENDING", results.get(0).resultingState());
            assertEquals("COMPENSATION_PENDING", h.state(operationId));
            LedgerEntry row = h.ledger.find(operationId).toCompletableFuture().join();
            assertEquals(1, row.compensationAttempts());
            assertEquals("refund:" + operationId, row.economyTransactionRef());
        }
    }

    @Test
    void domainCommittedRefundWithInvalidPayloadGoesToReconciliation() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            LedgerEntry broken = new LedgerEntry(operationId, RefundSaga.REFUND_OPERATION_TYPE,
                    LedgerState.DOMAIN_COMMITTED.name(), UUID.randomUUID(), UUID.randomUUID(),
                    new LandId(UUID.randomUUID()), 50L, "test-economy", null, "{broken",
                    OperationPayload.CURRENT_SCHEMA_VERSION, NOW, NOW, 0);
            insert(h, broken);

            List<RecoveryResult> results = h.scan(3);

            assertEquals("NEEDS_RECONCILIATION", results.get(0).resultingState());
            assertEquals(0, h.refundCalls.get());
        }
    }

    @Test
    void compensationPendingRefundWithMalformedPayloadNeverCallsEconomy() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            LedgerEntry broken = new LedgerEntry(operationId, RefundSaga.REFUND_OPERATION_TYPE,
                    LedgerState.COMPENSATION_PENDING.name(), UUID.randomUUID(), UUID.randomUUID(),
                    new LandId(UUID.randomUUID()), 50L, "test-economy", "refund:" + operationId, "{broken",
                    OperationPayload.CURRENT_SCHEMA_VERSION, NOW, NOW, 0);
            insert(h, broken);

            List<RecoveryResult> results = h.scan(3);

            assertEquals("NEEDS_RECONCILIATION", results.get(0).resultingState(),
                    "a malformed REFUND COMPENSATION_PENDING row must quarantine without calling Economy");
            assertEquals("NEEDS_RECONCILIATION", h.state(operationId));
            assertEquals(0, h.refundCalls.get(), "malformed payload must never call Economy");
            assertEquals(0, h.rebuildCalls.get(), "malformed payload must never rebuild runtime");
        }
    }

    @Test
    void compensationPendingRefundSuccessSettles() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            OperationPayload payload = refundPayload(operationId, UUID.randomUUID(),
                    UUID.randomUUID(), new LandId(UUID.randomUUID()), 50L, "test-economy");
            insert(h, LedgerEntry.fromPayload(payload, LedgerState.COMPENSATION_PENDING,
                    "refund:" + operationId, NOW));

            List<RecoveryResult> results = h.scan(3);

            assertEquals("COMPENSATED", results.get(0).resultingState());
            assertEquals("COMPENSATED", h.state(operationId));
            assertEquals(1, h.refundCalls.get(), "a valid pending refund must retry the deposit");
            assertEquals(1, h.rebuildCalls.get(), "a settled refund must republish the runtime");
        }
    }

    @Test
    void compensationPendingUnknownOutcomeKeepsPending() throws Exception {
        try (Harness h = new Harness()) {
            h.refundOutcome.set(RefundOutcome.UNKNOWN);
            UUID operationId = UUID.randomUUID();
            OperationPayload payload = refundPayload(operationId, UUID.randomUUID(),
                    UUID.randomUUID(), new LandId(UUID.randomUUID()), 50L, "test-economy");
            insert(h, LedgerEntry.fromPayload(payload, LedgerState.COMPENSATION_PENDING,
                    "refund:" + operationId, NOW));

            List<RecoveryResult> results = h.scan(3);

            assertEquals("COMPENSATION_PENDING", results.get(0).resultingState());
            LedgerEntry row = h.ledger.find(operationId).toCompletableFuture().join();
            assertEquals(1, row.compensationAttempts());
        }
    }

    @Test
    void compensationPendingAtLimitReachesReconciliation() throws Exception {
        try (Harness h = new Harness()) {
            h.refundOutcome.set(RefundOutcome.FAILED);
            UUID operationId = UUID.randomUUID();
            OperationPayload payload = refundPayload(operationId, UUID.randomUUID(),
                    UUID.randomUUID(), new LandId(UUID.randomUUID()), 50L, "test-economy");
            insert(h, LedgerEntry.fromPayload(payload, LedgerState.COMPENSATION_PENDING,
                    "refund:" + operationId, NOW));

            List<RecoveryResult> results = h.scan(1);

            assertEquals("NEEDS_RECONCILIATION", results.get(0).resultingState());
            assertEquals("NEEDS_RECONCILIATION", h.state(operationId));
        }
    }

    @Test
    void compensationPendingWithoutReferenceGoesToReconciliation() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            OperationPayload payload = refundPayload(operationId, UUID.randomUUID(),
                    UUID.randomUUID(), new LandId(UUID.randomUUID()), 50L, "test-economy");
            insert(h, LedgerEntry.fromPayload(payload, LedgerState.COMPENSATION_PENDING));

            List<RecoveryResult> results = h.scan(3);

            assertEquals("NEEDS_RECONCILIATION", results.get(0).resultingState());
            assertEquals(0, h.refundCalls.get(), "no reference means no Economy call");
        }
    }

    @Test
    void terminalRefundRowsAreNoOps() throws Exception {
        try (Harness h = new Harness()) {
            UUID compensated = UUID.randomUUID();
            UUID reconciled = UUID.randomUUID();
            UUID failed = UUID.randomUUID();
            insert(h, LedgerEntry.fromPayload(
                    refundPayload(compensated, UUID.randomUUID(), UUID.randomUUID(),
                            new LandId(UUID.randomUUID()), 50L, "test-economy"),
                    LedgerState.COMPENSATED));
            insert(h, LedgerEntry.fromPayload(
                    refundPayload(reconciled, UUID.randomUUID(), UUID.randomUUID(),
                            new LandId(UUID.randomUUID()), 50L, "test-economy"),
                    LedgerState.NEEDS_RECONCILIATION));
            insert(h, LedgerEntry.fromPayload(
                    refundPayload(failed, UUID.randomUUID(), UUID.randomUUID(),
                            new LandId(UUID.randomUUID()), 50L, "test-economy"),
                    LedgerState.FAILED));

            List<RecoveryResult> results = h.scan(3);

            assertEquals(3, results.size());
            assertEquals("COMPENSATED", h.state(compensated));
            assertEquals("NEEDS_RECONCILIATION", h.state(reconciled));
            assertEquals("FAILED", h.state(failed));
            assertEquals(0, h.refundCalls.get());
        }
    }

    @Test
    void claimDomainCommittedStillRebuildsToActive() throws Exception {
        try (Harness h = new Harness()) {
            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            LandId landId = new LandId(UUID.randomUUID());
            Set<ChunkKey> keys = Set.of(new ChunkKey(world, 0, 0));
            LandSnapshot snap = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                    OwnerRef.player(actor), world, keys, List.of(), 0, 0, NOW, NOW);
            new SqliteLandRepository(h.store).save(snap).toCompletableFuture().get(10, TimeUnit.SECONDS);
            UUID operationId = UUID.randomUUID();
            OperationPayload claim = OperationPayload.claim(operationId, actor, world, landId,
                    List.of(payloadChunk(world, 0, 100L)), 100L, "test-economy", NOW, "Home");
            insert(h, LedgerEntry.fromPayload(claim, LedgerState.DOMAIN_COMMITTED));

            List<RecoveryResult> results = h.scan(3);

            assertEquals(1, results.size());
            assertEquals("ACTIVE", results.get(0).resultingState());
            assertEquals("ACTIVE", h.state(operationId));
            assertEquals(1, h.rebuildCalls.get());
            assertEquals(0, h.refundCalls.get());
        }
    }
}
