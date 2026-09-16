package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.runtime.storage.OrphanWorldGuard;
import com.smile.chunkland.runtime.storage.WorldCatalogSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Persistence seam for orphan-world administration.
 *
 * <p>An orphan world is a {@code world_uuid} with durable land rows but no
 * entry in the server world catalog. The catalog arrives as a
 * {@link WorldCatalogSnapshot} bound at confirm time together with the
 * {@link OrphanWorldGuard} that versions it; this class never touches Bukkit.
 * All JDBC runs on the persistence executor through
 * {@link PersistenceStore#submitAsync}, so region threads never run SQL.
 *
 * <p>{@link #purgeOrphanWorld} holds the guard read lock for its whole SQL
 * boundary and re-checks the catalog generation and the world absence inside
 * the same transaction: a world that reloads — or any catalog change at all
 * — between the confirmation and the SQL aborts the purge before any row is
 * deleted or audited. It removes exactly one world's Land-owned rows —
 * lands, chunks, sublands and their bindings, defaults, rules and ENTRY bans
 * — together with one {@code ORPHAN_PURGE} audit row in a single transaction.
 * It never touches healthy worlds, the player-namespace group/profile rows,
 * the operation ledger, Economy state, or any refund path. A concurrent purge
 * of the same world loses: the second attempt observes zero rows and fails
 * with no side effect.
 */
public final class OrphanPurgeRepository {

    /** Audit action written once per successful purge, in the same transaction. */
    public static final String AUDIT_ACTION = "ORPHAN_PURGE";

    /** Metadata schema version for the {@code ORPHAN_PURGE} audit rows. */
    public static final int AUDIT_METADATA_VERSION = 1;

    /** Default page size for orphan listing. */
    public static final int DEFAULT_LIST_LIMIT = 20;

    /** Hard cap for one listing page so chat can never flood. */
    public static final int MAX_LIST_LIMIT = 100;

    /** Maximum land ids embedded in the purge audit metadata. */
    static final int AUDIT_LAND_ID_LIMIT = 20;

    /** One orphan world with its durable land count. */
    public record OrphanWorldSummary(UUID worldId, int landCount) {
        public OrphanWorldSummary {
            Objects.requireNonNull(worldId, "worldId");
            if (landCount <= 0) {
                throw new IllegalArgumentException("landCount must be positive");
            }
        }
    }

    /** Outcome of one successful purge. */
    public record OrphanPurgeResult(UUID worldId, int landCount, List<LandId> landIds) {
        public OrphanPurgeResult {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(landIds, "landIds");
            landIds = List.copyOf(landIds);
            if (landCount <= 0) {
                throw new IllegalArgumentException("landCount must be positive");
            }
            if (landIds.size() != landCount) {
                throw new IllegalArgumentException("landIds must match landCount");
            }
        }
    }

    private final PersistenceStore store;

    public OrphanPurgeRepository(PersistenceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Lists the durable worlds absent from the loaded set, newest-heaviest
     * first, bounded by {@code limit}.
     *
     * @param loadedWorlds caller-captured world catalog snapshot; never read from Bukkit here
     * @param limit maximum rows; clamped to {@code [1, MAX_LIST_LIMIT]}
     */
    public CompletionStage<List<OrphanWorldSummary>> listOrphanSummaries(
            Set<UUID> loadedWorlds, int limit) {
        Objects.requireNonNull(loadedWorlds, "loadedWorlds");
        Set<UUID> loaded = Set.copyOf(loadedWorlds);
        int bounded = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIST_LIMIT : limit, MAX_LIST_LIMIT));
        return store.submitAsync(connection -> {
            List<OrphanWorldSummary> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT world_uuid, COUNT(*) FROM lands GROUP BY world_uuid")) {
                while (result.next()) {
                    byte[] worldBytes = result.getBytes(1);
                    int count = result.getInt(2);
                    UUID world;
                    try {
                        world = UuidBlob.decode(worldBytes);
                    } catch (IllegalArgumentException malformed) {
                        throw new SQLException("malformed world_uuid in lands", malformed);
                    }
                    if (count <= 0 || loaded.contains(world)) {
                        continue;
                    }
                    rows.add(new OrphanWorldSummary(world, count));
                }
            }
            rows.sort(Comparator.comparingInt(OrphanWorldSummary::landCount).reversed()
                    .thenComparing(row -> row.worldId().toString()));
            return List.copyOf(rows.subList(0, Math.min(bounded, rows.size())));
        });
    }

    /** Counts the durable lands of one world. Unknown worlds count zero. */
    public CompletionStage<Integer> countLandsInWorld(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return store.submitAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM lands WHERE world_uuid = ?")) {
                statement.setBytes(1, UuidBlob.encode(worldId));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        });
    }

    /**
     * Atomically deletes one orphan world's Land-owned rows with its audit.
     *
     * <p>The confirmed snapshot binds the catalog generation the operator
     * confirmed against. The transaction takes the guard read lock — so no
     * catalog update can interleave — and aborts with
     * {@link OrphanConflictException} when the catalog is unverified, the
     * generation moved, the world is loaded, or the stored count moved since
     * {@code expectedCount}; when the world holds no rows it aborts with
     * {@link OrphanUnknownException}. Either way nothing is written.
     *
     * @param actor operator UUID, or {@code null} for console
     * @param confirmed catalog snapshot bound at confirm time
     * @param guard versioning guard for the catalog; held for the transaction
     */
    public CompletionStage<OrphanPurgeResult> purgeOrphanWorld(UUID worldId, UUID actor,
            WorldCatalogSnapshot confirmed, Instant now, int expectedCount,
            OrphanWorldGuard guard) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(confirmed, "confirmed");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(guard, "guard");
        if (expectedCount <= 0) {
            throw new IllegalArgumentException("expectedCount must be positive");
        }
        return store.submitAsync(connection -> {
            guard.readLock().lock();
            try {
                return SqlTransaction.run(connection, nested -> purgeInTransaction(
                        nested, worldId, actor, confirmed, now, expectedCount, guard));
            } finally {
                guard.readLock().unlock();
            }
        });
    }

    private static OrphanPurgeResult purgeInTransaction(Connection connection, UUID worldId,
            UUID actor, WorldCatalogSnapshot confirmed, Instant now, int expectedCount,
            OrphanWorldGuard guard) throws SQLException {
        final WorldCatalogSnapshot current;
        try {
            current = guard.currentUnderReadLock();
        } catch (RuntimeException lockFailure) {
            throw new SQLException("orphan catalog guard unreadable", lockFailure);
        }
        if (!current.isVerified() || current.generation() != confirmed.generation()) {
            throw new OrphanConflictException("world catalog changed since confirmation "
                    + "(confirmed generation " + confirmed.generation()
                    + ", current " + current.generation() + ")");
        }
        if (current.isLoaded(worldId)) {
            throw new OrphanConflictException("world is loaded; not an orphan: " + worldId);
        }
        List<UUID> landUuids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM lands WHERE world_uuid = ? ORDER BY id")) {
            statement.setBytes(1, UuidBlob.encode(worldId));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] landBytes = rows.getBytes(1);
                    try {
                        landUuids.add(UuidBlob.decode(landBytes));
                    } catch (IllegalArgumentException malformed) {
                        throw new SQLException("malformed land id in world " + worldId, malformed);
                    }
                }
            }
        }
        if (landUuids.isEmpty()) {
            throw new OrphanUnknownException("unknown orphan world: " + worldId);
        }
        if (landUuids.size() != expectedCount) {
            throw new OrphanConflictException("orphan world " + worldId + " holds "
                    + landUuids.size() + " lands, expected " + expectedCount);
        }
        for (UUID landUuid : landUuids) {
            deleteLandOwnedRows(connection, landUuid);
        }
        int removed;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM lands WHERE world_uuid = ?")) {
            statement.setBytes(1, UuidBlob.encode(worldId));
            removed = statement.executeUpdate();
        }
        if (removed != landUuids.size()) {
            throw new OrphanConflictException(
                    "concurrent purge modified world " + worldId + ": removed " + removed
                            + " of " + landUuids.size());
        }
        List<LandId> landIds = landUuids.stream().map(LandId::new).toList();
        insertPurgeAudit(connection, actor, worldId, landIds, now);
        return new OrphanPurgeResult(worldId, landUuids.size(), landIds);
    }

    /**
     * Removes every Land-owned row of one land: ENTRY bans, rules, defaults,
     * bindings, then the subland cascade (rules, defaults, bindings,
     * sublands) and finally the chunks. Mirrors the whole-land delete
     * cascade; group/profile, ledger, Economy and audit history rows are
     * never in this set.
     */
    private static void deleteLandOwnedRows(Connection connection, UUID landUuid)
            throws SQLException {
        byte[] land = UuidBlob.encode(landUuid);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM land_entry_bans WHERE land_id = ?")) {
            statement.setBytes(1, land);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM land_rules WHERE land_id = ?")) {
            statement.setBytes(1, land);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM land_defaults WHERE land_id = ?")) {
            statement.setBytes(1, land);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM land_bindings WHERE land_id = ?")) {
            statement.setBytes(1, land);
            statement.executeUpdate();
        }
        List<byte[]> sublandIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM sublands WHERE land_id = ?")) {
            statement.setBytes(1, land);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    sublandIds.add(rows.getBytes(1));
                }
            }
        }
        for (byte[] sublandId : sublandIds) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM subland_rules WHERE subland_id = ?")) {
                statement.setBytes(1, sublandId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM subland_defaults WHERE subland_id = ?")) {
                statement.setBytes(1, sublandId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM subland_bindings WHERE subland_id = ?")) {
                statement.setBytes(1, sublandId);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM sublands WHERE land_id = ?")) {
            statement.setBytes(1, land);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM land_chunks WHERE land_id = ?")) {
            statement.setBytes(1, land);
            statement.executeUpdate();
        }
    }

    private static void insertPurgeAudit(Connection connection, UUID actor, UUID worldId,
            List<LandId> landIds, Instant now) throws SQLException {
        String sql = """
                INSERT INTO audit_log (timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, metadata_schema_version, before_json, after_json, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now.toEpochMilli());
            if (actor == null) {
                statement.setBytes(2, null);
            } else {
                statement.setBytes(2, UuidBlob.encode(actor));
            }
            statement.setString(3, AUDIT_ACTION);
            statement.setBytes(4, null);
            statement.setBytes(5, UuidBlob.encode(worldId));
            statement.setObject(6, null);
            statement.setInt(7, AUDIT_METADATA_VERSION);
            statement.setString(8, "{\"count\":" + landIds.size() + "}");
            statement.setString(9, "{\"purged\":" + landIds.size() + "}");
            statement.setString(10, purgeMetadata(actor, worldId, landIds));
            statement.executeUpdate();
        }
    }

    /**
     * Bounded audit metadata: actor, world, count and at most
     * {@link #AUDIT_LAND_ID_LIMIT} land ids. Never embeds chunk sets, payloads
     * or Economy references.
     */
    static String purgeMetadata(UUID actor, UUID worldId, List<LandId> landIds) {
        StringBuilder metadata = new StringBuilder("{\"world\":\"");
        metadata.append(worldId).append("\",\"count\":").append(landIds.size());
        metadata.append(",\"actor\":\"").append(actor == null ? "console" : actor).append('"');
        metadata.append(",\"lands\":[");
        for (int i = 0; i < Math.min(landIds.size(), AUDIT_LAND_ID_LIMIT); i++) {
            if (i > 0) {
                metadata.append(',');
            }
            metadata.append('"').append(landIds.get(i).value()).append('"');
        }
        metadata.append("]}");
        return metadata.toString();
    }
}
