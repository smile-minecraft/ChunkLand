package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
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

public final class SqliteSubLandRepository implements SubLandRepository {

    private final PersistenceStore store;

    public SqliteSubLandRepository(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CompletionStage<Void> save(SubLandSnapshot subLand) {
        Objects.requireNonNull(subLand, "subLand");
        if (subLand.cuboid() == null) {
            throw new IllegalArgumentException("SubLand must have cuboid geometry");
        }
        return store.submitAsync(conn -> {
            Cuboid c = subLand.cuboid();
            UUID wid = extractWorld(subLand);
            String sql = """
                    INSERT INTO sublands (id, land_id, name, min_x, min_y, min_z, max_x, max_y, max_z, world_uuid)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        land_id=excluded.land_id,
                        name=excluded.name,
                        min_x=excluded.min_x, min_y=excluded.min_y, min_z=excluded.min_z,
                        max_x=excluded.max_x, max_y=excluded.max_y, max_z=excluded.max_z,
                        world_uuid=excluded.world_uuid
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, UuidBlob.encode(subLand.id().value()));
                ps.setBytes(2, UuidBlob.encode(subLand.parentLandId().value()));
                ps.setString(3, subLand.name());
                ps.setInt(4, c.minX());
                ps.setInt(5, c.minY());
                ps.setInt(6, c.minZ());
                ps.setInt(7, c.maxX());
                ps.setInt(8, c.maxY());
                ps.setInt(9, c.maxZ());
                ps.setBytes(10, UuidBlob.encode(wid));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletionStage<Optional<SubLandSnapshot>> findById(SubLandId id) {
        Objects.requireNonNull(id, "id");
        return store.submitAsync(conn -> findInternal(conn, id));
    }

    @Override
    public CompletionStage<List<SubLandSnapshot>> findByLand(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return store.submitAsync(conn -> {
            String sql = "SELECT id, name, min_x, min_y, min_z, max_x, max_y, max_z, world_uuid, land_id FROM sublands WHERE land_id = ? ORDER BY name";
            List<SubLandSnapshot> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, UuidBlob.encode(landId.value()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapRow(rs));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public CompletionStage<Void> delete(SubLandId id) {
        Objects.requireNonNull(id, "id");
        return store.submitAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sublands WHERE id = ?")) {
                ps.setBytes(1, UuidBlob.encode(id.value()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    private Optional<SubLandSnapshot> findInternal(Connection conn, SubLandId id) throws SQLException {
        String sql = "SELECT id, name, min_x, min_y, min_z, max_x, max_y, max_z, world_uuid, land_id FROM sublands WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, UuidBlob.encode(id.value()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    private SubLandSnapshot mapRow(ResultSet rs) throws SQLException {
        UUID sid = UuidBlob.decode(rs.getBytes(1));
        String name = rs.getString(2);
        int minX = rs.getInt(3);
        int minY = rs.getInt(4);
        int minZ = rs.getInt(5);
        int maxX = rs.getInt(6);
        int maxY = rs.getInt(7);
        int maxZ = rs.getInt(8);
        UUID wid = UuidBlob.decode(rs.getBytes(9));
        UUID landUuid = UuidBlob.decode(rs.getBytes(10));
        Cuboid cuboid = new Cuboid(minX, minY, minZ, maxX, maxY, maxZ);
        return new SubLandSnapshot(new SubLandId(sid), new LandId(landUuid), name, cuboid, wid);
    }

    private UUID extractWorld(SubLandSnapshot s) {
        if (!s.chunks().isEmpty()) {
            return s.chunks().iterator().next().worldId();
        }
        throw new IllegalStateException("SubLand has no chunks to infer world");
    }
}
