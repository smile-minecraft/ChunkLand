package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.runtime.vertical.DepthExtendRequest;
import com.smile.chunkland.runtime.vertical.DepthStore;
import com.smile.chunkland.runtime.vertical.DepthWriteResult;
import com.smile.chunkland.runtime.vertical.VerticalDepths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Production {@link DepthStore}: atomic-minimum depth apply with
 * {@code DEPTH_EXTEND} audit.
 *
 * <p>One {@link #extend} call is one persistence-thread task running one
 * SQL transaction: the stored row moves through a single conditional
 * {@code UPDATE} that already enforces the owning land and the
 * strictly-deeper rule
 * ({@code COALESCE(stored_min_protected_y, fallback) > requested}), so the
 * compare-and-swap happens in SQL, not in Java. Exactly when that statement
 * affects one row does one audit row record
 * {@code before.storedMinProtectedY}, {@code after.storedMinProtectedY}
 * and {@code triggeringOperationY}. Shallower proposals, unknown chunks
 * and stale (land-mismatched) requests affect zero rows, persist nothing
 * and record no audit, so a trailing proposal can never overwrite a deeper
 * value or fabricate history — even if two writers ever run on different
 * threads, only the conditional-UPDATE winner audits.
 *
 * <p>Uses the existing {@link PersistenceStore} single-thread seam; no new
 * executor, no new transaction boundary, no Economy involvement. The
 * persistence thread serializes concurrent extends, and each extend
 * re-checks the durable value inside its own transaction, so the minimum
 * stays atomic end to end.
 */
public final class DepthExtendStore implements DepthStore {

    /** Schema version of the {@code DEPTH_EXTEND} before/after/metadata JSON. */
    public static final int METADATA_SCHEMA_VERSION = 1;

    private static final String ACTION = "DEPTH_EXTEND";

    private final PersistenceStore store;

    public DepthExtendStore(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CompletionStage<DepthWriteResult> apply(DepthExtendRequest request) {
        return extend(request);
    }

    /**
     * Apply one extend request.
     *
     * @return a stage with the outcome; exceptionally completed on any
     *         durable failure (never reported as applied then)
     */
    public CompletionStage<DepthWriteResult> extend(DepthExtendRequest request) {
        Objects.requireNonNull(request, "request");
        return store.submitAsync(conn -> SqlTransaction.run(conn, inner -> applyInTransaction(inner, request)));
    }

    private DepthWriteResult applyInTransaction(Connection conn, DepthExtendRequest request) throws SQLException {
        ChunkKey chunk = request.chunk();
        Row row = loadRow(conn, chunk);
        if (row == null) {
            return notApplied(request, VerticalDepths.LEGACY_STORED_FALLBACK_Y);
        }
        if (!row.landId.equals(request.landId().value())) {
            return notApplied(request, row.before);
        }
        if (request.requestedDepth() >= row.before) {
            return notApplied(request, row.before);
        }
        int updated = updateStoredConditionally(conn, chunk, request.landId().value(), request.requestedDepth());
        if (updated == 0) {
            // Lost the compare-and-swap: another writer moved the row (or the
            // land changed) between the read and the conditional UPDATE.
            // Re-read the durable value and report a no-op without an audit.
            Row current = loadRow(conn, chunk);
            int durable = current == null ? VerticalDepths.LEGACY_STORED_FALLBACK_Y : current.before;
            return notApplied(request, durable);
        }
        if (updated != 1) {
            throw new SQLException("depth extend expected to update 1 row, updated " + updated);
        }
        insertAudit(conn, request, row.before, request.requestedDepth());
        return new DepthWriteResult(
                chunk, request.landId(), true, row.before, request.requestedDepth(), request.triggeringOperationY());
    }

    private static DepthWriteResult notApplied(DepthExtendRequest request, int durable) {
        return new DepthWriteResult(request.chunk(), request.landId(), false,
                durable, durable, request.triggeringOperationY());
    }

    private record Row(UUID landId, int before) {
    }

    private static Row loadRow(Connection conn, ChunkKey chunk) throws SQLException {
        String sql = "SELECT land_id, stored_min_protected_y FROM land_chunks "
                + "WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidBlob.encode(chunk.worldId()));
            ps.setInt(2, chunk.chunkX());
            ps.setInt(3, chunk.chunkZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                byte[] landBytes = rs.getBytes(1);
                if (landBytes == null) {
                    return null;
                }
                int stored = rs.getInt(2);
                int before = rs.wasNull() ? VerticalDepths.LEGACY_STORED_FALLBACK_Y : stored;
                return new Row(UuidBlob.decode(landBytes), before);
            }
        }
    }

    /**
     * Single-statement compare-and-swap: the row moves only when it still
     * belongs to the requesting land and its durable value (nullable rows
     * read through the legacy fallback) is still strictly shallower than
     * the requested depth.
     *
     * @return the affected-row count: {@code 1} when this writer won,
     *         {@code 0} when a concurrent writer already moved the row,
     *         the land changed, or the request turned stale
     */
    private static int updateStoredConditionally(Connection conn, ChunkKey chunk, UUID landId, int deeper)
            throws SQLException {
        String sql = "UPDATE land_chunks SET stored_min_protected_y = ? "
                + "WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? AND land_id = ? "
                + "AND COALESCE(stored_min_protected_y, "
                + VerticalDepths.LEGACY_STORED_FALLBACK_Y
                + ") > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, deeper);
            ps.setBytes(2, UuidBlob.encode(chunk.worldId()));
            ps.setInt(3, chunk.chunkX());
            ps.setInt(4, chunk.chunkZ());
            ps.setBytes(5, UuidBlob.encode(landId));
            ps.setInt(6, deeper);
            return ps.executeUpdate();
        }
    }

    private static void insertAudit(Connection conn, DepthExtendRequest request, int before, int after)
            throws SQLException {
        String sql = """
                INSERT INTO audit_log (timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        long auditId;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, Instant.now().toEpochMilli());
            if (request.actor() == null) {
                ps.setBytes(2, null);
            } else {
                ps.setBytes(2, UuidBlob.encode(request.actor()));
            }
            ps.setString(3, ACTION);
            ps.setBytes(4, UuidBlob.encode(request.landId().value()));
            ps.setBytes(5, UuidBlob.encode(request.chunk().worldId()));
            ps.setLong(6, request.chunk().pack());
            ps.setInt(7, METADATA_SCHEMA_VERSION);
            ps.setString(8, "{\"storedMinProtectedY\":" + before + "}");
            ps.setString(9, "{\"storedMinProtectedY\":" + after + "}");
            ps.setString(10, "{\"triggeringOperationY\":" + request.triggeringOperationY() + "}");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                auditId = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_chunks (audit_id, world_uuid, chunk_x, chunk_z) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, auditId);
            ps.setBytes(2, UuidBlob.encode(request.chunk().worldId()));
            ps.setInt(3, request.chunk().chunkX());
            ps.setInt(4, request.chunk().chunkZ());
            ps.executeUpdate();
        }
    }

    /** For tests: the registered writer of this store's audit action. */
    static String auditAction() {
        return ACTION;
    }

    /** For tests: connected-chunk view parity with the claim-path audit shape. */
    static List<ChunkKey> auditedChunks(DepthExtendRequest request) {
        return List.of(request.chunk());
    }
}
