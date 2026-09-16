package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.LedgerState.RecoveryClassification;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Consolidated crash/restart matrix for the §46-1 ledger states and the §97
 * persistence cases.
 *
 * <p>Earlier suites prove each saga and each refund path in isolation; this
 * class pins the whole table in one place: every non-terminal state has its
 * recovery outcome, publish failure after the durable commit never refunds and
 * stays observable for a runtime rebuild, retry exhaustion quarantines without
 * a second money movement, and {@code NEEDS_RECONCILIATION} rows are never
 * touched again by a later scan. Recovery never replays the domain beyond the
 * single {@code CHARGED} replay, never writes a second ledger row, and never
 * moves money twice. All concurrency in this class is barrier/latch driven;
 * no sleep is used.
 */
class CrashRecoveryMatrixTest {

    @TempDir Path temp;

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(TIME, ZoneOffset.UTC);
    private static final String PROVIDER = "matrix-economy";

    /** Counting handlers with per-operation lookup/refund behaviour. */
    final class MatrixHandlers {
        final Map<UUID, PaymentLookup> lookups = new ConcurrentHashMap<>();
        final Map<UUID, RefundOutcome> refunds = new ConcurrentHashMap<>();
        final Map<UUID, ClaimCommit> replays = new ConcurrentHashMap<>();
        final AtomicInteger lookupCalls = new AtomicInteger();
        final AtomicInteger refundCalls = new AtomicInteger();
        final AtomicInteger replayCalls = new AtomicInteger();
        final AtomicInteger rebuildCalls = new AtomicInteger();
        volatile boolean failRebuild;

