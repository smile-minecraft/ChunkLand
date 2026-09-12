package com.smile.chunkland.wand;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Prevents a held selection wand from damaging or breaking blocks.
 * Selection enters only through block interact (left/right, main hand);
 * damage/break stay safety-cancel only because Creative left clicks do not
 * reliably raise damage events while Survival raises both for one hit.
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
            // Safety-cancel only: the interact path already recorded this hit,
            // so calling selection here would echo the same corner twice.
            event.setCancelled(true);
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            org.bukkit.event.block.Action action = event.getAction();
            boolean isLeft = action == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK;
            boolean isRight = action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
            if (!isLeft && !isRight) {
                return;
            }
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            Block block = event.getClickedBlock();
            if (block == null) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            ItemStack inHand = event.getItem();
            if (inHand == null && player.getInventory() != null) {
                inHand = player.getInventory().getItemInMainHand();
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
                BlockFace face = null;
                try {
                    face = event.getBlockFace();
                } catch (RuntimeException ignored) {
                }
                if (isLeft) {
                    clickHandler.onWandUse(new WandClickHandler.Context(player, block, face, false));
                } else {
                    clickHandler.onWandRightClick(player, block, face);
                }
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
            // Safety-cancel only: the interact path already recorded this hit,
            // so calling selection here would echo the same corner twice.
            event.setCancelled(true);
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Hotbar slot change: a genuine non-wand → wand transition guides a fresh
     * range, a wand → non-wand transition drops the old one. Moving between two
     * wand slots is not a transition and stays silent. Only the two slots'
     * items are read; no terrain, world, or chunk object is touched.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            int previous = event.getPreviousSlot();
            int next = event.getNewSlot();
            if (previous == next) {
                return;
            }
            PlayerInventory inventory = player.getInventory();
            if (inventory == null) {
                return;
            }
            applyHandTransition(
                    player,
                    isWandQuiet(inventory.getItem(previous)),
                    isWandQuiet(inventory.getItem(next)));
        } catch (RuntimeException ignored) {
            // A lifecycle miss must never break the item event.
        }
    }

    /**
     * Off-hand swap: the off-hand item moves into the main hand, so the
     * transition is decided from the two stacks the event already carries.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            applyHandTransition(
                    player,
                    isWandQuiet(event.getMainHandItem()),
                    isWandQuiet(event.getOffHandItem()));
        } catch (RuntimeException ignored) {
            // A lifecycle miss must never break the item event.
        }
    }

    private void applyHandTransition(Player player, boolean wasWand, boolean nowWand) {
        if (nowWand && !wasWand) {
            clickHandler.onWandEquipped(player);
        } else if (!nowWand && wasWand) {
            clickHandler.onWandUnequipped(player);
        }
    }

    /**
     * Identity probe that treats an unreadable item as "not a wand". The
     * lifecycle path must never throw or wrongly clear a live range, so a
     * malformed stack leaves the transition undecided instead of guessing.
     */
    private static boolean isWandQuiet(ItemStack stack) {
        try {
            return WandIdentity.isWand(stack);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
