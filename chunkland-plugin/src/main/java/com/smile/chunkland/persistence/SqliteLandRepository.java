package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class SqliteLandRepository implements LandRepository {

    private final PersistenceStore store;

    public SqliteLandRepository(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CompletionStage<Void> save(LandSnapshot land) {
        Objects.requireNonNull(land, "land");
        return store.submitAsync(conn -> {
            saveInternal(conn, land);
            return null;
        });
    }

    @Override
    public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
        Objects.requireNonNull(id, "id");
        return store.submitAsync(conn -> findByIdInternal(conn, id));
    }

    @Override
    public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
        Objects.requireNonNull(owner, "owner");
        return store.submitAsync(conn -> findByOwnerInternal(conn, owner));
    }

    @Override
    public CompletionStage<List<LandSnapshot>> findAll() {
        return store.submitAsync(this::findAllInternal);
    }

    @Override
    public CompletionStage<Void> delete(LandId id) {
        Objects.requireNonNull(id, "id");
        return store.submitAsync(conn -> {
            deleteInternal(conn, id);
            return null;
        });
    }

    // ---- internal JDBC on persistence thread ----

    private void saveInternal(Connection conn, LandSnapshot land) throws SQLException {
        // Enforce Server Land non-transferable at persistence boundary: an existing SERVER land
        // must not be silently re-owned as PLAYER via upsert.
        try (PreparedStatement check = conn.prepareStatement("SELECT owner_key FROM lands WHERE id = ?")) {
            check.setBytes(1, UuidBlob.encode(land.id().value()));
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    String existingOwnerKey = rs.getString(1);
                    boolean existingIsServer = OwnerKey.SERVER_VALUE.equals(existingOwnerKey);
                    boolean newIsPlayer = land.ownerRef() instanceof OwnerRef.PlayerOwnerRef;
                    if (existingIsServer && newIsPlayer) {
                        throw new IllegalStateException("Server Land is not transferable");
                    }
                }
            }
        }
        String sql = """
                INSERT INTO lands (id, owner_key, display_name, name_key, world_uuid,
                                   structure_revision, land_policy_revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    owner_key=excluded.owner_key,
                    display_name=excluded.display_name,
                    name_key=excluded.name_key,
                    world_uuid=excluded.world_uuid,
                    structure_revision=excluded.structure_revision,
                    land_policy_revision=excluded.land_policy_revision,
                    created_at=excluded.created_at,
                    updated_at=excluded.updated_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidBlob.encode(land.id().value()));
            ps.setString(2, land.ownerRef().key());
            ps.setString(3, land.displayName());
            ps.setString(4, land.nameKey());
            ps.setBytes(5, UuidBlob.encode(land.worldId()));
            ps.setLong(6, land.structureRevision());
            ps.setLong(7, land.landPolicyRevision());
            ps.setLong(8, land.createdAt().toEpochMilli());
            ps.setLong(9, land.updatedAt().toEpochMilli());
            ps.executeUpdate();
        }
        // Note: chunks and sublands are managed by their own repositories; Land delete cascade
        // handles cleanup. This keeps the repository focused and avoids cross-transaction complexity.
    }

    private Optional<LandSnapshot> findByIdInternal(Connection conn, LandId id) throws SQLException {
        String sql = "SELECT owner_key, display_name, name_key, world_uuid, structure_revision, land_policy_revision, created_at, updated_at FROM lands WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidBlob.encode(id.value()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String ownerKey = rs.getString(1);
                String displayName = rs.getString(2);
                String nameKey = rs.getString(3);
                UUID worldId = UuidBlob.decode(rs.getBytes(4));
                long structureRevision = rs.getLong(5);
                long policyRevision = rs.getLong(6);
                Instant created = Instant.ofEpochMilli(rs.getLong(7));
                Instant updated = Instant.ofEpochMilli(rs.getLong(8));
                OwnerRef owner = parseOwner(ownerKey);
                Set<ChunkKey> chunks = loadChunks(conn, id, worldId);
                List<SubLandSnapshot> subLands = loadSubLands(conn, id);
                LandSnapshot snap = new LandSnapshot(id, displayName, nameKey, owner, worldId, chunks, subLands, structureRevision, policyRevision, created, updated);
                return Optional.of(snap);
            }
        }
    }

    private List<LandSnapshot> findByOwnerInternal(Connection conn, OwnerRef owner) throws SQLException {
        String sql = "SELECT id FROM lands WHERE owner_key = ? ORDER BY created_at";
        List<LandId> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner.key());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(new LandId(UuidBlob.decode(rs.getBytes(1))));
                }
            }
        }
        List<LandSnapshot> result = new ArrayList<>();
        for (LandId lid : ids) {
            findByIdInternal(conn, lid).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private List<LandSnapshot> findAllInternal(Connection conn) throws SQLException {
        String sql = "SELECT id FROM lands ORDER BY created_at";
        List<LandId> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(new LandId(UuidBlob.decode(rs.getBytes(1))));
            }
        }
        List<LandSnapshot> result = new ArrayList<>();
        for (LandId lid : ids) {
            findByIdInternal(conn, lid).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private void deleteInternal(Connection conn, LandId id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM lands WHERE id = ?")) {
            ps.setBytes(1, UuidBlob.encode(id.value()));
            ps.executeUpdate();
        }
    }

    private Set<ChunkKey> loadChunks(Connection conn, LandId landId, UUID worldId) throws SQLException {
        Set<ChunkKey> out = new HashSet<>();
        String sql = "SELECT world_uuid, chunk_x, chunk_z FROM land_chunks WHERE land_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] w = rs.getBytes(1);
                    // rows inserted via legacy path may have null world_uuid/chunk_x; skip those
                    if (w == null) continue;
                    int x = rs.getInt(2);
                    if (rs.wasNull()) continue;
                    int z = rs.getInt(3);
                    if (rs.wasNull()) continue;
                    UUID wid = UuidBlob.decode(w);
                    out.add(new ChunkKey(wid, x, z));
                }
            }
        }
        return Set.copyOf(out);
    }

    private List<SubLandSnapshot> loadSubLands(Connection conn, LandId landId) throws SQLException {
        String sql = "SELECT id, name, min_x, min_y, min_z, max_x, max_y, max_z, world_uuid FROM sublands WHERE land_id = ?";
        List<SubLandSnapshot> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID sid = UuidBlob.decode(rs.getBytes(1));
                    String name = rs.getString(2);
                    int minX = rs.getInt(3);
                    int minY = rs.getInt(4);
                    int minZ = rs.getInt(5);
                    int maxX = rs.getInt(6);
                    int maxY = rs.getInt(7);
                    int maxZ = rs.getInt(8);
                    UUID wid = UuidBlob.decode(rs.getBytes(9));
                    Cuboid cuboid = new Cuboid(minX, minY, minZ, maxX, maxY, maxZ);
                    SubLandSnapshot snap = new SubLandSnapshot(new SubLandId(sid), landId, name, cuboid, wid);
                    out.add(snap);
                }
            }
        }
        return List.copyOf(out);
    }

    private static OwnerRef parseOwner(String ownerKey) {
        if (OwnerKey.SERVER_VALUE.equals(ownerKey)) {
            return OwnerRef.server();
        }
        if (ownerKey.startsWith(OwnerKey.PLAYER_PREFIX)) {
            String uuidText = ownerKey.substring(OwnerKey.PLAYER_PREFIX.length());
            return OwnerRef.player(UUID.fromString(uuidText));
        }
        throw new IllegalStateException("unknown owner_key: " + ownerKey);
    }
}