        RecoveryHandlers handlers() {
            return RecoveryHandlers.of(
                    entry -> {
                        lookupCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(
                                lookups.getOrDefault(entry.operationId(), PaymentLookup.unknown()));
                    },
                    entry -> {
                        refundCalls.incrementAndGet();
                        RefundOutcome outcome = refunds.get(entry.operationId());
                        if (outcome == null) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("no refund behaviour"));
                        }
                        return CompletableFuture.completedFuture(outcome);
                    },
                    (entry, payload) -> {
                        replayCalls.incrementAndGet();
                        ClaimCommit commit = replays.get(entry.operationId());
                        if (commit == null) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("domain replay rejected"));
                        }
                        return CompletableFuture.completedFuture(commit);
                    },
                    (entry, payload) -> {
                        rebuildCalls.incrementAndGet();
                        if (failRebuild) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("runtime unavailable"));
                        }
                        return CompletableFuture.completedFuture(null);
                    });
        }
    }

    private static OperationPayload.Chunk chunk(UUID world, int index, long basis) {
        return new OperationPayload.Chunk(
                new ChunkKey(world, 100 + index, 200), 12,
                UUID.nameUUIDFromBytes(("lot-" + index).getBytes()), basis);
    }

    private static OperationPayload claimPayload(UUID operationId, UUID actor, UUID world,
            int index, long price, Instant time) {
        return OperationPayload.claim(operationId, actor, world,
                new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                List.of(chunk(world, index, price)), price, PROVIDER, time,
                "Matrix land " + index);
    }

    private static OperationPayload refundPayload(UUID operationId, UUID actor, UUID world,
            int index, long price, Instant time) {
        return new OperationPayload(operationId, "REFUND", actor, world,
                new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                List.of(chunk(world, index, Math.max(price, 0L))), price, PROVIDER, null,
                time, time, OperationPayload.CURRENT_SCHEMA_VERSION, "Matrix land " + index);
    }

    private static ClaimCommit commitOf(OperationPayload payload) {
        ChunkKey chunk = payload.chunkSet().get(0).chunk();
        LandSnapshot land = new LandSnapshot(payload.targetLandId(), payload.landDisplayName(),
                LandName.normalize(payload.landDisplayName()), OwnerRef.player(payload.actorUuid()),
                payload.worldUuid(), Set.of(chunk), List.of(), 0, 0,
                payload.createdAt(), payload.updatedAt());
        AuditEntry audit = new AuditEntry(0, payload.updatedAt(), payload.actorUuid(), "LAND_CREATE",
                payload.targetLandId(), payload.worldUuid(), null, payload.schemaVersion(), null,
                payload.toJson(), "{}", List.of(chunk));
        return new ClaimCommit(payload.operationId(), land, payload.chunkSet(), audit);
    }

    private static void insert(OperationLedger ledger, LedgerEntry entry) throws Exception {
        ledger.insert(entry).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static String stateOf(OperationLedger ledger, UUID operationId) throws Exception {
        return ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state();
    }

    private static RecoveryResult resultFor(List<RecoveryResult> results, UUID operationId) {
        return results.stream().filter(result -> result.operationId().equals(operationId))
                .findFirst().orElseThrow(() -> new AssertionError("missing result " + operationId));
    }

    @Test
    void chargePathMatrixEveryStateHasItsRecoveryOutcome() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            MatrixHandlers matrix = new MatrixHandlers();

            UUID created = UUID.randomUUID();
            UUID unpaid = UUID.randomUUID();
            UUID unknown = UUID.randomUUID();
            UUID paid = UUID.randomUUID();
            UUID chargedFail = UUID.randomUUID();
            UUID chargedNoRef = UUID.randomUUID();
            UUID active = UUID.randomUUID();
            UUID failed = UUID.randomUUID();
            UUID compensated = UUID.randomUUID();
            UUID needs = UUID.randomUUID();

            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(created, actor, world, 1, 100L, TIME), LedgerState.CREATED));
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(unpaid, actor, world, 2, 100L, TIME.plusSeconds(1)),
                    LedgerState.PAYMENT_PENDING));
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(unknown, actor, world, 3, 100L, TIME.plusSeconds(2)),
                    LedgerState.PAYMENT_PENDING));
            OperationPayload paidPayload = claimPayload(paid, actor, world, 4, 100L, TIME.plusSeconds(3));
            insert(ledger, LedgerEntry.fromPayload(paidPayload, LedgerState.PAYMENT_PENDING));
            OperationPayload failPayload =
                    claimPayload(chargedFail, actor, world, 5, 100L, TIME.plusSeconds(4));
            insert(ledger, LedgerEntry.fromPayload(
                    failPayload, LedgerState.CHARGED, "charge-ref-fail", TIME.plusSeconds(4)));
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(chargedNoRef, actor, world, 6, 100L, TIME.plusSeconds(5)),
                    LedgerState.CHARGED));
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(active, actor, world, 7, 100L, TIME.plusSeconds(6)),
                    LedgerState.ACTIVE));
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(failed, actor, world, 8, 100L, TIME.plusSeconds(7)),
                    LedgerState.FAILED));
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(compensated, actor, world, 9, 100L, TIME.plusSeconds(8)),
                    LedgerState.COMPENSATED, "charge-ref-done", TIME.plusSeconds(8)));
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(needs, actor, world, 10, 100L, TIME.plusSeconds(9)),
                    LedgerState.NEEDS_RECONCILIATION));

            matrix.lookups.put(unpaid, PaymentLookup.unpaid());
            matrix.lookups.put(unknown, PaymentLookup.unknown());
            matrix.lookups.put(paid, PaymentLookup.paid("paid-ref"));
            matrix.replays.put(paid, commitOf(paidPayload));
            matrix.refunds.put(chargedFail, RefundOutcome.REFUNDED);

            List<RecoveryResult> results = new CrashRecoveryScanner(
                    ledger, matrix.handlers(), CLOCK, 3).scan()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(10, results.size(), "one result per inserted row, in scan order");
            assertEquals(
                    List.of(created, unpaid, unknown, paid, chargedFail, chargedNoRef,
                            active, failed, compensated, needs),
                    results.stream().map(RecoveryResult::operationId).toList(),
                    "scan order follows the ledger created-at/operation-id order");

            assertEquals("FAILED", resultFor(results, created).resultingState());
            assertEquals(RecoveryClassification.FAIL_UNCHARGED,
                    resultFor(results, created).classification());
            assertEquals("FAILED", resultFor(results, unpaid).resultingState());
            assertEquals("NEEDS_RECONCILIATION", resultFor(results, unknown).resultingState());
            assertEquals("ACTIVE", resultFor(results, paid).resultingState());
            assertEquals("COMPENSATED", resultFor(results, chargedFail).resultingState());
            assertEquals(RecoveryClassification.RETRY_COMPENSATION,
                    resultFor(results, chargedFail).classification());
            assertEquals("NEEDS_RECONCILIATION", resultFor(results, chargedNoRef).resultingState());
            assertEquals("ACTIVE", resultFor(results, active).resultingState());
            assertEquals("FAILED", resultFor(results, failed).resultingState());
            assertEquals("COMPENSATED", resultFor(results, compensated).resultingState());
            assertEquals("NEEDS_RECONCILIATION", resultFor(results, needs).resultingState());

            assertEquals("FAILED", stateOf(ledger, created));
            assertEquals("FAILED", stateOf(ledger, unpaid));
            assertEquals("NEEDS_RECONCILIATION", stateOf(ledger, unknown));
            assertEquals("ACTIVE", stateOf(ledger, paid));
            assertEquals("COMPENSATED", stateOf(ledger, chargedFail));
            assertEquals("NEEDS_RECONCILIATION", stateOf(ledger, chargedNoRef));

            assertEquals(1, matrix.refundCalls.get(),
                    "only the replay-rejected CHARGED row may touch Economy");
            assertEquals(10, ledger.findAll().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).size(), "recovery must not write a second ledger row");
            assertEquals(0, ledger.find(chargedFail).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).compensationAttempts(),
                    "a confirmed refund settles without opening a retry count");
        }
    }

    @Test
    void domainCommittedPublishFailureStaysObservableAndRecoveryRebuildsRuntime() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            SqliteLandRepository lands = new SqliteLandRepository(store);
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder live = new RuntimeRegistryRebuilder(lands, registryStore);
            MatrixHandlers matrix = new MatrixHandlers();

            UUID claimId = UUID.randomUUID();
            OperationPayload payload = claimPayload(claimId, actor, world, 21, 100L, TIME);
            ledger.createPaymentPending(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.transitionToCharged(claimId, "charge-ref", TIME)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.commitClaimAtomically(commitOf(payload))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("DOMAIN_COMMITTED", stateOf(ledger, claimId));

            UUID refundId = UUID.randomUUID();
            insert(ledger, LedgerEntry.fromPayload(
                    refundPayload(refundId, actor, world, 22, 50L, TIME.plusSeconds(1)),
                    LedgerState.DOMAIN_COMMITTED));
            UUID zeroId = UUID.randomUUID();
            insert(ledger, LedgerEntry.fromPayload(
                    refundPayload(zeroId, actor, world, 23, 0L, TIME.plusSeconds(2)),
                    LedgerState.DOMAIN_COMMITTED));
            matrix.refunds.put(refundId, RefundOutcome.REFUNDED);

            // Publish window one: the runtime is unavailable, so the durable
            // claim row must stay pinned instead of settling or refunding.
            matrix.failRebuild = true;
            List<RecoveryResult> pinned = new CrashRecoveryScanner(
                    ledger, matrix.handlers(), CLOCK, 3).scan()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals("DOMAIN_COMMITTED", resultFor(pinned, claimId).resultingState());
            assertEquals(RecoveryClassification.REBUILD_RUNTIME,
                    resultFor(pinned, claimId).classification());
            assertEquals("DOMAIN_COMMITTED", stateOf(ledger, claimId));
            assertEquals(0, ledger.find(claimId).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).compensationAttempts());
            assertEquals(1, matrix.refundCalls.get(),
                    "only the refund row may call Economy; the pinned claim and zero rows must not");
            assertTrue(ledger.landExists(payload.targetLandId()).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS), "durable domain truth is retained");

            // Publish window two: rebuild from the authoritative database and
            // advance the claim row; the refund and zero rows settle alongside.
            int refundsBefore = matrix.refundCalls.get();
            RecoveryHandlers liveHandlers = RecoveryHandlers.of(
                    entry -> CompletableFuture.completedFuture(PaymentLookup.unknown()),
                    entry -> {
                        matrix.refundCalls.incrementAndGet();
                        RefundOutcome outcome = matrix.refunds.get(entry.operationId());
                        if (outcome == null) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("no refund behaviour"));
                        }
                        return CompletableFuture.completedFuture(outcome);
                    },
                    (entry, replayPayload) -> CompletableFuture.failedFuture(
                            new IllegalStateException("no replay after commit")),
                    live::rebuildForRecovery);
            List<RecoveryResult> rebuilt = new CrashRecoveryScanner(
                    ledger, liveHandlers, CLOCK, 3).scan()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals("ACTIVE", resultFor(rebuilt, claimId).resultingState());
            assertEquals("ACTIVE", stateOf(ledger, claimId));
            assertNotNull(registryStore.snapshot().findLand(world, 100 + 21, 200),
                    "recovery must publish the durable domain into the runtime index");
            assertEquals("COMPENSATED", resultFor(rebuilt, refundId).resultingState());
            assertEquals("COMPENSATED", resultFor(rebuilt, zeroId).resultingState());
            assertEquals("zero-value", ledger.find(zeroId).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).economyTransactionRef());
            assertEquals(refundsBefore, matrix.refundCalls.get(),
                    "settlement is idempotent: the rescan repeats no Economy call");
        }
    }

    @Test
    void compensationExhaustionQuarantinesAndManualRowsStayUntouched() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            MatrixHandlers matrix = new MatrixHandlers();

            UUID exhausted = UUID.randomUUID();
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(exhausted, actor, world, 31, 100L, TIME),
                    LedgerState.COMPENSATION_PENDING, "charge-ref", TIME));
            UUID noRef = UUID.randomUUID();
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(noRef, actor, world, 32, 100L, TIME.plusSeconds(1)),
                    LedgerState.COMPENSATION_PENDING));
            UUID brokenId = UUID.randomUUID();
            insert(ledger, new LedgerEntry(brokenId, "REFUND",
                    LedgerState.COMPENSATION_PENDING.name(), actor, world,
                    new LandId(UUID.randomUUID()), 50L, PROVIDER, "refund:" + brokenId, "{broken",
                    OperationPayload.CURRENT_SCHEMA_VERSION, TIME.plusSeconds(2),
                    TIME.plusSeconds(2), 0));
            UUID manual = UUID.randomUUID();
            insert(ledger, LedgerEntry.fromPayload(
                    claimPayload(manual, actor, world, 33, 100L, TIME.plusSeconds(3)),
                    LedgerState.NEEDS_RECONCILIATION));
            matrix.refunds.put(exhausted, RefundOutcome.FAILED);

            List<RecoveryResult> first = new CrashRecoveryScanner(
                    ledger, matrix.handlers(), CLOCK, 1).scan()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals("NEEDS_RECONCILIATION", resultFor(first, exhausted).resultingState());
            assertEquals("NEEDS_RECONCILIATION", stateOf(ledger, exhausted));
            assertEquals(1, ledger.find(exhausted).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).compensationAttempts());
            assertEquals("NEEDS_RECONCILIATION", resultFor(first, noRef).resultingState());
            assertEquals("NEEDS_RECONCILIATION", resultFor(first, brokenId).resultingState());
            assertEquals("NEEDS_RECONCILIATION", resultFor(first, manual).resultingState());
            assertEquals(1, matrix.refundCalls.get(),
                    "rows without a reference or with a broken payload must never call Economy");

            int callsAfterFirst = matrix.refundCalls.get();
            List<RecoveryResult> second = new CrashRecoveryScanner(
                    ledger, matrix.handlers(), CLOCK, 1).scan()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals("NEEDS_RECONCILIATION", resultFor(second, exhausted).resultingState());
            assertEquals("NEEDS_RECONCILIATION", resultFor(second, noRef).resultingState());
            assertEquals("NEEDS_RECONCILIATION", resultFor(second, brokenId).resultingState());
            assertEquals("NEEDS_RECONCILIATION", resultFor(second, manual).resultingState());
            assertEquals(callsAfterFirst, matrix.refundCalls.get(),
                    "quarantined rows wait for an operator: a rescan repairs and refunds nothing");
            assertEquals(1, ledger.find(exhausted).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).compensationAttempts(),
                    "a rescan must not advance the durable retry count");
            assertEquals(4, ledger.findAll().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).size(), "recovery must not write new ledger rows");
        }
    }

    @Test
    void concurrentInsertsRecoverInDeterministicOrderAndRescanIsIdempotent() throws Exception {
        int writers = 4;
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            OperationLedger ledger = new OperationLedger(store);
            MatrixHandlers matrix = new MatrixHandlers();
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();

            CyclicBarrier start = new CyclicBarrier(writers + 1);
            CountDownLatch done = new CountDownLatch(writers);
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            try {
                for (int writer = 0; writer < writers; writer++) {
                    final int index = writer;
                    pool.execute(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            UUID operationId = UUID.randomUUID();
                            insert(ledger, LedgerEntry.fromPayload(
                                    claimPayload(operationId, actor, world, 40 + index, 100L,
                                            TIME.plusSeconds(index)),
                                    LedgerState.CREATED));
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.await(10, TimeUnit.SECONDS);
                assertTrue(done.await(10, TimeUnit.SECONDS), "all barrier writers must finish");
            } finally {
                pool.shutdownNow();
            }

            List<RecoveryResult> first = new CrashRecoveryScanner(
                    ledger, matrix.handlers(), CLOCK, 3).scan()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(writers, first.size());
            for (RecoveryResult result : first) {
                assertEquals("FAILED", result.resultingState());
                assertEquals(RecoveryClassification.FAIL_UNCHARGED, result.classification());
            }
            List<RecoveryResult> ordered = List.copyOf(first);
            assertEquals(ordered.stream().map(RecoveryResult::operationId).toList(),
                    ledger.findAll().toCompletableFuture().get(10, TimeUnit.SECONDS).stream()
                            .map(LedgerEntry::operationId).toList(),
                    "scan order follows the ledger created-at/operation-id order");
            assertEquals(0, matrix.lookupCalls.get());
            assertEquals(0, matrix.refundCalls.get());
            assertEquals(0, matrix.rebuildCalls.get());

            List<RecoveryResult> second = new CrashRecoveryScanner(
                    ledger, matrix.handlers(), CLOCK, 3).scan()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(first.stream().map(RecoveryResult::resultingState).toList(),
                    second.stream().map(RecoveryResult::resultingState).toList());
            assertEquals(0, matrix.lookupCalls.get());
            assertEquals(0, matrix.refundCalls.get());
            assertEquals(0, matrix.rebuildCalls.get(),
                    "terminal rows are no-ops: a rescan repeats no side effect");
        }
    }
}
