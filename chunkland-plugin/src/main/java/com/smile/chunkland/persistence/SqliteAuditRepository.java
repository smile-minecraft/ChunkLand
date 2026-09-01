package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class SqliteAuditRepository implements AuditRepository {

    private final PersistenceStore store;

    public SqliteAuditRepository(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CompletionStage<Long> insert(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return store.submitAsync(conn -> {
            String sql = """
                    INSERT INTO audit_log (timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            long generatedId;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, entry.timestamp().toEpochMilli());
                if (entry.actor() == null) ps.setBytes(2, null); else ps.setBytes(2, UuidBlob.encode(entry.actor()));
                ps.setString(3, entry.action());
                if (entry.landId() == null) ps.setBytes(4, null); else ps.setBytes(4, UuidBlob.encode(entry.landId().value()));
                if (entry.worldId() == null) ps.setBytes(5, null); else ps.setBytes(5, UuidBlob.encode(entry.worldId()));
                if (entry.singleChunkPacked() == null) ps.setObject(6, null); else ps.setLong(6, entry.singleChunkPacked());
                ps.setInt(7, entry.metadataVersion());
                ps.setString(8, entry.beforeJson());
                ps.setString(9, entry.afterJson());
                ps.setString(10, entry.metadataJson());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    generatedId = keys.getLong(1);
                }
            }
            if (!entry.chunks().isEmpty()) {
                String chunkSql = "INSERT INTO audit_chunks (audit_id, world_uuid, chunk_x, chunk_z) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(chunkSql)) {
                    for (ChunkKey ck : entry.chunks()) {
                        ps.setLong(1, generatedId);
                        ps.setBytes(2, UuidBlob.encode(ck.worldId()));
                        ps.setInt(3, ck.chunkX());
                        ps.setInt(4, ck.chunkZ());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return generatedId;
        });
    }

    @Override
    public CompletionStage<Optional<AuditEntry>> findById(long id) {
        return store.submitAsync(conn -> findByIdInternal(conn, id));
    }

    @Override
    public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
        Objects.requireNonNull(landId, "landId");
        return store.submitAsync(conn -> {
            String sql = "SELECT id, timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json FROM audit_log WHERE land_id = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?";
            List<AuditEntry> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, UuidBlob.encode(landId.value()));
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapRow(conn, rs));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
        Objects.requireNonNull(action, "action");
        return store.submitAsync(conn -> {
            String sql = "SELECT id, timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json FROM audit_log WHERE action = ? ORDER BY timestamp DESC LIMIT ?";
            List<AuditEntry> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, action);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapRow(conn, rs));
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
        return store.submitAsync(conn -> {
            String sql = "SELECT id, timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json FROM audit_log ORDER BY timestamp DESC LIMIT ? OFFSET ?";
            List<AuditEntry> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapRow(conn, rs));
                }
            }
            return List.copyOf(out);
        });
    }

    private Optional<AuditEntry> findByIdInternal(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json FROM audit_log WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(conn, rs));
            }
        }
    }

    private AuditEntry mapRow(Connection conn, ResultSet rs) throws SQLException {
        long id = rs.getLong(1);
        Instant ts = Instant.ofEpochMilli(rs.getLong(2));
        byte[] actorB = rs.getBytes(3);
        UUID actor = actorB == null ? null : UuidBlob.decode(actorB);
        String action = rs.getString(4);
        byte[] landB = rs.getBytes(5);
        LandId landId = landB == null ? null : new LandId(UuidBlob.decode(landB));
        byte[] worldB = rs.getBytes(6);
        UUID worldId = worldB == null ? null : UuidBlob.decode(worldB);
        Long packed = rs.getObject(7) == null ? null : rs.getLong(7);
        int meta = rs.getInt(8);
        String before = rs.getString(9);
        String after = rs.getString(10);
        String metaJson = rs.getString(11);
        List<ChunkKey> chunks = loadChunks(conn, id);
        return new AuditEntry(id, ts, actor, action, landId, worldId, packed, meta, before, after, metaJson, chunks);
    }

    private List<ChunkKey> loadChunks(Connection conn, long auditId) throws SQLException {
        List<ChunkKey> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT world_uuid, chunk_x, chunk_z FROM audit_chunks WHERE audit_id = ? ORDER BY chunk_x, chunk_z")) {
            ps.setLong(1, auditId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID wid = UuidBlob.decode(rs.getBytes(1));
                    int x = rs.getInt(2);
                    int z = rs.getInt(3);
                    out.add(new ChunkKey(wid, x, z));
                }
            }
        }
        return List.copyOf(out);
    }
}
