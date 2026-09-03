package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Objects;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Native Bukkit enforcement skeleton for the protection engine.
 *
 * <p>Each handler runs at an explicit priority, consults
 * {@link ProtectionEngine} once, and cancels on {@code DENY}. Position is
 * derived from coordinates already on the event, so nothing here waits on or
 * fetches remote state. Any failure cancels the event (fail-closed).
 *
 * <p>Routing split: a player harming a player decides as
 * {@code PLAYER_DAMAGE_PLAYER} (rule path); a player harming anything else
 * decides as {@code ENTITY_DAMAGE} (subject path). Damage without a player
 * attacker stays vanilla here; later milestones own that path.
 */
public final class ProtectionListener implements Listener {

    private final ProtectionEngine engine;

    public ProtectionListener(ProtectionEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        try {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            if (player == null || block == null || block.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            var decision = engine.decideAt(
                    player.getUniqueId(),
                    block.getWorld().getUID(),
                    block.getX() >> 4,
                    block.getZ() >> 4,
                    ProtectionActionType.BLOCK_BREAK);
            if (decision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getDamager() instanceof Player damager)) {
                return;
            }
            Entity victim = event.getEntity();
            if (victim == null || victim.getLocation() == null
                    || victim.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            ProtectionActionType action = (victim instanceof Player)
                    ? ProtectionActionType.PLAYER_DAMAGE_PLAYER
                    : ProtectionActionType.ENTITY_DAMAGE;
            var location = victim.getLocation();
            var decision = engine.decideAt(
                    damager.getUniqueId(),
                    location.getWorld().getUID(),
                    location.getBlockX() >> 4,
                    location.getBlockZ() >> 4,
                    action);
            if (decision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
