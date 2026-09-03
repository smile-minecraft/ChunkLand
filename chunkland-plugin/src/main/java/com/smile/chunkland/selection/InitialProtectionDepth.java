package com.smile.chunkland.selection;

import java.util.Objects;

/**
 * Pure selection-plane depth derivation.
 *
 * <p>Implements the Initial Protection Depth rule from spec §17:
 * {@code storedMinProtectedY = min(PointA.blockY, PointB.blockY) - buffer},
 * clamped to {@code [worldMinY, worldMaxY]}. For a single manually added chunk,
 * the clicked block Y is used via {@link #resolveSingle(int, int, int, int)}.
 *
 * <p>The derivation is intentionally memory-only: it uses only the block Y
 * carried by {@link SelectionPoint} / {@link SelectionSession} and caller-supplied
 * buffer and world-height bounds. It never scans terrain, queries height data,
 * inspects block data, or loads a World or Chunk. The computed value becomes
 * the persisted {@code storedMinProtectedY} and is later read through the
 * snapshot-based seam {@link com.smile.chunkland.runtime.api.ProtectionDepthLookup}
 * ({@code getProtectionDepth(landId, snapshot)}). This class does not create a
 * second query path.</p>
 */
public final class InitialProtectionDepth {

    private InitialProtectionDepth() {
    }

    /**
     * Derive the initial depth from two selection Y values.
     *
     * @param yA first corner block Y
     * @param yB second corner block Y
     * @param buffer non-negative blocks subtracted from the lower selection plane
     * @param worldMinY inclusive world minimum block Y
     * @param worldMaxY inclusive world maximum block Y
     * @return clamped {@code min(yA, yB) - buffer} within {@code [worldMinY, worldMaxY]}
     * @throws IllegalArgumentException if {@code buffer < 0} or {@code worldMinY > worldMaxY}
     */
    public static int resolve(int yA, int yB, int buffer, int worldMinY, int worldMaxY) {
        if (buffer < 0) {
            throw new IllegalArgumentException("buffer must be >= 0: " + buffer);
        }
        if (worldMinY > worldMaxY) {
            throw new IllegalArgumentException("worldMinY (" + worldMinY + ") > worldMaxY (" + worldMaxY + ")");
        }
        int lower = Math.min(yA, yB);
        long raw = (long) lower - (long) buffer;
        if (raw < worldMinY) {
            return worldMinY;
        }
        if (raw > worldMaxY) {
            return worldMaxY;
        }
        return (int) raw;
    }

    /**
     * Derive from two explicit selection points.
     *
     * @throws NullPointerException if a point is null
     * @throws IllegalArgumentException if points belong to different worlds
     */
    public static int resolve(SelectionPoint pointA, SelectionPoint pointB, int buffer, int worldMinY, int worldMaxY) {
        Objects.requireNonNull(pointA, "pointA");
        Objects.requireNonNull(pointB, "pointB");
        if (!pointA.worldId().equals(pointB.worldId())) {
            throw new IllegalArgumentException("selection points must be in the same world");
        }
        return resolve(pointA.blockY(), pointB.blockY(), buffer, worldMinY, worldMaxY);
    }

    /**
     * Derive from a session that already holds both selection points.
     *
     * @throws NullPointerException if session is null
     * @throws IllegalArgumentException if the session lacks both points
     */
    public static int resolve(SelectionSession session, int buffer, int worldMinY, int worldMaxY) {
        Objects.requireNonNull(session, "session");
        SelectionPoint a = session.pointA().orElseThrow(() -> new IllegalArgumentException("session missing pointA"));
        SelectionPoint b = session.pointB().orElseThrow(() -> new IllegalArgumentException("session missing pointB"));
        return resolve(a, b, buffer, worldMinY, worldMaxY);
    }

    /**
     * Single-chunk manual addition: use the clicked block Y as the plane.
     *
     * @param blockY clicked block Y
     * @param buffer non-negative buffer
     */
    public static int resolveSingle(int blockY, int buffer, int worldMinY, int worldMaxY) {
        if (buffer < 0) {
            throw new IllegalArgumentException("buffer must be >= 0: " + buffer);
        }
        if (worldMinY > worldMaxY) {
            throw new IllegalArgumentException("worldMinY (" + worldMinY + ") > worldMaxY (" + worldMaxY + ")");
        }
        long raw = (long) blockY - (long) buffer;
        if (raw < worldMinY) {
            return worldMinY;
        }
        if (raw > worldMaxY) {
            return worldMaxY;
        }
        return (int) raw;
    }

    /**
     * Single-chunk variant sourced from a point.
     */
    public static int resolveSingle(SelectionPoint point, int buffer, int worldMinY, int worldMaxY) {
        Objects.requireNonNull(point, "point");
        return resolveSingle(point.blockY(), buffer, worldMinY, worldMaxY);
    }
}
