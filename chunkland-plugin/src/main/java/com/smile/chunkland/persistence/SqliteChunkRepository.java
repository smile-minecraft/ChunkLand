package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class SqliteChunkRepository implements ChunkRepository {

    private final PersistenceStore store;

    public SqliteChunkRepository(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CompletionStage<Void> addChunk(LandId landId, ChunkKey chunk, int storedMinY, UUID claimLotId, long costBasisMinor) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(claimLotId, "claimLotId");
        return store.submitAsync(conn -> {
            String sql = """
                    INSERT INTO land_chunks (world, chunk, owner_key, claimed_at, land_id, world_uuid, chunk_x, chunk_z, stored_min_protected_y, claim_lot_id, cost_basis_minor_units)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(world, chunk) DO UPDATE SET
                        land_id=excluded.land_id,
                        world_uuid=excluded.world_uuid,
                        chunk_x=excluded.chunk_x,
                        chunk_z=excluded.chunk_z,
                        stored_min_protected_y=excluded.stored_min_protected_y,
                        claim_lot_id=excluded.claim_lot_id,
                        cost_basis_minor_units=excluded.cost_basis_minor_units
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, chunk.worldId().toString());
                ps.setString(2, chunk.chunkX() + "," + chunk.chunkZ());
                // owner_key/owner_uuid kept for legacy compatibility; fetch from lands if possible
                ps.setString(3, resolveOwnerKey(conn, landId));
                ps.setLong(4, System.currentTimeMillis());
                ps.setBytes(5, UuidBlob.encode(landId.value()));
                ps.setBytes(6, UuidBlob.encode(chunk.worldId()));
                ps.setInt(7, chunk.chunkX());
                ps.setInt(8, chunk.chunkZ());
                ps.setInt(9, storedMinY);
                ps.setBytes(10, UuidBlob.encode(claimLotId));
                ps.setLong(11, costBasisMinor);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletionStage<List<ChunkKey>> listByLand(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return store.submitAsync(conn -> {
            String sql = "SELECT world_uuid, chunk_x, chunk_z FROM land_chunks WHERE land_id = ? ORDER BY chunk_x, chunk_z";
            List<ChunkKey> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, UuidBlob.encode(landId.value()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] w = rs.getBytes(1);
                        if (w == null) continue;
                        UUID wid = UuidBlob.decode(w);
                        int x = rs.getInt(2);
                        int z = rs.getInt(3);
                        out.add(new ChunkKey(wid, x, z));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public CompletionStage<Optional<LandId>> findLandByChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return store.submitAsync(conn -> {
            String sql = "SELECT land_id FROM land_chunks WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, UuidBlob.encode(chunk.worldId()));
                ps.setInt(2, chunk.chunkX());
                ps.setInt(3, chunk.chunkZ());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // fallback to legacy world/chunk lookup
                        return findLandByLegacy(conn, chunk);
                    }
                    byte[] lid = rs.getBytes(1);
                    if (lid == null) return Optional.empty();
                    return Optional.of(new LandId(UuidBlob.decode(lid)));
                }
            }
        });
    }

    @Override
    public CompletionStage<Void> removeChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return store.submitAsync(conn -> {
            String sql = "DELETE FROM land_chunks WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, UuidBlob.encode(chunk.worldId()));
                ps.setInt(2, chunk.chunkX());
                ps.setInt(3, chunk.chunkZ());
                int deleted = ps.executeUpdate();
                if (deleted == 0) {
                    // try legacy
                    try (PreparedStatement ps2 = conn.prepareStatement("DELETE FROM land_chunks WHERE world = ? AND chunk = ?")) {
                        ps2.setString(1, chunk.worldId().toString());
                        ps2.setString(2, chunk.chunkX() + "," + chunk.chunkZ());
                        ps2.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    @Override
    public CompletionStage<Void> deleteByLand(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return store.submitAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM land_chunks WHERE land_id = ?")) {
                ps.setBytes(1, UuidBlob.encode(landId.value()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    private Optional<LandId> findLandByLegacy(Connection conn, ChunkKey chunk) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT land_id FROM land_chunks WHERE world = ? AND chunk = ?")) {
            ps.setString(1, chunk.worldId().toString());
            ps.setString(2, chunk.chunkX() + "," + chunk.chunkZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                byte[] lid = rs.getBytes(1);
                if (lid == null) return Optional.empty();
                return Optional.of(new LandId(UuidBlob.decode(lid)));
            }
        }
    }

    private String resolveOwnerKey(Connection conn, LandId landId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT owner_key FROM lands WHERE id = ?")) {
            ps.setBytes(1, UuidBlob.encode(landId.value()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return OwnerKey.SERVER_VALUE;
    }
}
