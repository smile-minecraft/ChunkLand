package com.smile.chunkland.protection;

import java.util.List;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * Player-scoped particle shapes for deny feedback.
 *
 * <p>Every shape is sent with the player's own {@code spawnParticle}, so only
 * the denied player sees it and no world or entity state is read. Callers run
 * these on the player's thread; a shape that cannot be drawn is dropped.
 */
public final class DenialParticles {

    /** Warning red shared by every deny shape. */
    public static final Color DENY_COLOR = Color.fromRGB(255, 45, 45);

    /** Dust size for the border wall; large enough to read as a solid line. */
    public static final float WALL_DUST_SIZE = 1.6F;

    /** Dust size for the block outline and cross. */
    public static final float MARK_DUST_SIZE = 1.1F;

    /** Points per block along a border segment. */
    public static final int WALL_POINTS_PER_BLOCK = 2;

    /** Heights of the wall rows above the player's feet. */
    private static final double[] WALL_ROWS = {0.1D, 0.9D, 1.7D, 2.5D};

    /** Points per block edge of the outline, end points included. */
    private static final int OUTLINE_POINTS = 5;

    /** Points per diagonal of the cross drawn on the facing side. */
    private static final int CROSS_POINTS = 7;

    /** Gap between the block surface and the shapes drawn on it. */
    private static final double SURFACE_GAP = 0.03D;

    private DenialParticles() {
    }

    /**
     * Draws the border as a wall of dust rows standing on {@code feetY}.
     *
     * @param edges border segments from {@link EntryBoundaryTracer}
     */
    public static void boundaryWall(Player player, List<EntryBoundaryTracer.Edge> edges,
                                    double feetY) {
        boundaryWall(player, edges, feetY, WALL_DUST_SIZE, WALL_POINTS_PER_BLOCK);
    }

    /**
     * Draws the border wall with an explicit dust size and density.
     *
     * @param dustSize dust size; non-positive falls back to the default
     * @param pointsPerBlock particles per block along the border; values
     *                       below one fall back to the default
     */
    public static void boundaryWall(Player player, List<EntryBoundaryTracer.Edge> edges,
                                    double feetY, float dustSize, int pointsPerBlock) {
        if (player == null || edges == null || edges.isEmpty()) {
            return;
        }
        float size = dustSize > 0.0F ? dustSize : WALL_DUST_SIZE;
        int points = pointsPerBlock >= 1 ? pointsPerBlock : WALL_POINTS_PER_BLOCK;
        Particle.DustOptions dust = new Particle.DustOptions(DENY_COLOR, size);
        for (EntryBoundaryTracer.Edge edge : edges) {
            for (int step = 0; step < points; step++) {
                double t = (step + 0.5D) / points;
                double x = edge.x1() + (edge.x2() - edge.x1()) * t;
                double z = edge.z1() + (edge.z2() - edge.z1()) * t;
                for (double row : WALL_ROWS) {
                    dust(player, x, feetY + row, z, dust);
                }
            }
        }
    }

    /**
     * Marks one denied target: a block gets its twelve edges outlined plus a
     * cross on the side facing the player; a free spot gets two rings.
     */
    public static void mark(Player player, ActionDenialParticleFeedback.Mark mark) {
        mark(player, mark, MARK_DUST_SIZE);
    }

    /**
     * Marks one denied target with an explicit dust size.
     *
     * @param dustSize dust size; non-positive falls back to the default
     */
    public static void mark(Player player, ActionDenialParticleFeedback.Mark mark,
                            float dustSize) {
        if (player == null || mark == null) {
            return;
        }
        if (player.getWorld() == null || !mark.worldId().equals(player.getWorld().getUID())) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(DENY_COLOR,
                dustSize > 0.0F ? dustSize : MARK_DUST_SIZE);
        if (!mark.block()) {
            ring(player, mark.x(), mark.y() + 0.2D, mark.z(), dust);
            ring(player, mark.x(), mark.y() + 1.1D, mark.z(), dust);
            return;
        }
        double minX = mark.x() - SURFACE_GAP;
        double minY = mark.y() - SURFACE_GAP;
        double minZ = mark.z() - SURFACE_GAP;
        double span = 1.0D + SURFACE_GAP * 2.0D;
        for (int i = 0; i < OUTLINE_POINTS; i++) {
            double t = span * i / (OUTLINE_POINTS - 1);
            for (int a = 0; a <= 1; a++) {
                for (int b = 0; b <= 1; b++) {
                    dust(player, minX + t, minY + span * a, minZ + span * b, dust);
                    dust(player, minX + span * a, minY + t, minZ + span * b, dust);
                    dust(player, minX + span * a, minY + span * b, minZ + t, dust);
                }
            }
        }
        cross(player, mark, dust);
    }

    /** Cross on the block side that faces the player's eyes. */
    private static void cross(Player player, ActionDenialParticleFeedback.Mark mark,
                              Particle.DustOptions dust) {
        Location eye = player.getEyeLocation();
        if (eye == null) {
            return;
        }
        double dx = eye.getX() - (mark.x() + 0.5D);
        double dy = eye.getY() - (mark.y() + 0.5D);
        double dz = eye.getZ() - (mark.z() + 0.5D);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        double out = 1.0D + SURFACE_GAP * 2.0D;
        for (int i = 0; i < CROSS_POINTS; i++) {
            double t = 0.15D + 0.7D * i / (CROSS_POINTS - 1);
            double u = 1.0D - t;
            if (ax >= ay && ax >= az) {
                double x = mark.x() + (dx >= 0 ? out - SURFACE_GAP : -SURFACE_GAP * 2.0D);
                dust(player, x, mark.y() + t, mark.z() + t, dust);
                dust(player, x, mark.y() + t, mark.z() + u, dust);
            } else if (ay >= az) {
                double y = mark.y() + (dy >= 0 ? out - SURFACE_GAP : -SURFACE_GAP * 2.0D);
                dust(player, mark.x() + t, y, mark.z() + t, dust);
                dust(player, mark.x() + t, y, mark.z() + u, dust);
            } else {
                double z = mark.z() + (dz >= 0 ? out - SURFACE_GAP : -SURFACE_GAP * 2.0D);
                dust(player, mark.x() + t, mark.y() + t, z, dust);
                dust(player, mark.x() + u, mark.y() + t, z, dust);
            }
        }
    }

    private static void ring(Player player, double x, double y, double z,
                             Particle.DustOptions dust) {
        int points = 12;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            dust(player, x + Math.cos(angle) * 0.6D, y, z + Math.sin(angle) * 0.6D, dust);
        }
    }

    private static void dust(Player player, double x, double y, double z,
                             Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D, dust);
    }
}
