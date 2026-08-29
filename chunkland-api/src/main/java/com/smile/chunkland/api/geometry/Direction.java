package com.smile.chunkland.api.geometry;

/**
 * One of the four orthogonal sides of a chunk.
 *
 * <p>Coordinates follow the Minecraft chunk grid convention, where {@code x} increases
 * to the east and {@code z} increases to the south (matching
 * {@link com.smile.chunkland.api.land.ChunkKey}). Each direction carries the unit
 * offset to the adjacent chunk across that edge:
 * <ul>
 *   <li>{@link #NORTH} &mdash; toward {@code -z} (dz = -1)</li>
 *   <li>{@link #EAST}  &mdash; toward {@code +x} (dx = +1)</li>
 *   <li>{@link #SOUTH} &mdash; toward {@code +z} (dz = +1)</li>
 *   <li>{@link #WEST}  &mdash; toward {@code -x} (dx = -1)</li>
 * </ul>
 *
 * <p>Diagonal contact is never a boundary: only these four orthogonal directions are
 * considered. {@link #opposite()} returns the direction facing back across the shared
 * edge, which is the side a neighboring chunk would use to describe the same physical
 * boundary line from its own face.
 */
public enum Direction {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    private final int dx;
    private final int dz;

    Direction(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** Unit x-offset to the adjacent chunk across this edge. */
    public int dx() {
        return dx;
    }

    /** Unit z-offset to the adjacent chunk across this edge. */
    public int dz() {
        return dz;
    }

    /** The direction facing back across the shared edge. */
    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case EAST -> WEST;
            case SOUTH -> NORTH;
            case WEST -> EAST;
        };
    }
}
