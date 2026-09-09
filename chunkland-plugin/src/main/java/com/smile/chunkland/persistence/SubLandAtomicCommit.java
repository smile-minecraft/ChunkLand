package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * All-or-nothing durable commit for SubLand create/update/delete.
 *
 * <p>One call writes the child row, the parent {@code structure_revision}
 * bump and the {@code SUBLAND_*} audit row inside a single SQLite transaction
 * on the persistence thread. The parent update is a durable
 * compare-and-set: the live {@code structure_revision} is re-read inside the
 * transaction and the bump applies only when it still equals the revision the
 * caller validated against, so two mutations racing on the same revision
 * serialize to exactly one winner and the loser fails with a stale conflict
 * instead of a lost update. The per-land limit and the child id presence are
 * re-checked inside the same transaction for the same reason.
 *
 * <p>No collaborator callback runs inside the transaction: the only
 * out-of-line call is the test-only failure injector, which never touches
 * Economy, Bukkit, or I/O.
 */
public final class SubLandAtomicCommit {

    /** Injection points for atomicity tests, mirroring the ledger seam. */
    public enum Step {
        AFTER_CHILD,
        AFTER_PARENT,
        AFTER_AUDIT
    }

    private final PersistenceStore store;
    private final Consumer<Step> failureInjector;

    public SubLandAtomicCommit(PersistenceStore store) {
        this(store, ignored -> {
        });
    }

    public SubLandAtomicCommit(PersistenceStore store, Consumer<Step> failureInjector) {
        this.store = Objects.requireNonNull(store, "store");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    }

