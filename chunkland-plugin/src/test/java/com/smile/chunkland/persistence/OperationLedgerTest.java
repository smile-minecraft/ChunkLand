package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationLedgerTest {

    @TempDir Path temporaryDirectory;

    @Test
    void everyStateHasAClassificationAndTerminalRowsAreNotTransitions() {
        assertEquals(9, LedgerState.values().length);
        assertEquals(LedgerState.RecoveryClassification.FAIL_UNCHARGED,
                LedgerState.CREATED.recoveryClassification());
        assertEquals(LedgerState.RecoveryClassification.LOOKUP_PAYMENT,
                LedgerState.PAYMENT_PENDING.recoveryClassification());
        assertEquals(LedgerState.RecoveryClassification.REPLAY_DOMAIN,
                LedgerState.CHARGED.recoveryClassification());
        assertEquals(LedgerState.RecoveryClassification.REBUILD_RUNTIME,
                LedgerState.DOMAIN_COMMITTED.recoveryClassification());
        assertEquals(LedgerState.RecoveryClassification.RETRY_COMPENSATION,
                LedgerState.COMPENSATION_PENDING.recoveryClassification());
        assertEquals(LedgerState.RecoveryClassification.WAIT_FOR_OPERATOR,
                LedgerState.NEEDS_RECONCILIATION.recoveryClassification());
        assertTrue(LedgerState.ACTIVE.isTerminal());
        assertTrue(LedgerState.FAILED.isTerminal());
        assertTrue(LedgerState.COMPENSATED.isTerminal());
        assertTrue(LedgerState.NEEDS_RECONCILIATION.isTerminal());
        assertFalse(LedgerState.ACTIVE.canTransitionTo(LedgerState.FAILED));
        assertThrows(IllegalArgumentException.class, () -> LedgerState.parse(" payment_pending "));
    }

    @Test
    void typedCompareAndSetRejectsIllegalTerminalAndSecondWriterAttempts() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationPayload payload = payload();
            SqliteLedgerRepository repository = new SqliteLedgerRepository(store);
            repository.insert(LedgerEntry.fromPayload(payload, LedgerState.CREATED)).toCompletableFuture().join();

            assertThrows(IllegalArgumentException.class,
                    () -> repository.compareAndSetState(payload.operationId(), LedgerState.CREATED,
                            LedgerState.ACTIVE, payload.updatedAt()));
            assertEquals("CREATED", state(store, payload.operationId()));

            repository.compareAndSetState(payload.operationId(), LedgerState.CREATED,
                    LedgerState.PAYMENT_PENDING, payload.updatedAt()).toCompletableFuture().join();
            CompletionException secondWriter = assertThrows(CompletionException.class,
                    () -> repository.compareAndSetState(payload.operationId(), LedgerState.CREATED,
                            LedgerState.PAYMENT_PENDING, payload.updatedAt()).toCompletableFuture().join());
            assertTrue(secondWriter.getCause() instanceof PersistenceException);
            assertEquals("PAYMENT_PENDING", state(store, payload.operationId()));

            repository.compareAndSetState(payload.operationId(), LedgerState.PAYMENT_PENDING,
                    LedgerState.FAILED, payload.updatedAt()).toCompletableFuture().join();
            assertThrows(IllegalArgumentException.class,
                    () -> repository.compareAndSetState(payload.operationId(), LedgerState.FAILED,
                            LedgerState.ACTIVE, payload.updatedAt()));
            assertEquals("FAILED", state(store, payload.operationId()));

            CompletionException rawBypass = assertThrows(CompletionException.class,
                    () -> repository.updateState(payload.operationId(), "LEGACY_STATE", payload.updatedAt())
                            .toCompletableFuture().join());
            assertTrue(rawBypass.getCause() instanceof PersistenceException);
            assertEquals("FAILED", state(store, payload.operationId()));
        }
    }

    @Test
    void typedOperationInsertRejectsMalformedStateBeforePersistence() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationPayload payload = payload();
            LedgerEntry blank = new LedgerEntry(payload.operationId(), payload.operationType(), "", payload.actorUuid(),
                    payload.worldUuid(), payload.targetLandId(), payload.priceMinorUnits(), payload.economyProviderId(),
                    null, payload.toJson(), payload.schemaVersion(), payload.createdAt(), payload.updatedAt());
            LedgerEntry unknown = new LedgerEntry(UUID.randomUUID(), payload.operationType(), "NOT_A_STATE",
                    payload.actorUuid(), payload.worldUuid(), payload.targetLandId(), payload.priceMinorUnits(),
                    payload.economyProviderId(), null, payload.toJson(), payload.schemaVersion(), payload.createdAt(),
                    payload.updatedAt());

            OperationLedger ledger = new OperationLedger(store);
            assertThrows(IllegalArgumentException.class, () -> ledger.insert(blank));
            assertThrows(IllegalArgumentException.class, () -> ledger.insert(unknown));
            assertEquals(0, count(store, "operation_ledger"));
        }
    }

    @Test
    void startupRecoveryEntryPointUsesTheExistingScannerWithoutInventingHandlers() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationPayload payload = payload();
            OperationLedger ledger = new OperationLedger(store);
            ledger.create(payload).toCompletableFuture().join();

            List<RecoveryResult> results = CrashRecoveryScanner.scanAtStartup(ledger, RecoveryHandlers.none())
                    .toCompletableFuture().join();

            assertEquals(1, results.size());
            assertEquals(LedgerState.RecoveryClassification.FAIL_UNCHARGED, results.get(0).classification());
            assertEquals("FAILED", results.get(0).resultingState());
            assertEquals("FAILED", state(store, payload.operationId()));
        }
    }

    @Test
    void paymentPendingIsCommittedBeforeDomainTransaction() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload payload = payload();
            ledger.createPaymentPending(payload).toCompletableFuture().join();

            assertEquals("PAYMENT_PENDING", state(store, payload.operationId()));
            assertEquals(0, count(store, "lands"));
            assertEquals(0, count(store, "land_chunks"));
        }
    }

    @Test
    void payloadJsonRoundTripPreservesLongAmountsBeyondDoublePrecision() {
        for (long amount : new long[] { 9007199254740993L, Long.MAX_VALUE }) {
            OperationPayload payload = payload(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"), amount, amount);

            OperationPayload decoded = OperationPayload.fromJson(payload.toJson());

            assertEquals(amount, decoded.priceMinorUnits());
            assertEquals(amount, decoded.chunkSet().get(0).costBasisMinorUnits());
            assertEquals(payload, decoded);
        }
    }

    @Test
    void exactLongRejectsFractionalNumberNearLongUpperBound() throws Exception {
        Method exactLong = OperationPayload.class.getDeclaredMethod("exactLong", Number.class, String.class);
        exactLong.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> exactLong.invoke(null, new BigDecimal("9223372036854775807.5"), "amount"));

        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void payloadJsonRejectsFractionalOverflowAndMalformedNumbers() {
        String json = payload().toJson();

        assertThrows(IllegalArgumentException.class,
                () -> OperationPayload.fromJson(json.replace("\"priceMinorUnits\":417", "\"priceMinorUnits\":417.5")));
        assertThrows(IllegalArgumentException.class,
                () -> OperationPayload.fromJson(json.replace("\"priceMinorUnits\":417", "\"priceMinorUnits\":9223372036854775808")));
        assertThrows(IllegalArgumentException.class,
                () -> OperationPayload.fromJson(json + "malformed"));
    }

    @Test
    void atomicCommitPreservesExactCostBasisFromSavedPayload() {
        long costBasis = 9007199254740993L;
        OperationPayload payload = payload(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                Instant.parse("2026-01-01T00:00:00Z"), 417L, costBasis);

        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationLedger ledger = chargedLedger(store, payload);
            ledger.commitClaimAtomically(commit(payload)).toCompletableFuture().join();

            long savedBasis = store.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT cost_basis_minor_units FROM land_chunks")) {
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        return rows.getLong(1);
                    }
                }
            });

            assertEquals(costBasis, savedBasis);
        }
    }

    @Test
    void atomicCommitWritesAllDomainRowsAndSavedCostBasis() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationPayload payload = payload();
            OperationLedger ledger = chargedLedger(store, payload);
            ledger.commitClaimAtomically(commit(payload)).toCompletableFuture().join();

            assertEquals("DOMAIN_COMMITTED", state(store, payload.operationId()));
            assertEquals(1, count(store, "lands"));
            assertEquals(1, count(store, "land_chunks"));
            assertEquals(1, count(store, "audit_log"));
            assertEquals(1, count(store, "audit_chunks"));
            long savedBasis = store.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT cost_basis_minor_units FROM land_chunks")) {
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        return rows.getLong(1);
                    }
                }
            });
            assertEquals(417L, savedBasis);
        }
    }

    @Test
    void everyAtomicFailureInjectionRollsBackAllWritesAndLedgerState() {
        for (AtomicCommitStep failureStep : EnumSet.allOf(AtomicCommitStep.class)) {
            try (PersistenceStore store = PersistenceStore.open(database())) {
                OperationPayload payload = payload();
                OperationLedger ledger = chargedLedger(store, payload);
                OperationLedger failing = new OperationLedger(store, step -> {
                    if (step == failureStep) throw new IllegalStateException("injected failure");
                });
                assertThrows(RuntimeException.class,
                        () -> failing.commitClaimAtomically(commit(payload)).toCompletableFuture().join());
                assertEquals("CHARGED", state(store, payload.operationId()));
                assertEquals(0, count(store, "lands"));
                assertEquals(0, count(store, "land_chunks"));
                assertEquals(0, count(store, "audit_log"));
                assertEquals(0, count(store, "audit_chunks"));
                assertNotNull(ledger);
            }
        }
    }

    @Test
    void recoveryCoversLookupReplayRebuildCompensationAndTerminalPaths() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            Instant time = Instant.parse("2026-01-01T00:00:00Z");
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload created = payload(UUID.fromString("00000000-0000-0000-0000-000000000101"), time);
            OperationPayload unpaid = payload(UUID.fromString("00000000-0000-0000-0000-000000000102"), time.plusSeconds(1));
            OperationPayload unknown = payload(UUID.fromString("00000000-0000-0000-0000-000000000103"), time.plusSeconds(2));
            OperationPayload paid = payload(UUID.fromString("00000000-0000-0000-0000-000000000104"), time.plusSeconds(3));
            OperationPayload charged = payload(UUID.fromString("00000000-0000-0000-0000-000000000105"), time.plusSeconds(4));
            OperationPayload domain = payload(UUID.fromString("00000000-0000-0000-0000-000000000106"), time.plusSeconds(5));
            OperationPayload compensation = payload(UUID.fromString("00000000-0000-0000-0000-000000000107"), time.plusSeconds(6));
            OperationPayload active = payload(UUID.fromString("00000000-0000-0000-0000-000000000108"), time.plusSeconds(7));
            OperationPayload failed = payload(UUID.fromString("00000000-0000-0000-0000-000000000109"), time.plusSeconds(8));
            OperationPayload compensated = payload(UUID.fromString("00000000-0000-0000-0000-000000000110"), time.plusSeconds(9));
            OperationPayload needs = payload(UUID.fromString("00000000-0000-0000-0000-000000000111"), time.plusSeconds(10));
            ledger.insert(LedgerEntry.fromPayload(created, LedgerState.CREATED)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(unpaid, LedgerState.PAYMENT_PENDING)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(unknown, LedgerState.PAYMENT_PENDING)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(paid, LedgerState.PAYMENT_PENDING)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(charged, LedgerState.CHARGED, "paid-charged", time)).toCompletableFuture().join();
            ledger.createPaymentPending(domain).toCompletableFuture().join();
            ledger.transitionToCharged(domain.operationId(), "domain-payment", domain.updatedAt())
                    .toCompletableFuture().join();
            ledger.commitClaimAtomically(commit(domain)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(compensation, LedgerState.COMPENSATION_PENDING, "paid-comp", time)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(active, LedgerState.ACTIVE)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(failed, LedgerState.FAILED)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(compensated, LedgerState.COMPENSATED, "paid-done", time)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(needs, LedgerState.NEEDS_RECONCILIATION)).toCompletableFuture().join();

            AtomicInteger lookupCalls = new AtomicInteger();
            AtomicInteger refundCalls = new AtomicInteger();
            AtomicBoolean allExternalCallsSawAutocommit = new AtomicBoolean(true);
            RecoveryHandlers handlers = RecoveryHandlers.of(
                    entry -> {
                        allExternalCallsSawAutocommit.set(allExternalCallsSawAutocommit.get() && autoCommit(store));
                        lookupCalls.incrementAndGet();
                        if (entry.operationId().equals(unpaid.operationId())) return completed(PaymentLookup.unpaid());
                        if (entry.operationId().equals(paid.operationId())) return completed(PaymentLookup.paid("paid-ref"));
                        return completed(PaymentLookup.unknown());
                    },
                    entry -> {
                        allExternalCallsSawAutocommit.set(allExternalCallsSawAutocommit.get() && autoCommit(store));
                        refundCalls.incrementAndGet();
                        return completed(RefundOutcome.REFUNDED);
                    },
                    (entry, ignored) -> {
                        allExternalCallsSawAutocommit.set(allExternalCallsSawAutocommit.get() && autoCommit(store));
                        if (entry.operationId().equals(paid.operationId())) return completed(commit(paid));
                        return CompletableFuture.failedFuture(new IllegalStateException("invalid domain constraints"));
                    },
                    (entry, ignored) -> {
                        allExternalCallsSawAutocommit.set(allExternalCallsSawAutocommit.get() && autoCommit(store));
                        return completed(null);
                    });

            List<RecoveryResult> results = new CrashRecoveryScanner(
                    ledger, handlers, Clock.fixed(time.plusSeconds(30), ZoneOffset.UTC), 3)
                    .scan().toCompletableFuture().join();

            assertEquals("FAILED", state(store, created.operationId()));
            assertEquals("FAILED", state(store, unpaid.operationId()));
            assertEquals("NEEDS_RECONCILIATION", state(store, unknown.operationId()));
            assertEquals("ACTIVE", state(store, paid.operationId()));
            assertEquals("COMPENSATED", state(store, charged.operationId()));
            assertEquals("ACTIVE", state(store, domain.operationId()));
            assertEquals("COMPENSATED", state(store, compensation.operationId()));
            assertEquals("ACTIVE", state(store, active.operationId()));
            assertEquals("FAILED", state(store, failed.operationId()));
            assertEquals("COMPENSATED", state(store, compensated.operationId()));
            assertEquals("NEEDS_RECONCILIATION", state(store, needs.operationId()));
            assertEquals(11, results.size());
            assertEquals(3, lookupCalls.get());
            assertEquals(2, refundCalls.get());
            assertTrue(allExternalCallsSawAutocommit.get());
        }
    }

    @Test
    void recoveryOrderingIsDeterministicAndSecondScanDoesNotReprocessTerminals() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationLedger ledger = new OperationLedger(store);
            Instant sameTime = Instant.parse("2026-01-01T00:00:00Z");
            OperationPayload first = payload(UUID.fromString("00000000-0000-0000-0000-000000000201"), sameTime);
            OperationPayload second = payload(UUID.fromString("00000000-0000-0000-0000-000000000202"), sameTime);
            ledger.insert(LedgerEntry.fromPayload(second, LedgerState.CREATED)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(first, LedgerState.CREATED)).toCompletableFuture().join();
            AtomicInteger calls = new AtomicInteger();
            CrashRecoveryScanner scanner = new CrashRecoveryScanner(ledger, RecoveryHandlers.of(
                    entry -> completed(PaymentLookup.unknown()),
                    entry -> completed(RefundOutcome.UNKNOWN),
                    (entry, payload) -> completed(null),
                    (entry, payload) -> completed(null)));
            List<RecoveryResult> firstResults = scanner.scan().toCompletableFuture().join();
            List<RecoveryResult> secondResults = scanner.scan().toCompletableFuture().join();
            assertEquals(List.of(first.operationId(), second.operationId()),
                    firstResults.stream().map(RecoveryResult::operationId).toList());
            assertEquals(List.of("FAILED", "FAILED"),
                    firstResults.stream().map(RecoveryResult::resultingState).toList());
            assertEquals(firstResults.stream().map(RecoveryResult::resultingState).toList(),
                    secondResults.stream().map(RecoveryResult::resultingState).toList());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void malformedAndOrphanedRecordsRemainRetainedAndNeverBecomeSuccess() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationLedger ledger = new OperationLedger(store);
            UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000301");
            UUID malformedId = UUID.fromString("00000000-0000-0000-0000-000000000302");
            UUID blankStateId = UUID.fromString("00000000-0000-0000-0000-000000000303");
            UUID orphanId = UUID.fromString("00000000-0000-0000-0000-000000000304");
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            new SqliteLedgerRepository(store).insert(new LedgerEntry(unknownId, "CLAIM", "NOT_A_STATE", null,
                    null, null, null, "provider", null, "{}", 1, now, now)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(payload(malformedId, now), LedgerState.CHARGED,
                    "orphan-payment", now)).toCompletableFuture().join();
            ledger.insert(LedgerEntry.fromPayload(payload(orphanId, now), LedgerState.DOMAIN_COMMITTED))
                    .toCompletableFuture().join();
            store.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO operation_ledger (operation_id, operation_type, state, metadata_schema_version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    statement.setBytes(1, UuidBlob.encode(blankStateId));
                    statement.setString(2, "CLAIM");
                    statement.setString(3, "");
                    statement.setInt(4, 1);
                    statement.setLong(5, now.toEpochMilli());
                    statement.setLong(6, now.toEpochMilli());
                    statement.executeUpdate();
                }
                return null;
            });
            store.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE operation_ledger SET payload_json = ? WHERE operation_id = ?")) {
                    statement.setString(1, "{\"operationId\":null}");
                    statement.setBytes(2, UuidBlob.encode(malformedId));
                    statement.executeUpdate();
                }
                return null;
            });
            List<RecoveryResult> results = new CrashRecoveryScanner(ledger, RecoveryHandlers.none()).scan()
                    .toCompletableFuture().join();
            assertEquals("NOT_A_STATE", state(store, unknownId));
            assertEquals("NEEDS_RECONCILIATION", state(store, malformedId));
            assertEquals("", state(store, blankStateId));
            assertEquals("NEEDS_RECONCILIATION", state(store, orphanId));
            assertEquals(4, count(store, "operation_ledger"));
            assertTrue(results.stream().anyMatch(result -> result.classification()
                    == LedgerState.RecoveryClassification.INVALID_RECORD));
            assertFalse(results.stream().anyMatch(result -> "ACTIVE".equals(result.resultingState())));
        }
    }

    @Test
    void failedRefundReachesReconciliationAtDurableRetryLimit() {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            OperationPayload payload = payload();
            OperationLedger ledger = new OperationLedger(store);
            ledger.insert(LedgerEntry.fromPayload(payload, LedgerState.COMPENSATION_PENDING,
                    "refund-me", payload.updatedAt())).toCompletableFuture().join();
            AtomicInteger refunds = new AtomicInteger();
            CrashRecoveryScanner scanner = new CrashRecoveryScanner(ledger, RecoveryHandlers.of(
                    entry -> completed(PaymentLookup.unknown()),
                    entry -> {
                        refunds.incrementAndGet();
                        return completed(RefundOutcome.FAILED);
                    },
                    (entry, ignored) -> completed(null),
                    (entry, ignored) -> completed(null)),
                    Clock.fixed(payload.updatedAt(), ZoneOffset.UTC), 2);
            assertEquals("COMPENSATION_PENDING", scanner.scan().toCompletableFuture().join().get(0).resultingState());
            assertEquals("NEEDS_RECONCILIATION", scanner.scan().toCompletableFuture().join().get(0).resultingState());
            assertEquals(2, refunds.get());
            assertEquals(2, ledger.find(payload.operationId()).toCompletableFuture().join().compensationAttempts());
        }
    }

    private OperationLedger chargedLedger(PersistenceStore store, OperationPayload payload) {
        OperationLedger ledger = new OperationLedger(store);
        ledger.createPaymentPending(payload).toCompletableFuture().join();
        ledger.transitionToCharged(payload.operationId(), "payment-ref", payload.updatedAt())
                .toCompletableFuture().join();
        return ledger;
    }

    private ClaimCommit commit(OperationPayload payload) {
        ChunkKey chunk = payload.chunkSet().get(0).chunk();
        LandId landId = payload.targetLandId();
        LandSnapshot land = new LandSnapshot(landId, payload.landDisplayName(),
                LandName.normalize(payload.landDisplayName()), OwnerRef.player(payload.actorUuid()),
                payload.worldUuid(), Set.of(chunk), List.of(), 0, 0,
                payload.createdAt(), payload.updatedAt());
        AuditEntry audit = new AuditEntry(0, payload.updatedAt(), payload.actorUuid(), "CLAIM_COMMITTED",
                landId, payload.worldUuid(), null, payload.schemaVersion(), null, payload.toJson(), "{}", List.of(chunk));
        return new ClaimCommit(payload.operationId(), land, payload.chunkSet(), audit);
    }

    private OperationPayload payload() {
        return payload(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private OperationPayload payload(UUID operationId, Instant time) {
        return payload(operationId, time, 417L, 417L);
    }

    private OperationPayload payload(UUID operationId, Instant time, long priceMinorUnits, long costBasisMinorUnits) {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000012");
        String displayName = "Recovered land " + operationId.toString().substring(28);
        return OperationPayload.claim(operationId, actor, world,
                new LandId(UUID.nameUUIDFromBytes((operationId + "-land").getBytes())),
                List.of(new OperationPayload.Chunk(new ChunkKey(world, (int) operationId.getLeastSignificantBits(), -3), 12,
                        UUID.nameUUIDFromBytes((operationId + "-lot").getBytes()), costBasisMinorUnits)),
                priceMinorUnits, "test-economy", time, displayName);
    }

    private Path database() {
        return temporaryDirectory.resolve(UUID.randomUUID() + ".db");
    }

    private static String state(PersistenceStore store, UUID operationId) {
        return store.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state FROM operation_ledger WHERE operation_id = ?")) {
                statement.setBytes(1, UuidBlob.encode(operationId));
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getString(1);
                }
            }
        });
    }

    private static int count(PersistenceStore store, String table) {
        return store.execute(connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        });
    }

    private static boolean autoCommit(PersistenceStore store) {
        return store.execute(connection -> connection.getAutoCommit());
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
