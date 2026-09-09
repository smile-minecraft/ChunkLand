package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Durable operation state machine and the all-or-nothing domain commit seam. */
public final class OperationLedger {

    private final PersistenceStore store;
    private final Consumer<AtomicCommitStep> failureInjector;

    public OperationLedger(PersistenceStore store) {
        this(store, ignored -> { });
    }

    public OperationLedger(PersistenceStore store, Consumer<AtomicCommitStep> failureInjector) {
        this.store = Objects.requireNonNull(store, "store");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    /** Inserts a new operation in its own committed transaction. */
    public CompletionStage<Void> insert(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        LedgerState.parse(entry.state());
        if (entry.payloadJson() == null || entry.payloadJson().isBlank()) {
            throw new IllegalArgumentException("operation payload must not be blank");
        }
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            insertRow(c, entry);
            return null;
        }));
    }

    public CompletionStage<Void> create(OperationPayload payload) {
        return insert(LedgerEntry.fromPayload(payload, LedgerState.CREATED));
    }

    /** The durable boundary that must precede Economy work. */
    public CompletionStage<Void> createPaymentPending(OperationPayload payload) {
        return insert(LedgerEntry.fromPayload(payload, LedgerState.PAYMENT_PENDING));
    }

    /** Atomically changes a typed state only when the expected state still matches. */
    public CompletionStage<Void> compareAndSetState(
            UUID operationId, LedgerState expected, LedgerState next, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("invalid ledger transition: " + expected + " -> " + next);
        }
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            SqliteLedgerRepository.compareAndSetState(c, operationId, expected, next, null, updatedAt);
            return null;
        }));
    }

    /** Compatibility name for callers that already use the typed transition facade. */
    public CompletionStage<Void> transition(UUID operationId, LedgerState expected, LedgerState next, Instant updatedAt) {
        return compareAndSetState(operationId, expected, next, updatedAt);
    }

    /**
     * Parks a domain-committed refund for compensation with its Economy
     * idempotency reference in a single transaction.
     *
     * <p>The reference lets startup recovery retry the deposit through the
     * shared compensation path, which requires a recorded transaction
     * reference before it will call Economy.
     */
    public CompletionStage<Void> parkForRefundCompensation(
            UUID operationId, String transactionRef, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(transactionRef, "transactionRef");
        if (transactionRef.isBlank()) throw new IllegalArgumentException("transactionRef must not be blank");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            SqliteLedgerRepository.compareAndSetState(
                    c, operationId, LedgerState.DOMAIN_COMMITTED,
                    LedgerState.COMPENSATION_PENDING, transactionRef, updatedAt);
            return null;
        }));
    }

    public CompletionStage<Void> transitionToCharged(UUID operationId, String transactionRef, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(transactionRef, "transactionRef");
        if (transactionRef.isBlank()) throw new IllegalArgumentException("transactionRef must not be blank");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            SqliteLedgerRepository.compareAndSetState(
                    c, operationId, LedgerState.PAYMENT_PENDING, LedgerState.CHARGED, transactionRef, updatedAt);
            return null;
        }));
    }

    public CompletionStage<LedgerEntry> find(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return store.submitAsync(connection -> findRow(connection, operationId));
    }

    public CompletionStage<Optional<LedgerEntry>> findById(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return store.submitAsync(connection -> findOptionalRow(connection, operationId));
    }

    public CompletionStage<List<LedgerEntry>> findAll() {
        return store.submitAsync(connection -> {
            java.util.ArrayList<LedgerEntry> entries = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT operation_id, operation_type, state, actor_uuid, world_uuid, target_land_id, "
                            + "price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, "
                            + "metadata_schema_version, created_at, updated_at, compensation_attempts "
                            + "FROM operation_ledger ORDER BY created_at, operation_id")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) entries.add(mapRow(rows));
                }
            }
            return List.copyOf(entries);
        });
    }

    public CompletionStage<Boolean> landExists(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return store.submitAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM lands WHERE id = ?")) {
                statement.setBytes(1, UuidBlob.encode(landId.value()));
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next();
                }
            }
        });
    }

    /** Fail-closed escape used when a record cannot safely follow its normal path. */
    public CompletionStage<Void> quarantine(UUID operationId, LedgerState expected, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expected.isTerminal()) {
            throw new IllegalArgumentException("terminal state cannot be quarantined: " + expected);
        }
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            SqliteLedgerRepository.compareAndSetState(
                    c, operationId, expected, LedgerState.NEEDS_RECONCILIATION, null, updatedAt);
            return null;
        }));
    }

    /** Increments the durable compensation retry count and applies the retry limit. */
    public CompletionStage<CompensationDecision> recordCompensationFailure(
            UUID operationId, int retryLimit, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (retryLimit < 1) throw new IllegalArgumentException("retryLimit must be positive");
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            int attempts;
            try (PreparedStatement statement = c.prepareStatement(
                    "UPDATE operation_ledger SET compensation_attempts = compensation_attempts + 1, updated_at = ? "
                            + "WHERE operation_id = ? AND state = ?")) {
                statement.setLong(1, updatedAt.toEpochMilli());
                statement.setBytes(2, UuidBlob.encode(operationId));
                statement.setString(3, LedgerState.COMPENSATION_PENDING.name());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("compensation retry requires COMPENSATION_PENDING operation");
                }
            }
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT compensation_attempts FROM operation_ledger WHERE operation_id = ?")) {
                statement.setBytes(1, UuidBlob.encode(operationId));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("operation disappeared during compensation retry");
                    attempts = rows.getInt(1);
                }
            }
            LedgerState resultingState = LedgerState.COMPENSATION_PENDING;
            if (attempts >= retryLimit) {
                SqliteLedgerRepository.compareAndSetState(
                        c, operationId, LedgerState.COMPENSATION_PENDING,
                        LedgerState.NEEDS_RECONCILIATION, null, updatedAt);
                resultingState = LedgerState.NEEDS_RECONCILIATION;
            }
            return new CompensationDecision(attempts, resultingState);
        }));
    }

    /**
     * Settles a refund row as compensated from its post-commit state with the
     * Economy idempotency reference in a single transaction.
     *
     * <p>Accepts both {@code DOMAIN_COMMITTED} (first confirmed deposit) and
     * {@code COMPENSATION_PENDING} (confirmed retry) as the expected state, so
     * a retry never has to guess which post-commit state the row is in.
     */
    public CompletionStage<Void> settleRefundCompensated(
            UUID operationId, LedgerState expected, String transactionRef, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(transactionRef, "transactionRef");
        if (transactionRef.isBlank()) throw new IllegalArgumentException("transactionRef must not be blank");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expected != LedgerState.DOMAIN_COMMITTED && expected != LedgerState.COMPENSATION_PENDING) {
            throw new IllegalArgumentException("refund settlement requires a post-commit state, got " + expected);
        }
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            SqliteLedgerRepository.compareAndSetState(
                    c, operationId, expected, LedgerState.COMPENSATED, transactionRef, updatedAt);
            return null;
        }));
    }

    /**
     * Writes domain truth, its audit trail, and DOMAIN_COMMITTED in one SQL
     * transaction. No repository callback or external collaborator is called.
     */
    public CompletionStage<Void> commitClaimAtomically(ClaimCommit commit) {
        Objects.requireNonNull(commit, "commit");
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            LedgerEntry charged = findRow(c, commit.operationId());
            if (!LedgerState.CHARGED.name().equals(charged.state())) {
                throw new SQLException("atomic claim commit requires CHARGED state, got " + charged.state());
            }
            validateCommitAgainstPayload(charged, commit);
            insertLand(c, commit.land());
            failureInjector.accept(AtomicCommitStep.AFTER_LAND);
            insertChunks(c, commit.land(), commit.chunks());
            failureInjector.accept(AtomicCommitStep.AFTER_CHUNKS);
            insertAudit(c, commit.audit());
            failureInjector.accept(AtomicCommitStep.AFTER_AUDIT);
            SqliteLedgerRepository.compareAndSetState(
                    c, commit.operationId(), LedgerState.CHARGED, LedgerState.DOMAIN_COMMITTED,
                    null, commit.audit().timestamp());
            failureInjector.accept(AtomicCommitStep.AFTER_LEDGER);
            return null;
        }));
    }

    public CompletionStage<Void> commitClaimAtomically(
            UUID operationId, LandSnapshot land, List<OperationPayload.Chunk> chunks, AuditEntry audit) {
        return commitClaimAtomically(new ClaimCommit(operationId, land, chunks, audit));
    }

    /**
     * Removes the refunded chunks, writes the refund audit trail, deletes the
     * land row when no chunk remains, and marks {@code DOMAIN_COMMITTED} in
     * one SQL transaction. No repository callback or external collaborator is
     * called; Economy work always happens after this transaction completes.
     *
     * <p>Each chunk row is re-read inside the transaction: a missing chunk, a
     * chunk owned by another land, or a stored cost basis that no longer
     * matches the validated value aborts the whole transaction, so stale or
     * already-refunded requests fail closed without moving money.
     */
    public CompletionStage<Void> commitRefundAtomically(RefundCommit commit) {
        Objects.requireNonNull(commit, "commit");
        return store.submitAsync(connection -> SqlTransaction.run(connection, c -> {
            LedgerEntry created = findRow(c, commit.operationId());
            if (!LedgerState.CREATED.name().equals(created.state())) {
                throw new SQLException("atomic refund commit requires CREATED state, got " + created.state());
            }
            validateRefundAgainstPayload(created, commit);
            verifyRefundChunks(c, commit);
            deleteRefundChunks(c, commit);
            failureInjector.accept(AtomicCommitStep.AFTER_CHUNKS);
            insertAudit(c, commit.audit());
            failureInjector.accept(AtomicCommitStep.AFTER_AUDIT);
            deleteLandWhenEmpty(c, commit.landId());
            failureInjector.accept(AtomicCommitStep.AFTER_LAND);
            SqliteLedgerRepository.compareAndSetState(
                    c, commit.operationId(), LedgerState.CREATED, LedgerState.DOMAIN_COMMITTED,
                    null, commit.audit().timestamp());
            failureInjector.accept(AtomicCommitStep.AFTER_LEDGER);
            return null;
        }));
    }

    private static void insertRow(Connection connection, LedgerEntry entry) throws SQLException {
        String sql = "INSERT INTO operation_ledger (operation_id, operation_type, state, actor_uuid, world_uuid, "
                + "target_land_id, price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, "
                + "metadata_schema_version, created_at, updated_at, compensation_attempts) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBlob.encode(entry.operationId()));
            statement.setString(2, entry.operationType());
            statement.setString(3, entry.state());
            setUuid(statement, 4, entry.actor());
            setUuid(statement, 5, entry.worldId());
            setUuid(statement, 6, entry.targetLandId() == null ? null : entry.targetLandId().value());
            if (entry.priceMinorUnits() == null) statement.setObject(7, null);
            else statement.setLong(7, entry.priceMinorUnits());
            statement.setString(8, entry.economyProviderId());
            statement.setString(9, entry.economyTransactionRef());
            statement.setString(10, entry.payloadJson());
            statement.setInt(11, entry.metadataVersion());
            statement.setLong(12, entry.createdAt().toEpochMilli());
            statement.setLong(13, entry.updatedAt().toEpochMilli());
            statement.setInt(14, entry.compensationAttempts());
            statement.executeUpdate();
        }
    }

    private static LedgerEntry findRow(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operation_id, operation_type, state, actor_uuid, world_uuid, target_land_id, "
                        + "price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, "
                        + "metadata_schema_version, created_at, updated_at, compensation_attempts "
                        + "FROM operation_ledger WHERE operation_id = ?")) {
            statement.setBytes(1, UuidBlob.encode(operationId));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("unknown operation: " + operationId);
                return mapRow(rows);
            }
        }
    }

    private static Optional<LedgerEntry> findOptionalRow(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operation_id, operation_type, state, actor_uuid, world_uuid, target_land_id, "
                        + "price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, "
                        + "metadata_schema_version, created_at, updated_at, compensation_attempts "
                        + "FROM operation_ledger WHERE operation_id = ?")) {
            statement.setBytes(1, UuidBlob.encode(operationId));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapRow(rows)) : Optional.empty();
            }
        }
    }

    private static LedgerEntry mapRow(ResultSet rows) throws SQLException {
        byte[] actorBytes = rows.getBytes(4);
        byte[] worldBytes = rows.getBytes(5);
        byte[] landBytes = rows.getBytes(6);
        Object priceValue = rows.getObject(7);
        return new LedgerEntry(
                UuidBlob.decode(rows.getBytes(1)),
                rows.getString(2),
                rows.getString(3),
                actorBytes == null ? null : UuidBlob.decode(actorBytes),
                worldBytes == null ? null : UuidBlob.decode(worldBytes),
                landBytes == null ? null : new com.smile.chunkland.api.land.LandId(UuidBlob.decode(landBytes)),
                priceValue == null ? null : rows.getLong(7),
                rows.getString(8),
                rows.getString(9),
                rows.getString(10),
                rows.getInt(11),
                Instant.ofEpochMilli(rows.getLong(12)),
                Instant.ofEpochMilli(rows.getLong(13)),
                rows.getInt(14));
    }

    private static void validateCommitAgainstPayload(LedgerEntry charged, ClaimCommit commit) throws SQLException {        final OperationPayload payload;
        try {
            payload = OperationPayload.fromJson(charged.payloadJson());
        } catch (RuntimeException failure) {
            throw new SQLException("charged operation has an invalid payload", failure);
        }
        if (!payload.operationId().equals(commit.operationId())) {
            throw new SQLException("payload operationId does not match ledger row");
        }
        if (payload.targetLandId() != null && !payload.targetLandId().equals(commit.land().id())) {
            throw new SQLException("committed land does not match payload targetLandId");
        }
        if (!payload.worldUuid().equals(commit.land().worldId())) {
            throw new SQLException("committed land does not match payload worldUuid");
        }
        if (!payload.chunkSet().equals(commit.chunks())) {
            throw new SQLException("committed chunks must use the saved operation payload");
        }
    }

    private static void validateRefundAgainstPayload(LedgerEntry created, RefundCommit commit) throws SQLException {
        final OperationPayload payload;
        try {
            payload = OperationPayload.fromJson(created.payloadJson());
        } catch (RuntimeException failure) {
            throw new SQLException("refund operation has an invalid payload", failure);
        }
        if (!"REFUND".equals(payload.operationType())) {
            throw new SQLException("atomic refund commit requires a REFUND payload, got " + payload.operationType());
        }
        if (!payload.operationId().equals(commit.operationId())) {
            throw new SQLException("payload operationId does not match ledger row");
        }
        if (payload.targetLandId() != null && !payload.targetLandId().equals(commit.landId())) {
            throw new SQLException("refunded land does not match payload targetLandId");
        }
        if (!payload.worldUuid().equals(commit.worldId())) {
            throw new SQLException("refunded land does not match payload worldUuid");
        }
        if (!payload.chunkSet().equals(commit.chunks())) {
            throw new SQLException("refunded chunks must use the saved operation payload");
        }
        if (payload.priceMinorUnits() != commit.refundAmountMinorUnits()) {
            throw new SQLException("refund amount does not match payload priceMinorUnits");
        }
    }

    private static void verifyRefundChunks(Connection connection, RefundCommit commit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT land_id, cost_basis_minor_units FROM land_chunks "
                        + "WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?")) {
            for (OperationPayload.Chunk chunk : commit.chunks()) {
                statement.setBytes(1, UuidBlob.encode(chunk.chunk().worldId()));
                statement.setInt(2, chunk.chunk().chunkX());
                statement.setInt(3, chunk.chunk().chunkZ());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("refund chunk is already released or unknown: "
                                + chunk.chunk().chunkX() + "," + chunk.chunk().chunkZ());
                    }
                    byte[] landBytes = rows.getBytes(1);
                    if (landBytes == null || !commit.landId().value().equals(UuidBlob.decode(landBytes))) {
                        throw new SQLException("refund chunk does not belong to the refunded land");
                    }
                    Object basisValue = rows.getObject(2);
                    if (basisValue == null) {
                        throw new SQLException("refund chunk has no durable cost basis");
                    }
                    long storedBasis = rows.getLong(2);
                    if (storedBasis < 0) {
                        throw new SQLException("refund chunk has a negative durable cost basis");
                    }
                    if (storedBasis != chunk.costBasisMinorUnits()) {
                        throw new SQLException("refund chunk cost basis changed since validation");
                    }
                }
            }
        }
    }

    private static void deleteRefundChunks(Connection connection, RefundCommit commit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM land_chunks WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?")) {
            for (OperationPayload.Chunk chunk : commit.chunks()) {
                statement.setBytes(1, UuidBlob.encode(chunk.chunk().worldId()));
                statement.setInt(2, chunk.chunk().chunkX());
                statement.setInt(3, chunk.chunk().chunkZ());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("refund chunk disappeared during commit");
                }
            }
        }
    }

    private static void deleteLandWhenEmpty(Connection connection, LandId landId) throws SQLException {
        try (PreparedStatement remaining = connection.prepareStatement(
                "SELECT 1 FROM land_chunks WHERE land_id = ? LIMIT 1")) {
            remaining.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rows = remaining.executeQuery()) {
                if (rows.next()) {
                    return;
                }
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM lands WHERE id = ?")) {
            delete.setBytes(1, UuidBlob.encode(landId.value()));
            delete.executeUpdate();
        }
    }

    private static void insertLand(Connection connection, LandSnapshot land) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lands (id, owner_key, display_name, name_key, world_uuid, structure_revision, "
                        + "land_policy_revision, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setBytes(1, UuidBlob.encode(land.id().value()));
            statement.setString(2, land.ownerRef().key());
            statement.setString(3, land.displayName());
            statement.setString(4, land.nameKey());
            statement.setBytes(5, UuidBlob.encode(land.worldId()));
            statement.setLong(6, land.structureRevision());
            statement.setLong(7, land.landPolicyRevision());
            statement.setLong(8, land.createdAt().toEpochMilli());
            statement.setLong(9, land.updatedAt().toEpochMilli());
            statement.executeUpdate();
        }
        for (SubLandSnapshot subLand : land.subLands()) {
            Cuboid cuboid = subLand.cuboid();
            if (cuboid == null) throw new SQLException("atomic commit cannot persist legacy subland geometry");
            UUID world = subLand.chunks().stream().findFirst().map(chunk -> chunk.worldId()).orElse(null);
            if (world == null) throw new SQLException("subland must contain a world-bearing chunk");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sublands (id, land_id, name, min_x, min_y, min_z, max_x, max_y, max_z, world_uuid) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setBytes(1, UuidBlob.encode(subLand.id().value()));
                statement.setBytes(2, UuidBlob.encode(subLand.parentLandId().value()));
                statement.setString(3, subLand.name());
                statement.setInt(4, cuboid.minX());
                statement.setInt(5, cuboid.minY());
                statement.setInt(6, cuboid.minZ());
                statement.setInt(7, cuboid.maxX());
                statement.setInt(8, cuboid.maxY());
                statement.setInt(9, cuboid.maxZ());
                statement.setBytes(10, UuidBlob.encode(world));
                statement.executeUpdate();
            }
        }
    }

    private static void insertChunks(
            Connection connection, LandSnapshot land, List<OperationPayload.Chunk> chunks) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO land_chunks (world, chunk, owner_key, owner_uuid, claimed_at, land_id, world_uuid, "
                        + "chunk_x, chunk_z, stored_min_protected_y, claim_lot_id, cost_basis_minor_units) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (OperationPayload.Chunk payloadChunk : chunks) {
                statement.setString(1, payloadChunk.chunk().worldId().toString());
                statement.setString(2, payloadChunk.chunk().chunkX() + "," + payloadChunk.chunk().chunkZ());
                statement.setString(3, land.ownerRef().key());
                setUuid(statement, 4, ownerUuid(land.ownerRef()));
                statement.setLong(5, land.createdAt().toEpochMilli());
                statement.setBytes(6, UuidBlob.encode(land.id().value()));
                statement.setBytes(7, UuidBlob.encode(payloadChunk.chunk().worldId()));
                statement.setInt(8, payloadChunk.chunk().chunkX());
                statement.setInt(9, payloadChunk.chunk().chunkZ());
                statement.setInt(10, payloadChunk.storedMinProtectedY());
                statement.setBytes(11, UuidBlob.encode(payloadChunk.claimLotId()));
                statement.setLong(12, payloadChunk.costBasisMinorUnits());
                statement.executeUpdate();
            }
        }
    }

    private static void insertAudit(Connection connection, AuditEntry audit) throws SQLException {
        long auditId;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_log (timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, "
                        + "metadata_schema_version, before_json, after_json, metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, audit.timestamp().toEpochMilli());
            setUuid(statement, 2, audit.actor());
            statement.setString(3, audit.action());
            setUuid(statement, 4, audit.landId() == null ? null : audit.landId().value());
            setUuid(statement, 5, audit.worldId());
            if (audit.singleChunkPacked() == null) statement.setObject(6, null);
            else statement.setLong(6, audit.singleChunkPacked());
            statement.setInt(7, audit.metadataVersion());
            statement.setString(8, audit.beforeJson());
            statement.setString(9, audit.afterJson());
            statement.setString(10, audit.metadataJson());
            if (statement.executeUpdate() != 1) throw new SQLException("audit insert affected no rows");
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("audit insert did not return an id");
                auditId = keys.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_chunks (audit_id, world_uuid, chunk_x, chunk_z) VALUES (?, ?, ?, ?)")) {
            for (var chunk : audit.chunks()) {
                statement.setLong(1, auditId);
                statement.setBytes(2, UuidBlob.encode(chunk.worldId()));
                statement.setInt(3, chunk.chunkX());
                statement.setInt(4, chunk.chunkZ());
                statement.addBatch();
            }
            if (!audit.chunks().isEmpty()) statement.executeBatch();
        }
    }

    private static UUID ownerUuid(OwnerRef owner) {
        return owner instanceof OwnerRef.PlayerOwnerRef player ? player.uuid() : null;
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) statement.setBytes(index, null);
        else statement.setBytes(index, UuidBlob.encode(value));
    }

    public record CompensationDecision(int attempts, LedgerState resultingState) {
        public CompensationDecision {
            if (attempts < 1) throw new IllegalArgumentException("attempts must be positive");
            Objects.requireNonNull(resultingState, "resultingState");
        }
    }
}
