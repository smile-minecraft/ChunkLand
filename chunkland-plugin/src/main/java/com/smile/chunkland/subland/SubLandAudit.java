package com.smile.chunkland.subland;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.persistence.AuditEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Audit entry factory for the three SubLand mutations.
 *
 * <p>Actions reuse the registered {@code SUBLAND_CREATE} / {@code SUBLAND_UPDATE} /
 * {@code SUBLAND_DELETE} audit actions from the central audit registry. The
 * {@code before}/{@code after} payloads carry the SubLand descriptor JSON and
 * the {@code metadata} payload carries the confirmation triple plus the depth
 * decision, so a reviewer can trace who confirmed which revision against
 * which parent structure revision and whether the depth floor was breached
 * with an explicit confirmation.
 */
public final class SubLandAudit {

    /** Metadata schema version for every SubLand audit entry. */
    public static final int METADATA_VERSION = 1;

    private SubLandAudit() {
    }

    /** Audit entry for a successful create (no {@code before} payload). */
    public static AuditEntry createEntry(
            UUID actor,
            LandSnapshot parent,
            SubLandSnapshot after,
            long generation,
            long selectionRevision,
            boolean depthExtendConfirmed,
            int effectiveMinY,
            Instant now) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(now, "now");
        return new AuditEntry(0L, now, actor, "SUBLAND_CREATE",
                parent.id(), parent.worldId(), null, METADATA_VERSION,
                null, describe(after),
                metadata(after, generation, selectionRevision,
                        parent.structureRevision(), effectiveMinY, depthExtendConfirmed),
                sortedChunks(after));
    }

    /** Audit entry for a successful update. */
    public static AuditEntry updateEntry(
            UUID actor,
            LandSnapshot parent,
            SubLandSnapshot before,
            SubLandSnapshot after,
            long generation,
            long selectionRevision,
            boolean depthExtendConfirmed,
            int effectiveMinY,
            Instant now) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(now, "now");
        return new AuditEntry(0L, now, actor, "SUBLAND_UPDATE",
                parent.id(), parent.worldId(), null, METADATA_VERSION,
                describe(before), describe(after),
                metadata(after, generation, selectionRevision,
                        parent.structureRevision(), effectiveMinY, depthExtendConfirmed),
                sortedChunks(after));
    }

    /** Audit entry for a successful delete (no {@code after} payload). */
    public static AuditEntry deleteEntry(
            UUID actor,
            LandSnapshot parent,
            SubLandSnapshot before,
            long generation,
            long selectionRevision,
            Instant now) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(now, "now");
        return new AuditEntry(0L, now, actor, "SUBLAND_DELETE",
                parent.id(), parent.worldId(), null, METADATA_VERSION,
                describe(before), null,
                metadata(before, generation, selectionRevision,
                        parent.structureRevision(), null, false),
                sortedChunks(before));
    }

    /** Stable descriptor JSON for one SubLand snapshot (hand-rolled, no dependency). */
    static String describe(SubLandSnapshot sub) {
        var c = sub.cuboid();
        StringBuilder out = new StringBuilder(192);
        out.append('{');
        out.append("\"id\":\"").append(sub.id().value()).append("\",");
        out.append("\"parent\":\"").append(sub.parentLandId().value()).append("\",");
        out.append("\"name\":");
        if (sub.name() == null) {
            out.append("null");
        } else {
            out.append('"').append(escape(sub.name())).append('"');
        }
        out.append(",\"cuboid\":");
        if (c == null) {
            out.append("null");
        } else {
            out.append("{\"minX\":").append(c.minX())
                    .append(",\"minY\":").append(c.minY())
                    .append(",\"minZ\":").append(c.minZ())
                    .append(",\"maxX\":").append(c.maxX())
                    .append(",\"maxY\":").append(c.maxY())
                    .append(",\"maxZ\":").append(c.maxZ())
                    .append('}');
        }
        out.append(",\"minBlockY\":").append(sub.minBlockY())
                .append(",\"maxBlockY\":").append(sub.maxBlockY());
        out.append('}');
        return out.toString();
    }

    private static String metadata(
            SubLandSnapshot sub,
            long generation,
            long selectionRevision,
            long structureRevision,
            Integer effectiveMinY,
            boolean depthExtendConfirmed) {
        StringBuilder out = new StringBuilder(192);
        out.append('{');
        out.append("\"sublandId\":\"").append(sub.id().value()).append("\",");
        out.append("\"generation\":").append(generation).append(',');
        out.append("\"selectionRevision\":").append(selectionRevision).append(',');
        out.append("\"structureRevision\":").append(structureRevision).append(',');
        out.append("\"effectiveMinProtectedY\":");
        out.append(effectiveMinY == null ? "null" : Integer.toString(effectiveMinY)).append(',');
        out.append("\"depthExtendConfirmed\":").append(depthExtendConfirmed);
        out.append('}');
        return out.toString();
    }

    private static List<ChunkKey> sortedChunks(SubLandSnapshot sub) {
        List<ChunkKey> chunks = new ArrayList<>(sub.chunks());
        chunks.sort(Comparator.comparingInt(ChunkKey::chunkX).thenComparingInt(ChunkKey::chunkZ));
        return List.copyOf(chunks);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
