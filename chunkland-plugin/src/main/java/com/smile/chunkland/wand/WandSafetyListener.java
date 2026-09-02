package com.smile.chunkland.wand;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Prevents a held selection wand from damaging or breaking blocks.
 * Fail-closed on any PDC or callback exception.
 */
public final class WandSafetyListener implements Listener {

    private final WandClickHandler clickHandler;

    public WandSafetyListener(WandClickHandler clickHandler) {
        this.clickHandler = clickHandler == null ? WandClickHandler.noop() : clickHandler;
    }

    public WandSafetyListener() {
        this(WandClickHandler.noop());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            ItemStack inHand = event.getItemInHand();
            // Fallback to player's main hand if event item is null (some versions)
            if (inHand == null) {
                Player p = event.getPlayer();
                if (p != null && p.getInventory() != null) {
                    inHand = p.getInventory().getItemInMainHand();
                }
            }
            boolean isWand;
            try {
                isWand = WandIdentity.isWand(inHand);
            } catch (RuntimeException ex) {
                // Fail closed: malformed PDC must cancel
                event.setCancelled(true);
                return;
            }
            if (!isWand) {
                return;
            }
            event.setCancelled(true);
            try {
                Block block = event.getBlock();
                BlockFace face = null;
                try {
                    face = event.getBlockFace();
                } catch (RuntimeException ignored) {
                }
                clickHandler.onWandUse(new WandClickHandler.Context(event.getPlayer(), block, face, false));
            } catch (RuntimeException ignored) {
                // click seam failures remain cancelled
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            Player p = event.getPlayer();
            if (p == null || p.getInventory() == null) {
                return;
            }
            ItemStack inHand = p.getInventory().getItemInMainHand();
            boolean isWand;
            try {
                isWand = WandIdentity.isWand(inHand);
            } catch (RuntimeException ex) {
                event.setCancelled(true);
                return;
            }
            if (!isWand) {
                return;
            }
            event.setCancelled(true);
            try {
                clickHandler.onWandUse(new WandClickHandler.Context(p, event.getBlock(), null, true));
            } catch (RuntimeException ignored) {
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