    /**
     * Atomically insert one child, bump the parent revision and audit the create.
     *
     * @param child validated precise candidate
     * @param nextParent parent snapshot with the candidate appended (revision exactly one past expected)
     * @param expectedStructureRevision optimistic lock observed with the confirmation token
     * @param maxSublandsPerLand enforced limit, re-checked inside the transaction
     * @param audit audit entry for the mutation
     */
    public CompletionStage<Void> commitCreate(
            SubLandSnapshot child,
            LandSnapshot nextParent,
            long expectedStructureRevision,
            int maxSublandsPerLand,
            AuditEntry audit) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(nextParent, "nextParent");
        Objects.requireNonNull(audit, "audit");
        requireNextRevision(nextParent, expectedStructureRevision);
        requireParentMatch(nextParent, child.parentLandId());
        if (maxSublandsPerLand < 0) {
            throw new IllegalArgumentException("maxSublandsPerLand must be >= 0: " + maxSublandsPerLand);
        }
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            long stored = readStructureRevision(conn, nextParent.id());
            requireCurrent(stored, expectedStructureRevision);
            int count = countSublands(conn, nextParent.id());
            if (count >= maxSublandsPerLand) {
                throw new SQLException("max-sublands-per-land reached ("
                        + count + "/" + maxSublandsPerLand + ")");
            }
            if (sublandExists(conn, child.id())) {
                throw new SQLException("SubLand id already exists: " + child.id());
            }
            upsertSubland(conn, child);
            failureInjector.accept(Step.AFTER_CHILD);
            casParentRevision(conn, nextParent, expectedStructureRevision, audit.timestamp().toEpochMilli());
            failureInjector.accept(Step.AFTER_PARENT);
            insertAudit(conn, audit);
            failureInjector.accept(Step.AFTER_AUDIT);
            return null;
        }));
    }

    /**
     * Atomically replace one child, bump the parent revision and audit the update.
     */
    public CompletionStage<Void> commitUpdate(
            SubLandSnapshot child,
            LandSnapshot nextParent,
            long expectedStructureRevision,
            AuditEntry audit) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(nextParent, "nextParent");
        Objects.requireNonNull(audit, "audit");
        requireNextRevision(nextParent, expectedStructureRevision);
        requireParentMatch(nextParent, child.parentLandId());
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            long stored = readStructureRevision(conn, nextParent.id());
            requireCurrent(stored, expectedStructureRevision);
            if (!sublandExists(conn, child.id())) {
                throw new SQLException("unknown SubLand id: " + child.id());
            }
            upsertSubland(conn, child);
            failureInjector.accept(Step.AFTER_CHILD);
            casParentRevision(conn, nextParent, expectedStructureRevision, audit.timestamp().toEpochMilli());
            failureInjector.accept(Step.AFTER_PARENT);
            insertAudit(conn, audit);
            failureInjector.accept(Step.AFTER_AUDIT);
            return null;
        }));
    }

    /**
     * Atomically delete one child, bump the parent revision and audit the delete.
     */
    public CompletionStage<Void> commitDelete(
            SubLandId target,
            LandSnapshot nextParent,
            long expectedStructureRevision,
            AuditEntry audit) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(nextParent, "nextParent");
        Objects.requireNonNull(audit, "audit");
        requireNextRevision(nextParent, expectedStructureRevision);
        return store.submitAsync(connection -> SqlTransaction.run(connection, conn -> {
            long stored = readStructureRevision(conn, nextParent.id());
            requireCurrent(stored, expectedStructureRevision);
            if (!sublandExists(conn, target)) {
                throw new SQLException("unknown SubLand id: " + target);
            }
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM sublands WHERE id = ?")) {
                delete.setBytes(1, UuidBlob.encode(target.value()));
                if (delete.executeUpdate() != 1) {
                    throw new SQLException("SubLand disappeared during commit: " + target);
                }
            }
            failureInjector.accept(Step.AFTER_CHILD);
            casParentRevision(conn, nextParent, expectedStructureRevision, audit.timestamp().toEpochMilli());
            failureInjector.accept(Step.AFTER_PARENT);
            insertAudit(conn, audit);
            failureInjector.accept(Step.AFTER_AUDIT);
            return null;
        }));
    }

    private static void requireNextRevision(LandSnapshot nextParent, long expected) {
        long want;
        try {
            want = Math.addExact(expected, 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("expectedStructureRevision overflows: " + expected, overflow);
        }
        if (nextParent.structureRevision() != want) {
            throw new IllegalArgumentException("next parent revision " + nextParent.structureRevision()
                    + " must be exactly one past expected " + expected);
        }
    }

    private static void requireParentMatch(LandSnapshot nextParent, LandId parentId) {
        if (!nextParent.id().equals(parentId)) {
            throw new IllegalArgumentException("next parent " + nextParent.id()
                    + " does not match mutation parent " + parentId);
        }
    }

    private static void requireCurrent(long stored, long expected) throws SQLException {
        if (stored != expected) {
            throw new SQLException("stale parent structure revision: expected " + expected
                    + " but live is " + stored);
        }
    }

    private static long readStructureRevision(Connection conn, LandId parentId) throws SQLException {
        try (PreparedStatement query =
                     conn.prepareStatement("SELECT structure_revision FROM lands WHERE id = ?")) {
            query.setBytes(1, UuidBlob.encode(parentId.value()));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("unknown parent land " + parentId);
                }
                return rows.getLong(1);
            }
        }
    }

    private static int countSublands(Connection conn, LandId parentId) throws SQLException {
        try (PreparedStatement query =
                     conn.prepareStatement("SELECT COUNT(*) FROM sublands WHERE land_id = ?")) {
            query.setBytes(1, UuidBlob.encode(parentId.value()));
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static boolean sublandExists(Connection conn, SubLandId id) throws SQLException {
        try (PreparedStatement query =
                     conn.prepareStatement("SELECT 1 FROM sublands WHERE id = ? LIMIT 1")) {
            query.setBytes(1, UuidBlob.encode(id.value()));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void upsertSubland(Connection conn, SubLandSnapshot child) throws SQLException {
        Cuboid cuboid = child.cuboid();
        if (cuboid == null) {
            throw new SQLException("SubLand " + child.id() + " must carry precise Cuboid geometry");
        }
        UUID world = child.chunks().stream().findFirst()
                .map(ChunkKey::worldId)
                .orElse(null);
        if (world == null) {
            throw new SQLException("SubLand " + child.id() + " has no chunks to infer world");
        }
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
        try (PreparedStatement write = conn.prepareStatement(sql)) {
            write.setBytes(1, UuidBlob.encode(child.id().value()));
            write.setBytes(2, UuidBlob.encode(child.parentLandId().value()));
            write.setString(3, child.name());
            write.setInt(4, cuboid.minX());
            write.setInt(5, cuboid.minY());
            write.setInt(6, cuboid.minZ());
            write.setInt(7, cuboid.maxX());
            write.setInt(8, cuboid.maxY());
            write.setInt(9, cuboid.maxZ());
            write.setBytes(10, UuidBlob.encode(world));
            write.executeUpdate();
        }
    }

    private static void casParentRevision(
            Connection conn, LandSnapshot nextParent, long expected, long updatedAtMillis)
            throws SQLException {
        try (PreparedStatement bump = conn.prepareStatement(
                "UPDATE lands SET structure_revision = ?, updated_at = ? WHERE id = ? AND structure_revision = ?")) {
            bump.setLong(1, nextParent.structureRevision());
            bump.setLong(2, updatedAtMillis);
            bump.setBytes(3, UuidBlob.encode(nextParent.id().value()));
            bump.setLong(4, expected);
            if (bump.executeUpdate() != 1) {
                throw new SQLException("stale parent structure revision: expected " + expected
                        + " but the durable row moved");
            }
        }
    }

    private static void insertAudit(Connection conn, AuditEntry audit) throws SQLException {
        long auditId;
        try (PreparedStatement write = conn.prepareStatement(
                "INSERT INTO audit_log (timestamp, actor_uuid, action, land_id, world_uuid, single_chunk_packed, "
                        + "metadata_schema_version, before_json, after_json, metadata_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            write.setLong(1, audit.timestamp().toEpochMilli());
            setUuid(write, 2, audit.actor());
            write.setString(3, audit.action());
            setUuid(write, 4, audit.landId() == null ? null : audit.landId().value());
            setUuid(write, 5, audit.worldId());
            if (audit.singleChunkPacked() == null) {
                write.setObject(6, null);
            } else {
                write.setLong(6, audit.singleChunkPacked());
            }
            write.setInt(7, audit.metadataVersion());
            write.setString(8, audit.beforeJson());
            write.setString(9, audit.afterJson());
            write.setString(10, audit.metadataJson());
            if (write.executeUpdate() != 1) {
                throw new SQLException("audit insert affected no rows");
            }
            try (ResultSet keys = write.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("audit insert did not return an id");
                }
                auditId = keys.getLong(1);
            }
        }
        if (audit.chunks().isEmpty()) {
            return;
        }
        try (PreparedStatement chunks = conn.prepareStatement(
                "INSERT INTO audit_chunks (audit_id, world_uuid, chunk_x, chunk_z) VALUES (?, ?, ?, ?)")) {
            for (ChunkKey chunk : audit.chunks()) {
                chunks.setLong(1, auditId);
                chunks.setBytes(2, UuidBlob.encode(chunk.worldId()));
                chunks.setInt(3, chunk.chunkX());
                chunks.setInt(4, chunk.chunkZ());
                chunks.addBatch();
            }
            chunks.executeBatch();
        }
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setBytes(index, null);
        } else {
            statement.setBytes(index, UuidBlob.encode(value));
        }
    }
}
