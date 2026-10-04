package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cross-restart idempotency for refund-class rows.
 *
 * <p>The Vault Legacy provider has no server-side dedup (the bridge cannot
 * forward an idempotency key), so exactly-once across a restart rests solely
 * on the ledger: {@code DOMAIN_COMMITTED} means the deposit was definitely
 * never attempted and may be tried exactly once; {@code COMPENSATION_PENDING}
 * means the deposit may already have moved money, so recovery must quarantine
 * for operator reconciliation instead of depositing again. Every test below
 * reopens the same database file with brand-new store, ledger, scanner and
 * handler instances, so a passing run proves the guarantee survives a restart
 * instead of an in-memory cache.
 */
class RefundRestartIdempotencyTest {

    @TempDir Path temp;

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Fresh counting handler pair; rebuilt from scratch after each simulated restart. */
    static final class CountingRefund {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<RefundOutcome> outcome =
                new AtomicReference<>(RefundOutcome.REFUNDED);

        RecoveryHandlers handlers() {
            return RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(PaymentLookup.unknown()),
                    entry -> {
                        calls.incrementAndGet();
                        RefundOutcome next = outcome.get();
                        if (next == null) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("no outcome"));
                        }
                        return CompletableFuture.completedFuture(next);
                    },
                    (entry, payload) -> CompletableFuture.failedFuture(
                            new IllegalStateException("claim replay is not configured")),
                    (entry, payload) -> CompletableFuture.completedFuture(null));
        }
    }

    private static OperationPayload payloadFor(String type, UUID operationId, UUID actor,
            UUID world, LandId landId) {
        OperationPayload.Chunk chunk =
                new OperationPayload.Chunk(new ChunkKey(world, 3, 7), 64, UUID.randomUUID(), 100L);
        return switch (type) {
            case "DELETE" -> OperationPayload.delete(operationId, actor, world, landId,
                    List.of(chunk), 100L, "test-economy", NOW, "Home");
            case "SHRINK" -> OperationPayload.shrink(operationId, actor, world, landId,
                    List.of(chunk), 50L, "test-economy", NOW, "Home", 1L, 2L);
            case "REFUND" -> new OperationPayload(operationId, "REFUND", actor, world, landId,
                    List.of(chunk), 50L, "test-economy", null, NOW, NOW,
                    OperationPayload.CURRENT_SCHEMA_VERSION, "Home");
            default -> throw new IllegalArgumentException("unknown refund-class type: " + type);
        };
    }

    private static String intentRef(String type, UUID operationId) {
        return switch (type) {
            case "DELETE" -> "delete:" + operationId;
            case "SHRINK" -> "shrink:" + operationId;
            default -> "refund:" + operationId;
        };
    }

    private static String stateOf(OperationLedger ledger, UUID operationId) throws Exception {
        return ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state();
    }

    /**
     * The crash window: the deposit succeeded but the settle never reached the
     * disk. Phase one parks the durable intent, moves the money once, then
     * "crashes" (the store is closed without settling). Phase two reopens the
     * same file with completely new instances and scans. The money must have
     * moved exactly once in total.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "SHRINK", "REFUND"})
    void depositSucceededButUnsettledIsNotDepositedAgainAfterRestart(String type)
            throws Exception {
        Path dbFile = temp.resolve(UUID.randomUUID() + ".db");
        UUID operationId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OperationPayload payload = payloadFor(type, operationId, actor, world, landId);

        CountingRefund before = new CountingRefund();
        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            // Durable execution intent, recorded before the deposit runs.
            ledger.parkForRefundCompensation(operationId, intentRef(type, operationId), NOW)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            // The single successful deposit whose settle never lands.
            LedgerEntry parked =
                    ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(RefundOutcome.REFUNDED,
                    before.handlers().refund(parked).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS),
                    "test premise: the pre-crash deposit succeeds");
            // Crash: the store closes with the row still COMPENSATION_PENDING.
        }
        assertEquals(1, before.calls.get());

        CountingRefund after = new CountingRefund();
        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            List<RecoveryResult> results = new CrashRecoveryScanner(
                    ledger, after.handlers(), CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, results.size());
            assertEquals(0, after.calls.get(),
                    "recovery must not deposit again for a possibly-sent row");
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(),
                    results.get(0).resultingState());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), stateOf(ledger, operationId));
        }
        assertEquals(1, before.calls.get() + after.calls.get(),
                "the deposit ran exactly once across the restart");
    }

    /**
     * A parked row whose deposit result was never confirmed (or was never even
     * observed) enters reconciliation without an Economy call, even when the
     * provider would currently report success.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "SHRINK", "REFUND"})
    void possiblySentRowEntersReconciliationWithoutEconomyCall(String type) throws Exception {
        Path dbFile = temp.resolve(UUID.randomUUID() + ".db");
        UUID operationId = UUID.randomUUID();
        OperationPayload payload = payloadFor(
                type, operationId, UUID.randomUUID(), UUID.randomUUID(),
                new LandId(UUID.randomUUID()));

        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.parkForRefundCompensation(operationId, intentRef(type, operationId), NOW)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        CountingRefund after = new CountingRefund();
        after.outcome.set(RefundOutcome.REFUNDED);
        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            List<RecoveryResult> results = new CrashRecoveryScanner(
                    ledger, after.handlers(), CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, results.size());
            assertEquals(0, after.calls.get(),
                    "a possibly-sent row must never be auto-resent");
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(),
                    results.get(0).resultingState());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), stateOf(ledger, operationId));
        }
    }

    /**
     * A row that provably never reached the provider is safe to try exactly
     * once: one deposit, then compensated.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "SHRINK", "REFUND"})
    void definitelyUnsentRowRetriesExactlyOnce(String type) throws Exception {
        Path dbFile = temp.resolve(UUID.randomUUID() + ".db");
        UUID operationId = UUID.randomUUID();
        OperationPayload payload = payloadFor(
                type, operationId, UUID.randomUUID(), UUID.randomUUID(),
                new LandId(UUID.randomUUID()));

        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        CountingRefund after = new CountingRefund();
        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            List<RecoveryResult> results = new CrashRecoveryScanner(
                    ledger, after.handlers(), CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, results.size());
            assertEquals(1, after.calls.get(), "a definitely-unsent row is retried once");
            assertEquals(LedgerState.COMPENSATED.name(), results.get(0).resultingState());
            assertEquals(LedgerState.COMPENSATED.name(), stateOf(ledger, operationId));
        }
    }

    /**
     * The single retry of a definitely-unsent row is still fail-closed: any
     * outcome other than a confirmed deposit quarantines instead of scheduling
     * another automatic attempt.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "SHRINK", "REFUND"})
    void failedSingleRetryEntersReconciliationWithoutFurtherAttempts(String type)
            throws Exception {
        Path dbFile = temp.resolve(UUID.randomUUID() + ".db");
        UUID operationId = UUID.randomUUID();
        OperationPayload payload = payloadFor(
                type, operationId, UUID.randomUUID(), UUID.randomUUID(),
                new LandId(UUID.randomUUID()));

        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        CountingRefund after = new CountingRefund();
        after.outcome.set(RefundOutcome.FAILED);
        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            List<RecoveryResult> results = new CrashRecoveryScanner(
                    ledger, after.handlers(), CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, results.size());
            assertEquals(1, after.calls.get(), "exactly one attempt, never retried");
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(),
                    results.get(0).resultingState());
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), stateOf(ledger, operationId));
        }

        // A further restart performs no additional deposit either.
        CountingRefund third = new CountingRefund();
        try (PersistenceStore store = PersistenceStore.open(dbFile)) {
            OperationLedger ledger = new OperationLedger(store);
            new CrashRecoveryScanner(ledger, third.handlers(), CLOCK, 3)
                    .scan().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(0, third.calls.get(), "reconciliation rows stay untouched");
            assertEquals(LedgerState.NEEDS_RECONCILIATION.name(), stateOf(ledger, operationId));
        }
    }
}
