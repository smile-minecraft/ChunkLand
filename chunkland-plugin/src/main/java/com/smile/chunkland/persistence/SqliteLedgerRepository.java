package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class SqliteLedgerRepository implements LedgerRepository {

    private final PersistenceStore store;

    public SqliteLedgerRepository(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CompletionStage<Void> insert(LedgerEntry entry) {
        // This low-level repository preserves legacy string rows; typed callers use OperationLedger.insert.
        Objects.requireNonNull(entry, "entry");
        return store.submitAsync(conn -> SqlTransaction.run(conn, c -> {
            String sql = """
                    INSERT INTO operation_ledger (operation_id, operation_type, state, actor_uuid, world_uuid, target_land_id, price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, metadata_schema_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setBytes(1, UuidBlob.encode(entry.operationId()));
                ps.setString(2, entry.operationType());
                ps.setString(3, entry.state());
                ps.setBytes(4, entry.actor() == null ? null : UuidBlob.encode(entry.actor()));
                ps.setBytes(5, entry.worldId() == null ? null : UuidBlob.encode(entry.worldId()));
                ps.setBytes(6, entry.targetLandId() == null ? null : UuidBlob.encode(entry.targetLandId().value()));
                if (entry.priceMinorUnits() == null) ps.setObject(7, null); else ps.setLong(7, entry.priceMinorUnits());
                ps.setString(8, entry.economyProviderId());
                ps.setString(9, entry.economyTransactionRef());
                ps.setString(10, entry.payloadJson());
                ps.setInt(11, entry.metadataVersion());
                ps.setLong(12, entry.createdAt().toEpochMilli());
                ps.setLong(13, entry.updatedAt().toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        }));
    }

    @Override
    public CompletionStage<Optional<LedgerEntry>> findById(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return store.submitAsync(conn -> findInternal(conn, operationId));
    }

    @Override
    public CompletionStage<Void> updateState(UUID operationId, String newState, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(newState, "newState");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (LedgerState.tryParse(newState).isPresent()) {
            throw new IllegalArgumentException(
                    "compatibility state updates cannot write typed states; use compareAndSetState");
        }
        return store.submitAsync(conn -> SqlTransaction.run(conn, c -> {
            String sql = "UPDATE operation_ledger SET state = ?, updated_at = ? "
                    + "WHERE operation_id = ? AND state NOT IN ("
                    + "'CREATED', 'PAYMENT_PENDING', 'CHARGED', 'DOMAIN_COMMITTED', 'ACTIVE', "
                    + "'COMPENSATION_PENDING', 'COMPENSATED', 'FAILED', 'NEEDS_RECONCILIATION', "
                    + "'RESOLVED', 'REFUNDED', 'IGNORED')";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, newState);
                ps.setLong(2, updatedAt.toEpochMilli());
                ps.setBytes(3, UuidBlob.encode(operationId));
                int rows = ps.executeUpdate();
                if (rows != 1) throw new SQLException("ledger update affected " + rows + " rows");
            }
            return null;
        }));
    }

    @Override
    public CompletionStage<Void> compareAndSetState(
            UUID operationId, LedgerState expected, LedgerState next, Instant updatedAt) {
        validateTransition(operationId, expected, next, updatedAt);
        return store.submitAsync(conn -> SqlTransaction.run(conn, c -> {
            compareAndSetState(c, operationId, expected, next, null, updatedAt);
            return null;
        }));
    }

    @Override
    public CompletionStage<List<LedgerEntry>> findByState(String state) {
        Objects.requireNonNull(state, "state");
        return store.submitAsync(conn -> {
            String sql = "SELECT operation_id, operation_type, state, actor_uuid, world_uuid, target_land_id, price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, metadata_schema_version, created_at, updated_at, compensation_attempts FROM operation_ledger WHERE state = ? ORDER BY created_at, operation_id";
            List<LedgerEntry> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, state);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapRow(rs));
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public CompletionStage<List<LedgerEntry>> findAll() {
        return store.submitAsync(conn -> {
            String sql = "SELECT operation_id, operation_type, state, actor_uuid, world_uuid, target_land_id, price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, metadata_schema_version, created_at, updated_at, compensation_attempts FROM operation_ledger ORDER BY created_at, operation_id";
            List<LedgerEntry> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
            return List.copyOf(out);
        });
    }

    private Optional<LedgerEntry> findInternal(Connection conn, UUID operationId) throws SQLException {
        String sql = "SELECT operation_id, operation_type, state, actor_uuid, world_uuid, target_land_id, price_minor_units, economy_provider_id, economy_transaction_ref, payload_json, metadata_schema_version, created_at, updated_at, compensation_attempts FROM operation_ledger WHERE operation_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidBlob.encode(operationId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    static void compareAndSetState(
            Connection connection,
            UUID operationId,
            LedgerState expected,
            LedgerState next,
            String transactionRef,
            Instant updatedAt) throws SQLException {
        validateTransition(operationId, expected, next, updatedAt);
        String sql = transactionRef == null
                ? "UPDATE operation_ledger SET state = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND state = ?"
                : "UPDATE operation_ledger SET state = ?, economy_transaction_ref = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND state = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (transactionRef == null) {
                statement.setString(1, next.name());
                statement.setLong(2, updatedAt.toEpochMilli());
                statement.setBytes(3, UuidBlob.encode(operationId));
                statement.setString(4, expected.name());
            } else {
                statement.setString(1, next.name());
                statement.setString(2, transactionRef);
                statement.setLong(3, updatedAt.toEpochMilli());
                statement.setBytes(4, UuidBlob.encode(operationId));
                statement.setString(5, expected.name());
            }
            if (statement.executeUpdate() != 1) {
                throw new SQLException("expected ledger state " + expected + " was not found");
            }
        }
    }

    private static void validateTransition(
            UUID operationId, LedgerState expected, LedgerState next, Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("invalid ledger transition: " + expected + " -> " + next);
        }
    }

    private LedgerEntry mapRow(ResultSet rs) throws SQLException {
        UUID opId = UuidBlob.decode(rs.getBytes(1));
        String type = rs.getString(2);
        String state = rs.getString(3);
        byte[] actorB = rs.getBytes(4);
        UUID actor = actorB == null ? null : UuidBlob.decode(actorB);
        byte[] worldB = rs.getBytes(5);
        UUID world = worldB == null ? null : UuidBlob.decode(worldB);
        byte[] landB = rs.getBytes(6);
        LandId landId = landB == null ? null : new LandId(UuidBlob.decode(landB));
        Long price = rs.getObject(7) == null ? null : rs.getLong(7);
        String provider = rs.getString(8);
        String ref = rs.getString(9);
        String payload = rs.getString(10);
        int meta = rs.getInt(11);
        Instant created = Instant.ofEpochMilli(rs.getLong(12));
        Instant updated = Instant.ofEpochMilli(rs.getLong(13));
        int compensationAttempts = rs.getInt(14);
        return new LedgerEntry(opId, type, state, actor, world, landId, price, provider, ref, payload, meta, created, updated, compensationAttempts);
    }
}
