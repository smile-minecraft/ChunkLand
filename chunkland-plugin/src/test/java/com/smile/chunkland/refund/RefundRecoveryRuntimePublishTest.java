package com.smile.chunkland.refund;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.CrashRecoveryScanner;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.RecoveryResult;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
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
 * Recovery runtime publish for refund rows: a {@code DOMAIN_COMMITTED} refund
 * whose domain already released its chunks must rebuild and publish the
 * runtime after a successful compensation, so the stale snapshot no longer
 * contains the released chunks. Failures keep the durable retry state without
 * a false publish, and the claim path still rebuilds towards {@code ACTIVE}.
 */
class RefundRecoveryRuntimePublishTest {

    @TempDir Path temp;

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static OperationPayload.Chunk payloadChunk(UUID world, int x, long basis) {
        return new OperationPayload.Chunk(new ChunkKey(world, x, 0), 64, UUID.randomUUID(), basis);
    }

    @Test
    void refundDomainCommittedRebuildsRuntimeAfterCompensation() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);

            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId landId = new LandId(UUID.randomUUID());
            ChunkKey key = new ChunkKey(world, 0, 0);
            LandSnapshot snap = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                    OwnerRef.player(actor), world, Set.of(key), List.of(), 0, 0, NOW, NOW);
            lands.save(snap).toCompletableFuture().get(10, TimeUnit.SECONDS);
            chunks.addChunk(landId, key, 64, UUID.randomUUID(), 100L)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            // Publish the pre-refund runtime so it still contains the land.
            rebuilder.rebuild().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(registryStore.snapshot().lands().containsKey(landId));

            // Simulate the crash window: the refund domain commit released the
            // chunks in durable storage, but the runtime was never republished.
            UUID operationId = UUID.randomUUID();
            OperationPayload payload = new OperationPayload(operationId,
                    RefundSaga.REFUND_OPERATION_TYPE, actor, world, landId,
                    List.of(payloadChunk(world, 0, 100L)), 50L, "test-economy", null,
                    NOW, NOW, OperationPayload.CURRENT_SCHEMA_VERSION, "Home");
            ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            // Durable domain truth no longer has the chunk; runtime is stale.
            chunks.removeChunk(key).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(registryStore.snapshot().lands().containsKey(landId),
                    "runtime is stale before recovery");

            AtomicInteger refundCalls = new AtomicInteger();
            AtomicInteger rebuildCalls = new AtomicInteger();
            RecoveryHandlers handlers = RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(
                            com.smile.chunkland.persistence.PaymentLookup.unknown()),
                    entry -> {
                        refundCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
                    },
                    (entry, p) -> CompletableFuture.failedFuture(
                            new IllegalStateException("claim replay is not configured")),
                    (entry, p) -> {
                        rebuildCalls.incrementAndGet();
                        return rebuilder.rebuildForRecovery(entry, p);
                    });

            List<RecoveryResult> results = new CrashRecoveryScanner(ledger, handlers, CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, results.size());
            assertEquals("COMPENSATED", results.get(0).resultingState());
            assertEquals("COMPENSATED", ledger.find(operationId).toCompletableFuture().join().state());
            assertEquals(1, refundCalls.get());
            assertEquals(1, rebuildCalls.get(), "successful refund recovery must rebuild runtime");
            assertNull(registryStore.snapshot().findLand(world, 0, 0),
                    "released chunks must leave the published runtime");
        }
    }

    @Test
    void refundCompensationFailureDoesNotPublishOrSettle() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            AtomicInteger rebuildCalls = new AtomicInteger();
            AtomicReference<RefundOutcome> outcome = new AtomicReference<>(RefundOutcome.FAILED);

            UUID operationId = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            OperationPayload payload = new OperationPayload(operationId,
                    RefundSaga.REFUND_OPERATION_TYPE, UUID.randomUUID(), world,
                    new LandId(UUID.randomUUID()), List.of(payloadChunk(world, 0, 100L)),
                    50L, "test-economy", null, NOW, NOW,
                    OperationPayload.CURRENT_SCHEMA_VERSION, "Home");
            ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            RecoveryHandlers handlers = RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(
                            com.smile.chunkland.persistence.PaymentLookup.unknown()),
                    entry -> CompletableFuture.completedFuture(outcome.get()),
                    (entry, p) -> CompletableFuture.failedFuture(
                            new IllegalStateException("claim replay is not configured")),
                    (entry, p) -> {
                        rebuildCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });

            List<RecoveryResult> results = new CrashRecoveryScanner(ledger, handlers, CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals("COMPENSATION_PENDING", results.get(0).resultingState());
            assertEquals("COMPENSATION_PENDING",
                    ledger.find(operationId).toCompletableFuture().join().state());
            assertEquals(0, rebuildCalls.get(), "failed compensation must not publish runtime");

            // Unknown outcome keeps the same pending contract.
            outcome.set(RefundOutcome.UNKNOWN);
            UUID secondId = UUID.randomUUID();
            UUID secondWorld = UUID.randomUUID();
            OperationPayload second = new OperationPayload(secondId,
                    RefundSaga.REFUND_OPERATION_TYPE, UUID.randomUUID(), secondWorld,
                    new LandId(UUID.randomUUID()), List.of(payloadChunk(secondWorld, 0, 100L)),
                    50L, "test-economy", null, NOW, NOW,
                    OperationPayload.CURRENT_SCHEMA_VERSION, "Home");
            ledger.insert(LedgerEntry.fromPayload(second, LedgerState.COMPENSATION_PENDING,
                    "refund:" + secondId, NOW)).toCompletableFuture().get(10, TimeUnit.SECONDS);

            List<RecoveryResult> retry = new CrashRecoveryScanner(ledger, handlers, CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);
            // The scan processes both rows in order; the second row stays pending.
            RecoveryResult secondResult = retry.stream()
                    .filter(r -> r.operationId().equals(secondId)).findFirst().orElseThrow();
            assertEquals("COMPENSATION_PENDING", secondResult.resultingState());
            assertEquals(0, rebuildCalls.get(), "unknown outcome must not publish runtime");
        }
    }

    @Test
    void claimDomainCommittedStillRebuildsToActive() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            AtomicInteger rebuildCalls = new AtomicInteger();
            AtomicInteger refundCalls = new AtomicInteger();

            UUID world = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            LandId landId = new LandId(UUID.randomUUID());
            LandSnapshot snap = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                    OwnerRef.player(actor), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, NOW, NOW);
            lands.save(snap).toCompletableFuture().get(10, TimeUnit.SECONDS);
            UUID operationId = UUID.randomUUID();
            OperationPayload claim = OperationPayload.claim(operationId, actor, world, landId,
                    List.of(payloadChunk(world, 0, 100L)), 100L, "test-economy", NOW, "Home");
            ledger.insert(LedgerEntry.fromPayload(claim, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            RecoveryHandlers handlers = RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(
                            com.smile.chunkland.persistence.PaymentLookup.unknown()),
                    entry -> {
                        refundCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
                    },
                    (entry, p) -> CompletableFuture.failedFuture(
                            new IllegalStateException("claim replay is not configured")),
                    (entry, p) -> {
                        rebuildCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });

            List<RecoveryResult> results = new CrashRecoveryScanner(ledger, handlers, CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, results.size());
            assertEquals("ACTIVE", results.get(0).resultingState());
            assertEquals(1, rebuildCalls.get());
            assertEquals(0, refundCalls.get(), "claim rebuild must not call the refund path");
        }
    }
}
