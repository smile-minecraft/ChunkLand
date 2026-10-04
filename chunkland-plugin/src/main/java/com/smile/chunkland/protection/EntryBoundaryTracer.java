package com.smile.chunkland.protection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Finds the border a denied player just ran into, as unit segments on the
 * block grid.
 *
 * <p>The tracer samples a square neighbourhood around the denied position
 * through a memory-only probe and reports every cell side that separates a
 * denied column from an allowed one. That follows the real outline of the
 * denying land or subland (corners included) instead of guessing a straight
 * line, and never reads the world: coordinates in, segments out.
 */
public final class EntryBoundaryTracer {

    /** Half-width of the sampled square, in blocks. */
    public static final int DEFAULT_RADIUS = 6;

    /** Whether the player is refused entry at one block column. */
    @FunctionalInterface
    public interface DeniedProbe {
        boolean denied(int blockX, int blockZ);
    }

    /** One border segment on the horizontal plane, one block long. */
    public record Edge(double x1, double z1, double x2, double z2) {
    }

    private EntryBoundaryTracer() {
    }

    /**
     * Traces the border inside the square of {@code radius} blocks around the
     * centre column.
     *
     * @return the border segments, empty when the neighbourhood is uniform,
     *         the radius is not positive, or the probe fails
     */
    public static List<Edge> trace(int centerX, int centerZ, int radius, DeniedProbe probe) {
        Objects.requireNonNull(probe, "probe");
        if (radius <= 0) {
            return List.of();
        }
        int side = radius * 2 + 1;
        int minX = centerX - radius;
        int minZ = centerZ - radius;
        boolean[][] denied = new boolean[side][side];
        try {
            for (int dx = 0; dx < side; dx++) {
                for (int dz = 0; dz < side; dz++) {
                    denied[dx][dz] = probe.denied(minX + dx, minZ + dz);
                }
            }
        } catch (RuntimeException unresolved) {
            return List.of();
        }
        List<Edge> edges = new ArrayList<>();
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                if (dx + 1 < side && denied[dx][dz] != denied[dx + 1][dz]) {
                    edges.add(new Edge(x + 1, z, x + 1, z + 1));
                }
                if (dz + 1 < side && denied[dx][dz] != denied[dx][dz + 1]) {
                    edges.add(new Edge(x, z + 1, x + 1, z + 1));
                }
            }
        }
        return List.copyOf(edges);
    }
}
