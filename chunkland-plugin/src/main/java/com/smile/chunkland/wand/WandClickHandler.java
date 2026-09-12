package com.smile.chunkland.wand;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * Seam for future selection handling.
 * Default no-op, must not create selection state.
 * Provides short-lived click context (player/block/face) without retaining.
 */
public interface WandClickHandler {

    record Context(Player player, Block block, BlockFace face, boolean isBreak) {}

    void onWandUse(Context context);

    /**
     * Right-click hit with the wand. Dormant by default so the safety-only
     * wiring keeps ignoring right clicks until a selecting handler is fitted.
     */
    default void onWandRightClick(Player player, Block block, BlockFace face) {
    }

    /**
     * The main hand transitioned from a non-wand item to the wand. Dormant by
     * default; a selecting handler may start guiding a fresh range here.
     */
    default void onWandEquipped(Player player) {
    }

    /**
     * The main hand transitioned from the wand to a non-wand item. Dormant by
     * default; a selecting handler may drop any live range here.
     */
    default void onWandUnequipped(Player player) {
    }

    static WandClickHandler noop() {
        return context -> {};
    }

    /** Backwards-compatible overload for tests that only care about player. */
    default void onWandUse(Player player) {
        onWandUse(new Context(player, null, null, false));
    }
}
