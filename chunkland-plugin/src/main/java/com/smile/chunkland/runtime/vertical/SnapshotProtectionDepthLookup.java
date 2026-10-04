package com.smile.chunkland.runtime.vertical;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.runtime.api.ProtectionDepthLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Production {@link ProtectionDepthLookup} over the immutable snapshot.
 *
 * <p>Effective depth per chunk is {@code stored} under
 * {@code PER_CHUNK_DEPTH} and {@code worldMinHeight} under
 * {@code FULL_HEIGHT}; stored values are never rewritten by a mode switch.
 * The per-land {@link #getProtectionDepth} answer is the minimum effective
 * depth across the land's chunks (the deepest, most protective plane), or the
 * world minimum under {@code FULL_HEIGHT}. Missing stored rows resolve
 * through {@link VerticalDepths#LEGACY_STORED_FALLBACK_Y}.
 *
 * <p>Mode and world heights are injected seams: the lookup never reads Bukkit,
 * SQL or the network. Worlds the mode seam cannot resolve use the injected
 * unknown-world mode: production passes {@code PER_CHUNK_DEPTH} for the
 * normal config path (stored depths stay authoritative) and
 * {@code FULL_HEIGHT} while the conservative startup fallback is active, so
 * a world the damaged config never listed cannot shrink protection. The
 * constructor without an explicit unknown mode keeps the historical
 * {@code PER_CHUNK_DEPTH} behaviour.
 */
public final class SnapshotProtectionDepthLookup implements ProtectionDepthLookup {

    private final Function<UUID, VerticalMode> modeByWorld;
    private final ToIntFunction<UUID> worldMinByWorld;
    private final VerticalMode unknownMode;

    public SnapshotProtectionDepthLookup(
            Function<UUID, VerticalMode> modeByWorld,
            ToIntFunction<UUID> worldMinByWorld) {
        this(modeByWorld, worldMinByWorld, VerticalMode.defaultMode());
    }

    /**
     * Lookup with an explicit fallback mode for worlds the mode seam cannot
     * resolve (missing entry, {@code null} answer or throwing seam).
     *
     * @param unknownMode mode used when the seam has no answer; never null
     */
    public SnapshotProtectionDepthLookup(
            Function<UUID, VerticalMode> modeByWorld,
            ToIntFunction<UUID> worldMinByWorld,
            VerticalMode unknownMode) {
        this.modeByWorld = Objects.requireNonNull(modeByWorld, "modeByWorld");
        this.worldMinByWorld = Objects.requireNonNull(worldMinByWorld, "worldMinByWorld");
        this.unknownMode = Objects.requireNonNull(unknownMode, "unknownMode");
    }

    /** Fixed-mode lookup for tests and single-world wiring. */
    public static SnapshotProtectionDepthLookup fixed(VerticalMode mode, int worldMinHeight) {
        Objects.requireNonNull(mode, "mode");
        return new SnapshotProtectionDepthLookup(ignored -> mode, ignored -> worldMinHeight);
    }

    @Override
    public Optional<Integer> getProtectionDepth(LandId landId, LandRegistry snapshot) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(snapshot, "snapshot");
        LandSnapshot land = snapshot.land(landId);
        if (land == null) {
            return Optional.empty();
        }
        UUID worldId = land.worldId();
        VerticalMode mode = modeOrDefault(worldId);
        int worldMin = worldMinByWorld.applyAsInt(worldId);
        if (mode == VerticalMode.FULL_HEIGHT) {
            return Optional.of(worldMin);
        }
        int floor = Integer.MAX_VALUE;
        boolean seen = false;
        for (ChunkKey chunk : land.chunks()) {
            int stored = snapshot.storedDepth(chunk)
                    .orElse(VerticalDepths.LEGACY_STORED_FALLBACK_Y);
            if (!seen || stored < floor) {
                floor = stored;
                seen = true;
            }
        }
        if (!seen) {
            return Optional.of(VerticalDepths.LEGACY_STORED_FALLBACK_Y);
        }
        return Optional.of(floor);
    }

    /**
     * Effective depth for one chunk in the given snapshot.
     * Empty only for wilderness (no owning land).
     */
    public Optional<Integer> getEffectiveDepth(ChunkKey chunk, LandRegistry snapshot) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.findLandId(chunk.worldId(), chunk.chunkX(), chunk.chunkZ()) == null) {
            return Optional.empty();
        }
        int stored = snapshot.storedDepth(chunk)
                .orElse(VerticalDepths.LEGACY_STORED_FALLBACK_Y);
        VerticalMode mode = modeOrDefault(chunk.worldId());
        int worldMin = worldMinByWorld.applyAsInt(chunk.worldId());
        return Optional.of(VerticalDepths.effectiveFor(mode, stored, worldMin));
    }

    /**
     * Stored depth for one chunk in the given snapshot.
     * Empty only for wilderness; otherwise the authoritative persisted value
     * (legacy fallback applied). Never affected by the vertical mode.
     */
    public Optional<Integer> getStoredDepth(ChunkKey chunk, LandRegistry snapshot) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.findLandId(chunk.worldId(), chunk.chunkX(), chunk.chunkZ()) == null) {
            return Optional.empty();
        }
        return Optional.of(snapshot.storedDepth(chunk)
                .orElse(VerticalDepths.LEGACY_STORED_FALLBACK_Y));
    }

    private VerticalMode modeOrDefault(UUID worldId) {
        try {
            VerticalMode mode = modeByWorld.apply(worldId);
            return mode == null ? unknownMode : mode;
        } catch (RuntimeException failure) {
            return unknownMode;
        }
    }
}
