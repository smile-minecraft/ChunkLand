package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.economy.UnavailableVaultBridge;
import com.smile.chunkland.economy.VaultBridge;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryResult;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup recovery must run on an injectable Economy bridge: an available
 * provider refunds {@code CHARGED} rows to {@code COMPENSATED}, while an
 * unavailable provider stays fail-safe on retryable
 * {@code COMPENSATION_PENDING} instead of misreporting success.
 */
class ClaimStartupBootstrapEconomyTest {

    @TempDir Path temp;

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");
    private static final String PROVIDER = "test-economy";

    record DepositCall(UUID playerId, double amount, UUID operationId) {
    }

    static final class AvailableBridge implements VaultBridge {
        final List<DepositCall> deposits = new CopyOnWriteArrayList<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String providerId() {
            return PROVIDER;
        }

        @Override
        public Response withdraw(UUID playerId, double amount, UUID operationId) {
            return Response.ok();
        }

        @Override
        public Response deposit(UUID playerId, double amount, UUID operationId) {
            deposits.add(new DepositCall(playerId, amount, operationId));
            return Response.ok();
        }
    }

    private static OperationPayload payload(UUID operationId) {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000022");
        return OperationPayload.claim(operationId, actor, world,
                new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                List.of(new OperationPayload.Chunk(new ChunkKey(world, 3, 8), 12,
                        UUID.nameUUIDFromBytes((operationId + "-lot").getBytes()), 417L)),
                417L, PROVIDER, TIME, "Recovery land");
    }

    private static UUID seedCharged(Path databasePath) throws Exception {
        UUID operationId = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(databasePath)) {
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload payload = payload(operationId);
            ledger.createPaymentPending(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.transitionToCharged(operationId, "charge:" + operationId, TIME)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("CHARGED",
                    ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
        }
        return operationId;
    }

    private static LedgerEntry find(ClaimStartupBootstrap bootstrap, UUID operationId) throws Exception {
        return bootstrap.ledger().find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void availableProviderRefundsChargedRowToCompensated() throws Exception {
        Path databasePath = temp.resolve("available.db");
        UUID operationId = seedCharged(databasePath);
        AvailableBridge bridge = new AvailableBridge();
        Logger logger = Logger.getLogger("Test");

        ClaimStartupBootstrap bootstrap = ClaimStartupBootstrap.start(
                databasePath, new LandRegistryStore(), logger, bridge);
        try {
            List<RecoveryResult> results =
                    bootstrap.scanFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(1, results.size());
            assertEquals("COMPENSATED", results.get(0).resultingState());
            assertEquals("COMPENSATED", find(bootstrap, operationId).state());
            assertEquals(1, bridge.deposits.size(), "exactly one refund deposit");
            assertEquals(payload(operationId).actorUuid(), bridge.deposits.get(0).playerId());
        } finally {
            bootstrap.close();
        }
    }

    @Test
    void unavailableProviderStaysFailSafeWithoutMisreportingRefund() throws Exception {
        Path databasePath = temp.resolve("unavailable.db");
        UUID operationId = seedCharged(databasePath);
        Logger logger = Logger.getLogger("Test");

        ClaimStartupBootstrap bootstrap = ClaimStartupBootstrap.start(
                databasePath, new LandRegistryStore(), logger, new UnavailableVaultBridge());
        try {
            List<RecoveryResult> results =
                    bootstrap.scanFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(1, results.size());
            assertEquals("COMPENSATION_PENDING", results.get(0).resultingState());
            LedgerEntry row = find(bootstrap, operationId);
            assertEquals("COMPENSATION_PENDING", row.state());
            assertNotEquals("COMPENSATED", row.state(), "must never misreport a refund");
            assertNotEquals("ACTIVE", row.state(), "charged rows must never activate");
            assertEquals(1, row.compensationAttempts(), "failed refund stays retryable");
        } finally {
            bootstrap.close();
        }
    }
}
