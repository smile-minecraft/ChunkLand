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

    static WandClickHandler noop() {
        return context -> {};
    }

    /** Backwards-compatible overload for tests that only care about player. */
    default void onWandUse(Player player) {
        onWandUse(new Context(player, null, null, false));
    }
}
