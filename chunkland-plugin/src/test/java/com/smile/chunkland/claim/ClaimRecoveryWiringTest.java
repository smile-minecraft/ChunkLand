package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ClaimCommit;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.RecoveryResult;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the production startup wiring for claim recovery: a durable
 * {@code DOMAIN_COMMITTED} row is rebuilt from the authoritative repository
 * and advanced to {@code ACTIVE} through {@link ClaimRecoveryHandlers}, and
 * an unavailable rebuild leaves the row untouched instead of marking it
 * active or refunding it.
 */
class ClaimRecoveryWiringTest {

    @TempDir Path temp;

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");

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

    @Test
    void startupScanRebuildsDomainCommittedRowThroughProductionFactory() throws Exception {
        UUID operationId = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            FakeEconomy economy = new FakeEconomy();

            OperationPayload payload = payload(operationId, TIME);
            ledger.createPaymentPending(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.transitionToCharged(operationId, "startup-ref", payload.updatedAt())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.commitClaimAtomically(commit(payload)).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("DOMAIN_COMMITTED",
                    ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state());

            RecoveryHandlers handlers = ClaimRecoveryHandlers.forStartup(economy, rebuilder);
            List<RecoveryResult> results = ClaimRecoveryHandlers
                    .scanAtStartup(ledger, economy, rebuilder)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertNotNull(handlers, "production factory must supply handlers");
            assertEquals(1, results.size());
            assertEquals("ACTIVE", results.get(0).resultingState());
            assertEquals("ACTIVE",
                    ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            assertNotNull(registryStore.snapshot().findLand(payload.worldUuid(), 7, -3));
            assertTrue(economy.refundCalls.isEmpty(), "rebuild recovery must never refund");
        }
    }

    @Test
    void unavailableRuntimeRebuildKeepsDomainCommittedWithoutRefund() throws Exception {
        UUID operationId = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(new FailingLands(), registryStore);
            FakeEconomy economy = new FakeEconomy();

            OperationPayload payload = payload(operationId, TIME);
            ledger.createPaymentPending(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.transitionToCharged(operationId, "startup-ref", payload.updatedAt())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.commitClaimAtomically(commit(payload)).toCompletableFuture().get(10, TimeUnit.SECONDS);

            List<RecoveryResult> results = ClaimRecoveryHandlers
                    .scanAtStartup(ledger, economy, rebuilder)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, results.size());
            assertEquals("DOMAIN_COMMITTED", results.get(0).resultingState());
            assertEquals("DOMAIN_COMMITTED",
                    ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            assertTrue(economy.refundCalls.isEmpty(), "failed rebuild must never refund");
            assertTrue(ledger.landExists(payload.targetLandId())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "durable domain truth must be retained for the next startup");
        }
    }

    private static OperationPayload payload(UUID operationId, Instant time) {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000012");
        String displayName = "Recovered land " + operationId.toString().substring(28);
        return OperationPayload.claim(operationId, actor, world,
                new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                List.of(new OperationPayload.Chunk(new ChunkKey(world, 7, -3), 12,
                        UUID.nameUUIDFromBytes((operationId + "-lot").getBytes()), 417L)),
                417L, "test-economy", time, displayName);
    }

    private static ClaimCommit commit(OperationPayload payload) {
        ChunkKey chunk = payload.chunkSet().get(0).chunk();
        LandId landId = payload.targetLandId();
        LandSnapshot land = new LandSnapshot(landId, payload.landDisplayName(),
                LandName.normalize(payload.landDisplayName()), OwnerRef.player(payload.actorUuid()),
                payload.worldUuid(), Set.of(chunk), List.of(), 0, 0,
                payload.createdAt(), payload.updatedAt());
        AuditEntry audit = new AuditEntry(0, payload.updatedAt(), payload.actorUuid(), "LAND_CREATE",
                landId, payload.worldUuid(), null, payload.schemaVersion(), null,
                payload.toJson(), "{}", List.of(chunk));
        return new ClaimCommit(payload.operationId(), land, payload.chunkSet(), audit);
    }
}
