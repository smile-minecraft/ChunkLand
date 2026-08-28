package com.smile.chunkland.api.land;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable coordinate of a single chunk within a world.
 *
 * <p>Chunk coordinates may be negative (Minecraft allows negative chunk
 * coordinates). The {@link #pack()} / {@link #unpack(UUID, long)} helpers mirror
 * the runtime packed-long representation documented in the spec (§5):
 * {@code ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL)}. The world id is
 * resolved separately by the runtime index, so it is intentionally not part of
 * the packed value.
 *
 * <p>Thread-safe value object.
 */
public record ChunkKey(UUID worldId, int chunkX, int chunkZ) {

    public ChunkKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** Pack chunk coordinates into the runtime packed-long representation. */
    public long pack() {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Reconstruct a {@code ChunkKey} for the given world from a packed value. */
    public static ChunkKey unpack(UUID worldId, long packed) {
        Objects.requireNonNull(worldId, "worldId");
        int chunkX = (int) (packed >>> 32);
        int chunkZ = (int) (packed & 0xFFFFFFFFL);
        return new ChunkKey(worldId, chunkX, chunkZ);
    }
}
