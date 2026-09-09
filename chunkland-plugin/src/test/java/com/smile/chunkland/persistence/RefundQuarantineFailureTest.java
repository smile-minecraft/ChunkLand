package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.refund.DurableRefundValidator;
import com.smile.chunkland.refund.RefundEconomy;
import com.smile.chunkland.refund.RefundResult;
import com.smile.chunkland.refund.RefundSaga;
import com.smile.chunkland.refund.RefundValidator;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A malformed non-terminal refund row must only report
 * {@code NEEDS_RECONCILIATION} after its quarantine write actually succeeds.
 * When the quarantine write fails, the saga must report an explicit ledger
 * failure instead of claiming a durable transition that never happened, and
 * must leave Economy, the domain, and the runtime untouched.
 */
class RefundQuarantineFailureTest {

    @TempDir Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class FakeDeposits implements RefundEconomy {
        final List<LedgerEntry> deposits = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletionStage<RefundOutcome> deposit(LedgerEntry entry) {
            deposits.add(entry);
            return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
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
            Thread t = new Thread(r, "refund-quarantine-failure-test");
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

        RefundResult retry(UUID operationId) throws Exception {
            return saga.retry(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        /** Fail every later UPDATE on the ledger while leaving SELECT reads working. */
        void failLedgerUpdates() {
            store.submitAsync(conn -> {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("CREATE TRIGGER quarantine_fail BEFORE UPDATE ON operation_ledger "
                            + "BEGIN SELECT RAISE(ABORT, 'injected quarantine failure'); END;");
                }
                return null;
            }).toCompletableFuture().join();
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

    private static LedgerEntry malformedRow(UUID operationId, LedgerState state) {
        return new LedgerEntry(operationId, RefundSaga.REFUND_OPERATION_TYPE, state.name(),
                UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()),
                50L, "test-economy",
                state == LedgerState.COMPENSATION_PENDING ? "refund:" + operationId : null,
                "{broken-payload", OperationPayload.CURRENT_SCHEMA_VERSION, NOW, NOW, 0);
    }

    @Test
    void malformedNonTerminalRowWithFailingQuarantineReportsLedgerFailure() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            h.ledger.insert(malformedRow(operationId, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            h.failLedgerUpdates();

            RefundResult result = h.retry(operationId);

            assertEquals(RefundResult.Status.FAILED, result.status(),
                    "a failed quarantine must not claim NEEDS_RECONCILIATION");
            assertEquals("refund.ledger_failed", result.diagnosticKey());
            assertTrue(h.economy.deposits.isEmpty(), "a failed quarantine must never deposit");
        }
    }

    @Test
    void malformedNonTerminalRowWithWorkingQuarantineStillReconciles() throws Exception {
        try (Harness h = new Harness()) {
            UUID operationId = UUID.randomUUID();
            h.ledger.insert(malformedRow(operationId, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            RefundResult result = h.retry(operationId);

            assertEquals(RefundResult.Status.NEEDS_RECONCILIATION, result.status());
            assertEquals("NEEDS_RECONCILIATION",
                    h.ledger.find(operationId).toCompletableFuture().join().state());
            assertTrue(h.economy.deposits.isEmpty());
        }
    }
}
