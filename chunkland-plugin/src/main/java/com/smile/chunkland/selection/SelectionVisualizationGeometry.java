package com.smile.chunkland.selection;

import com.smile.chunkland.api.geometry.BoundaryExtractor;
import com.smile.chunkland.api.geometry.BoundarySegment;
import com.smile.chunkland.api.land.ChunkKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, server-independent planning of selection boundary particles.
 *
 * <p>Geometry comes only from the session snapshot (selected chunks, and the
 * stored block heights of its points) plus pure chunk math. This class never
 * queries a Highest Block, block data, an entity, or any Bukkit world/chunk/block
 * object — there is intentionally no code path here that could load terrain.
 * The render plane follows the highest stored point height plus one, clamped
 * into the viewer's visible band ({@link #PLANE_BELOW_VIEWER} blocks below to
 * {@link #PLANE_ABOVE_VIEWER} blocks above the viewer) so a click into a cave
 * or floating in the sky still gets a visible boundary. Sessions without points
 * fall back to the viewer height.
 *
 * <p>Planning is deterministic: segments are sorted by chunk then direction, the
 * segment cap keeps a prefix, each edge is sampled at fixed fractions, and the
 * per-tick {@link Frame#window(long, int)} rotates over the ordered points so a
 * large boundary shimmers across ticks instead of exceeding the particle budget.
 */
public final class SelectionVisualizationGeometry {

    /** Samples per boundary edge, at fixed fractions that avoid shared corners. */
    static final double[] SAMPLE_FRACTIONS = {
            0.0625, 0.1875, 0.3125, 0.4375, 0.5625, 0.6875, 0.8125, 0.9375
    };

    /** The render plane is never pushed more than this far below the viewer. */
    static final double PLANE_BELOW_VIEWER = 2.0;

    /** The render plane is never pushed more than this far above the viewer. */
    static final double PLANE_ABOVE_VIEWER = 8.0;

    private SelectionVisualizationGeometry() {
        // static utility only
    }

    /** One particle position in block coordinates. */
    public record Point(double x, double y, double z) {
    }

    /**
     * One planned render frame: the ordered particle positions for the current
     * viewer, how many boundary segments survived culling and the cap, and
     * whether the cap dropped anything.
     */
    public record Frame(List<Point> points, int segmentCount, boolean truncated) {
        public Frame {
            Objects.requireNonNull(points, "points");
            points = List.copyOf(points);
            if (segmentCount < 0) {
                throw new IllegalArgumentException("segmentCount must be non-negative");
            }
        }

        /**
         * The particles to emit on the given tick: at most {@code maxPerTick},
         * rotating deterministically over the ordered points so every point is
         * shown once per full cycle. The window advances by {@code maxPerTick}
         * per tick (modulo the point count, computed overflow-safe), so
         * consecutive ticks sweep the boundary without overlap.
         */
        public List<Point> window(long tickIndex, int maxPerTick) {
            if (maxPerTick <= 0) {
                throw new IllegalArgumentException("maxPerTick must be positive: " + maxPerTick);
            }
            if (points.isEmpty() || points.size() <= maxPerTick) {
                return points;
            }
            int size = points.size();
            int start = (int) (Math.floorMod(tickIndex, (long) size) * (long) maxPerTick % size);
            List<Point> window = new ArrayList<>(maxPerTick);
            for (int i = 0; i < maxPerTick; i++) {
                window.add(points.get((start + i) % size));
            }
            return List.copyOf(window);
        }
    }

    /**
     * Plan one frame for the viewer at the given block coordinates.
     *
     * @param session the session snapshot to render (only its chunks and stored points are read)
     * @param budget the active caps
     * @param viewerX viewer block x used for render-distance culling
     * @param viewerY viewer block y used only as the plane fallback when the session has no points
     * @param viewerZ viewer block z used for render-distance culling
     */
    public static Frame plan(
            SelectionSession session,
            SelectionVisualizationBudget budget,
            double viewerX,
            double viewerY,
            double viewerZ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(budget, "budget");
        return plan(session.selectedChunks(), planeY(session, viewerY), budget, viewerX, viewerZ);
    }

    /**
     * Plan one frame for an explicit immutable chunk set on a fixed plane.
     *
     * <p>Used by the occupied-land preview, which renders a land's real chunk
     * boundary without owning a selection session. Geometry, culling, caps and
     * sampling are identical to the session path, so the preview honours the
     * same budget.
     *
     * @param chunks the immutable chunk set to outline
     * @param planeY the fixed render plane in block coordinates
     * @param budget the active caps
     * @param viewerX viewer block x used for render-distance culling
     * @param viewerZ viewer block z used for render-distance culling
     */
    public static Frame plan(
            Set<ChunkKey> chunks,
            double planeY,
            SelectionVisualizationBudget budget,
            double viewerX,
            double viewerZ) {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(budget, "budget");
        if (chunks.isEmpty()) {
            return new Frame(List.of(), 0, false);
        }
        List<BoundarySegment> segments = new ArrayList<>(BoundaryExtractor.extract(chunks));
        segments.sort(Comparator.comparingInt((BoundarySegment segment) -> segment.chunk().chunkX())
                .thenComparingInt(segment -> segment.chunk().chunkZ())
                .thenComparingInt(segment -> segment.direction().ordinal()));
        double maxDistanceSquared = (double) budget.renderDistanceBlocks() * budget.renderDistanceBlocks();
        List<BoundarySegment> visible = new ArrayList<>(segments.size());
        for (BoundarySegment segment : segments) {
            if (withinRenderDistance(segment, viewerX, viewerZ, maxDistanceSquared)) {
                visible.add(segment);
            }
        }
        boolean truncated = visible.size() > budget.maxSegments();
        List<BoundarySegment> kept = truncated ? visible.subList(0, budget.maxSegments()) : visible;
        List<Point> points = new ArrayList<>(kept.size() * SAMPLE_FRACTIONS.length);
        for (BoundarySegment segment : kept) {
            long[] edge = segment.edgeEndpoints();
            double x0 = (double) edge[0] * 16.0;
            double z0 = (double) edge[1] * 16.0;
            double x1 = (double) edge[2] * 16.0;
            double z1 = (double) edge[3] * 16.0;
            for (double fraction : SAMPLE_FRACTIONS) {
                points.add(new Point(
                        x0 + (x1 - x0) * fraction,
                        planeY,
                        z0 + (z1 - z0) * fraction));
            }
        }
        return new Frame(points, kept.size(), truncated);
    }

    private static boolean withinRenderDistance(
            BoundarySegment segment, double viewerX, double viewerZ, double maxDistanceSquared) {
        long[] edge = segment.edgeEndpoints();
        double midX = ((double) edge[0] + edge[2]) * 8.0;
        double midZ = ((double) edge[1] + edge[3]) * 8.0;
        double dx = midX - viewerX;
        double dz = midZ - viewerZ;
        return dx * dx + dz * dz <= maxDistanceSquared;
    }

    private static double planeY(SelectionSession session, double viewerY) {
        int highest = Integer.MIN_VALUE;
        if (session.pointA().isPresent()) {
            highest = Math.max(highest, session.pointA().orElseThrow().blockY());
        }
        if (session.pointB().isPresent()) {
            highest = Math.max(highest, session.pointB().orElseThrow().blockY());
        }
        double candidate = highest == Integer.MIN_VALUE ? viewerY : (double) highest + 1.0;
        // Keep the plane where the viewer can actually see it: a click deep
        // underground raises it to just below eye level, a click high in the sky
        // lowers it to just above. Pure arithmetic — never a world/block read.
        double min = viewerY - PLANE_BELOW_VIEWER;
        double max = viewerY + PLANE_ABOVE_VIEWER;
        if (candidate < min) {
            return min;
        }
        if (candidate > max) {
            return max;
        }
        return candidate;
    }
}
