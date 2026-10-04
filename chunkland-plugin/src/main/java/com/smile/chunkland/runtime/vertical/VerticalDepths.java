package com.smile.chunkland.runtime.vertical;

import com.smile.chunkland.config.VerticalMode;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure stored vs effective depth rules.
 *
 * <p>{@code storedMinProtectedY} is the authoritative persisted value per
 * chunk. {@code effectiveMinProtectedY} is the runtime interpretation and is
 * never persisted. Switching {@link VerticalMode} only changes the
 * interpretation; it never migrates or rewrites stored values.
 *
 * <p>No Bukkit, SQL or I/O here: callers supply the stored value, the mode
 * and the world minimum height. World heights are injected by the caller so
 * this unit never loads a World or Chunk.
 */
public final class VerticalDepths {

    /**
     * Safe fallback for historical nullable {@code stored_min_protected_y}
     * rows (pre-depth claims and legacy backfills). Matches the claim-path
     * fallback so old rows keep the same protection they were claimed with.
     */
    public static final int LEGACY_STORED_FALLBACK_Y = 64;

    private VerticalDepths() {
    }

    /**
     * Normalize a possibly-null durable stored value.
     *
     * @param stored nullable value read from {@code land_chunks}
     * @return the stored value, or {@link #LEGACY_STORED_FALLBACK_Y} when null
     */
    public static int normalizeStored(Integer stored) {
        return stored == null ? LEGACY_STORED_FALLBACK_Y : stored.intValue();
    }

    /**
     * Resolve the effective depth for one chunk.
     *
     * @param mode effective interpretation for the chunk's world
     * @param storedMinProtectedY authoritative persisted value (already normalized)
     * @param worldMinHeight world minimum block Y used only for {@code FULL_HEIGHT}
     */
    public static int effectiveFor(VerticalMode mode, int storedMinProtectedY, int worldMinHeight) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case PER_CHUNK_DEPTH -> storedMinProtectedY;
            case FULL_HEIGHT -> worldMinHeight;
        };
    }

    /**
     * Depth-extend proposal seam for the Auto Extend follow-up ({taskId}).
     *
     * <p>Computes {@code newStored = max(worldMin, operationY - buffer)} and
     * returns it only when it extends strictly deeper than {@code currentStored}.
     * The proposal is mode-independent: even under {@code FULL_HEIGHT} the
     * stored value keeps tracking downward development so switching back to
     * {@code PER_CHUNK_DEPTH} restores the accumulated depths. This method
     * performs no persistence and no CAS; the queue lives in the follow-up.
     *
     * @param currentStored current persisted depth (already normalized)
     * @param operationY block Y of the legal break/place operation
     * @param buffer non-negative auto-extend buffer subtracted from the operation plane
     * @param worldMinHeight inclusive world minimum (lower clamp)
     * @return the deeper stored value to persist, or empty when no extend is needed
     * @throws IllegalArgumentException if {@code buffer < 0}
     */
    public static Optional<Integer> proposeExtend(
            int currentStored, int operationY, int buffer, int worldMinHeight) {
        if (buffer < 0) {
            throw new IllegalArgumentException("buffer must be >= 0: " + buffer);
        }
        long requested = (long) operationY - (long) buffer;
        int clamped = requested < worldMinHeight ? worldMinHeight : (int) Math.min(requested, Integer.MAX_VALUE);
        if (clamped < currentStored) {
            return Optional.of(clamped);
        }
        return Optional.empty();
    }
}
