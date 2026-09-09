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
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.RefundCommit;
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
 * Idempotency conflict coverage for reusing an operation id with a different
 * refund identity. A repeated id must resume only when the durable identity
 * matches; any land, chunk-set, actor, world, or ratio mismatch fails closed
 * without a second domain commit or deposit.
 */
class RefundIdempotencyConflictTest {

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
        final RefundValidator validator;
        final RefundSaga saga;
        final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "refund-conflict-test");
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
            validator = new DurableRefundValidator(lands, chunks, EMC);
            saga = new RefundSaga(validator, reservations, ledger, economy, rebuilder,
                    clock, async, 3);
        }

        RefundResult run(RefundRequest request) throws Exception {
            return saga.refund(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    private static int auditRefundCount(Harness h) {
        return h.audits.findByAction(RefundCommit.REFUND_AUDIT_ACTION, 100)
                .toCompletableFuture().join().size();
    }

    @Test
    void sameOperationIdDifferentLandIsRejectedAsConflict() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            ClaimedLand landX = claimLand(h, actor, UUID.randomUUID(), "LandX", 100L);
            ClaimedLand landY = claimLand(h, actor, UUID.randomUUID(), "LandY", 100L);
            UUID operationId = UUID.randomUUID();

            RefundRequest first = new RefundRequest(operationId, landX.actor(), landX.world(),
                    landX.landId(), Set.copyOf(landX.chunkKeys()), 1, 2);
            RefundResult ok = h.run(first);
            assertEquals(RefundResult.Status.SUCCESS, ok.status());
            assertEquals(1, h.economy.deposits.size());
            int auditsAfterFirst = auditRefundCount(h);

            RefundRequest second = new RefundRequest(operationId, landY.actor(), landY.world(),
                    landY.landId(), Set.copyOf(landY.chunkKeys()), 1, 2);
            RefundResult conflict = h.run(second);

            assertEquals(RefundResult.Status.REJECTED, conflict.status());
            assertEquals("refund.idempotency_conflict", conflict.diagnosticKey());
            assertEquals(1, h.economy.deposits.size(), "conflicting retry must not deposit again");
            assertEquals(auditsAfterFirst, auditRefundCount(h), "conflicting retry must not commit again");
            assertEquals(1, h.ledger.findAll().toCompletableFuture().join().size());
            assertEquals("COMPENSATED", h.ledger.find(operationId).toCompletableFuture().join().state());
        }
    }

    @Test
    void sameOperationIdDifferentChunksIsRejectedAsConflict() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            ClaimedLand land = claimLand(h, actor, world, "Home", 100L, 100L);
            UUID operationId = UUID.randomUUID();

            RefundRequest first = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.of(land.chunkKeys().get(0)), 1, 2);
            RefundResult ok = h.run(first);
            assertEquals(RefundResult.Status.SUCCESS, ok.status());
            assertEquals(1, h.economy.deposits.size());

            RefundRequest second = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 2);
            RefundResult conflict = h.run(second);

            assertEquals(RefundResult.Status.REJECTED, conflict.status());
            assertEquals("refund.idempotency_conflict", conflict.diagnosticKey());
            assertEquals(1, h.economy.deposits.size());
        }
    }

    @Test
    void sameOperationIdDifferentRatioIsRejectedAsConflict() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            ClaimedLand land = claimLand(h, actor, world, "Home", 100L);
            UUID operationId = UUID.randomUUID();

            RefundRequest first = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 2);
            RefundResult ok = h.run(first);
            assertEquals(RefundResult.Status.SUCCESS, ok.status());
            assertEquals(50L, ok.refundMinorUnits());

            // Same land and chunks but a different ratio is a different identity,
            // even though the domain row already exists.
            RefundRequest second = new RefundRequest(operationId, actor, world, land.landId(),
                    Set.copyOf(land.chunkKeys()), 1, 1);
            RefundResult conflict = h.run(second);

            assertEquals(RefundResult.Status.REJECTED, conflict.status());
            assertEquals("refund.idempotency_conflict", conflict.diagnosticKey());
            assertEquals(1, h.economy.deposits.size(), "ratio mismatch must not deposit again");
        }
    }

    @Test
    void sameOperationIdExactIdentityResumesWithoutDuplicate() throws Exception {
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
            assertEquals(1, h.ledger.findAll().toCompletableFuture().join().size());
        }
    }
}
