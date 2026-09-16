package com.smile.chunkland.api.history;

/**
 * One redacted block-history row.
 *
 * <p>The entry carries coordinates, a coarse action label and the block
 * material only. It deliberately has no player name, raw database payload or
 * backend detail, so replies built from entries cannot leak attribution or
 * storage internals.
 */
public record HistoryEntry(int x,
                           int y,
                           int z,
                           String action,
                           String material,
                           long epochSecond) {

    public HistoryEntry {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must be non-blank");
        }
        if (material == null || material.isBlank()) {
            throw new IllegalArgumentException("material must be non-blank");
        }
        if (epochSecond < 0) {
            throw new IllegalArgumentException("epochSecond must be non-negative");
        }
    }

    /**
     * Single-line player-facing rendering. Coordinates, action and material
     * only — no attribution, no backend detail.
     */
    public String describe() {
        return x + "," + y + "," + z + " " + action + " " + material;
    }
}
