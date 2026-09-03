package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

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
 *
 * <p>Right-click interaction on a block maps to one subject action by block
 * kind: storage blocks to {@code CONTAINER_OPEN}, crafting and processing
 * blocks to {@code WORKSTATION_USE}, doors, trapdoors, and fence gates to
 * {@code DOOR_USE}, buttons to {@code BUTTON_USE}, and levers to
 * {@code LEVER_USE}. Anything else stays vanilla. Stepping onto farmland
 * ({@code PHYSICAL} on soil, by foot or by mob) decides as
 * {@code FARMLAND_TRAMPLE}. World mechanics without a player actor (pistons,
 * fluids, hoppers, explosions, fire) decide under a fixed
 * environmental actor, which can never match a land owner.
 *
 * <p>Movement into a land decides as {@code ENTRY} at the destination, but
 * only when the chunk changes: walking inside one chunk never consults the
 * engine. Teleports always check the destination. Vehicles decide as
 * {@code VEHICLE_USE} (entering and player damage); item frames as
 * {@code ITEM_FRAME}, armor stands as {@code ARMOR_STAND}, and other
 * hangings as {@code HANGING_ENTITY}. Non-player actors on those paths stay
 * vanilla here; later milestones own them.
 */
public final class ProtectionListener implements Listener {

    /**
     * Fixed actor for world mechanics with no player cause. It never equals a
     * real owner, so the subject owner guarantee cannot rescue these paths and
     * rule actions decide purely from their rule source.
     */
    static final UUID ENVIRONMENT_ACTOR = new UUID(0L, 0L);

    private static final Set<Material> CONTAINER_TYPES = EnumSet.of(
            Material.CHEST,
            Material.TRAPPED_CHEST,
            Material.BARREL,
            Material.ENDER_CHEST,
            Material.HOPPER);

    private static final Set<Material> WORKSTATION_TYPES = EnumSet.of(
            Material.CRAFTING_TABLE,
            Material.FURNACE,
            Material.BLAST_FURNACE,
            Material.SMOKER,
            Material.ENCHANTING_TABLE,
            Material.BREWING_STAND,
            Material.LOOM,
            Material.CARTOGRAPHY_TABLE,
            Material.STONECUTTER,
            Material.SMITHING_TABLE,
            Material.GRINDSTONE,
            Material.BEACON,
            Material.ANVIL,
            Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL);

    private static final Set<Material> SHULKER_TYPES = EnumSet.of(
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX);

    private static final Set<Material> DOOR_TYPES = EnumSet.of(
            Material.OAK_DOOR,
            Material.SPRUCE_DOOR,
            Material.BIRCH_DOOR,
            Material.JUNGLE_DOOR,
            Material.ACACIA_DOOR,
            Material.DARK_OAK_DOOR,
            Material.MANGROVE_DOOR,
            Material.CHERRY_DOOR,
            Material.BAMBOO_DOOR,
            Material.CRIMSON_DOOR,
            Material.WARPED_DOOR,
            Material.PALE_OAK_DOOR,
            Material.IRON_DOOR,
            Material.COPPER_DOOR,
            Material.EXPOSED_COPPER_DOOR,
            Material.WEATHERED_COPPER_DOOR,
            Material.OXIDIZED_COPPER_DOOR,
            Material.WAXED_COPPER_DOOR,
            Material.WAXED_EXPOSED_COPPER_DOOR,
            Material.WAXED_WEATHERED_COPPER_DOOR,
            Material.WAXED_OXIDIZED_COPPER_DOOR,
            Material.OAK_TRAPDOOR,
            Material.SPRUCE_TRAPDOOR,
            Material.BIRCH_TRAPDOOR,
            Material.JUNGLE_TRAPDOOR,
            Material.ACACIA_TRAPDOOR,
            Material.DARK_OAK_TRAPDOOR,
            Material.MANGROVE_TRAPDOOR,
            Material.CHERRY_TRAPDOOR,
            Material.BAMBOO_TRAPDOOR,
            Material.CRIMSON_TRAPDOOR,
            Material.WARPED_TRAPDOOR,
            Material.PALE_OAK_TRAPDOOR,
            Material.IRON_TRAPDOOR,
            Material.COPPER_TRAPDOOR,
            Material.EXPOSED_COPPER_TRAPDOOR,
            Material.WEATHERED_COPPER_TRAPDOOR,
            Material.OXIDIZED_COPPER_TRAPDOOR,
            Material.WAXED_COPPER_TRAPDOOR,
            Material.WAXED_EXPOSED_COPPER_TRAPDOOR,
            Material.WAXED_WEATHERED_COPPER_TRAPDOOR,
            Material.WAXED_OXIDIZED_COPPER_TRAPDOOR,
            Material.OAK_FENCE_GATE,
            Material.SPRUCE_FENCE_GATE,
            Material.BIRCH_FENCE_GATE,
            Material.JUNGLE_FENCE_GATE,
            Material.ACACIA_FENCE_GATE,
            Material.DARK_OAK_FENCE_GATE,
            Material.MANGROVE_FENCE_GATE,
            Material.CHERRY_FENCE_GATE,
            Material.BAMBOO_FENCE_GATE,
            Material.CRIMSON_FENCE_GATE,
            Material.WARPED_FENCE_GATE,
            Material.PALE_OAK_FENCE_GATE);

    private static final Set<Material> BUTTON_TYPES = EnumSet.of(
            Material.OAK_BUTTON,
            Material.SPRUCE_BUTTON,
            Material.BIRCH_BUTTON,
            Material.JUNGLE_BUTTON,
            Material.ACACIA_BUTTON,
            Material.DARK_OAK_BUTTON,
            Material.MANGROVE_BUTTON,
            Material.CHERRY_BUTTON,
            Material.BAMBOO_BUTTON,
            Material.CRIMSON_BUTTON,
            Material.WARPED_BUTTON,
            Material.PALE_OAK_BUTTON,
            Material.STONE_BUTTON,
            Material.POLISHED_BLACKSTONE_BUTTON);

    private final ProtectionEngine engine;

    public ProtectionListener(ProtectionEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Maps a right-clicked block kind to its subject action, or {@code null}
     * when the kind is not protected and the interaction stays vanilla.
     *
     * <p>The sets above are explicit on purpose: the server tag registry is
     * unavailable without a running server, so the hot path classifies from
     * its own constants and never touches it.
     */
    static ProtectionActionType interactAction(Material type) {
        if (type == null) {
            return null;
        }
        if (CONTAINER_TYPES.contains(type) || SHULKER_TYPES.contains(type)) {
            return ProtectionActionType.CONTAINER_OPEN;
        }
        if (WORKSTATION_TYPES.contains(type)) {
            return ProtectionActionType.WORKSTATION_USE;
        }
        if (DOOR_TYPES.contains(type)) {
            return ProtectionActionType.DOOR_USE;
        }
        if (BUTTON_TYPES.contains(type)) {
            return ProtectionActionType.BUTTON_USE;
        }
        if (type == Material.LEVER) {
            return ProtectionActionType.LEVER_USE;
        }
        return null;
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
            ProtectionActionType action;
            if (victim instanceof ArmorStand) {
                action = ProtectionActionType.ARMOR_STAND;
            } else if (victim instanceof Player) {
                action = ProtectionActionType.PLAYER_DAMAGE_PLAYER;
            } else {
                action = ProtectionActionType.ENTITY_DAMAGE;
            }
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        try {
            Player player = event.getPlayer();
            Block placed = event.getBlockPlaced();
            if (player == null || placed == null || placed.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtBlock(player.getUniqueId(), placed, ProtectionActionType.BLOCK_PLACE)) {
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
    public void onPlayerInteract(PlayerInteractEvent event) {
        try {
            if (event.getAction() == Action.PHYSICAL) {
                Block soil = event.getClickedBlock();
                if (soil == null || soil.getType() != Material.FARMLAND) {
                    return;
                }
                Player player = event.getPlayer();
                if (player == null || soil.getWorld() == null) {
                    event.setCancelled(true);
                    return;
                }
                if (deniedAtBlock(player.getUniqueId(), soil,
                        ProtectionActionType.FARMLAND_TRAMPLE)) {
                    event.setCancelled(true);
                }
                return;
            }
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.hasBlock()) {
                return;
            }
            Block clicked = event.getClickedBlock();
            if (clicked == null) {
                return;
            }
            ProtectionActionType action = interactAction(clicked.getType());
            if (action == null) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null || clicked.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtBlock(player.getUniqueId(), clicked, action)) {
                event.setCancelled(true);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // PlayerBucketEvent itself has no handler list, so Bukkit cannot register
    // it: fill and empty are separate concrete events sharing one check.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        handleBucketUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        handleBucketUse(event);
    }

    private void handleBucketUse(PlayerBucketEvent event) {
        try {
            Player player = event.getPlayer();
            Block block = event.getBlockClicked();
            if (block == null) {
                block = event.getBlock();
            }
            if (player == null || block == null || block.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtBlock(player.getUniqueId(), block, ProtectionActionType.BUCKET_USE)) {
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
    public void onPistonExtend(BlockPistonExtendEvent event) {
        try {
            if (pistonMoveDenied(event.getBlocks(), event.getDirection(), false)) {
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
    public void onPistonRetract(BlockPistonRetractEvent event) {
        try {
            if (pistonMoveDenied(event.getBlocks(), event.getDirection(), true)) {
                event.setCancelled(true);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Checks every moved block at its current position and at its destination.
     * Extend pushes along the reported facing ({@code current + direction});
     * retract pulls back toward the piston ({@code current - direction}): the
     * pulled block sits two steps out ({@code piston + 2 * direction}) and
     * comes to rest one step closer. Offsets are plain arithmetic on
     * coordinates already in the event, so no chunk is loaded and no region
     * is crossed.
     *
     * @return {@code true} when the move must be cancelled (fail-closed on any
     *         missing block, world, or direction)
     */
    private boolean pistonMoveDenied(List<Block> moved, BlockFace direction, boolean retract) {
        if (moved == null || direction == null) {
            return true;
        }
        int sign = retract ? -1 : 1;
        for (Block block : moved) {
            if (block == null || block.getWorld() == null) {
                return true;
            }
            if (deniedAtBlock(ENVIRONMENT_ACTOR, block, ProtectionActionType.PISTON_MOVE)) {
                return true;
            }
            if (deniedAt(ENVIRONMENT_ACTOR, block.getWorld(),
                    block.getX() + sign * direction.getModX(),
                    block.getZ() + sign * direction.getModZ(),
                    ProtectionActionType.PISTON_MOVE)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        try {
            Block from = event.getBlock();
            Block to = event.getToBlock();
            if (from == null || from.getWorld() == null
                    || to == null || to.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtBlock(ENVIRONMENT_ACTOR, from, ProtectionActionType.FLUID_FLOW)
                    || deniedAtBlock(ENVIRONMENT_ACTOR, to, ProtectionActionType.FLUID_FLOW)) {
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
    public void onHopperTransfer(InventoryMoveItemEvent event) {
        try {
            Location source = locationOf(event.getSource());
            Location destination = locationOf(event.getDestination());
            if (source == null || source.getWorld() == null
                    || destination == null || destination.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtLocation(ENVIRONMENT_ACTOR, source, ProtectionActionType.HOPPER_TRANSFER)
                    || deniedAtLocation(ENVIRONMENT_ACTOR, destination,
                            ProtectionActionType.HOPPER_TRANSFER)) {
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
    public void onEntityExplode(EntityExplodeEvent event) {
        try {
            List<Block> affected = event.blockList();
            affected.removeIf(block -> {
                if (block == null || block.getWorld() == null) {
                    return true;
                }
                try {
                    return deniedAtBlock(ENVIRONMENT_ACTOR, block,
                            ProtectionActionType.EXPLOSION_TERRAIN);
                } catch (RuntimeException ex) {
                    return true;
                }
            });
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        try {
            List<Block> affected = event.blockList();
            affected.removeIf(block -> {
                if (block == null || block.getWorld() == null) {
                    return true;
                }
                try {
                    return deniedAtBlock(ENVIRONMENT_ACTOR, block,
                            ProtectionActionType.EXPLOSION_TERRAIN);
                } catch (RuntimeException ex) {
                    return true;
                }
            });
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionEntityDamage(EntityDamageEvent event) {
        try {
            var cause = event.getCause();
            if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                    && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                return;
            }
            Entity victim = event.getEntity();
            if (victim == null || victim.getLocation() == null
                    || victim.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtLocation(ENVIRONMENT_ACTOR, victim.getLocation(),
                    ProtectionActionType.EXPLOSION_ENTITY)) {
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
    public void onPlayerMove(PlayerMoveEvent event) {
        try {
            Player player = event.getPlayer();
            Location from = event.getFrom();
            Location to = event.getTo();
            if (player == null || to == null || to.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (from != null && from.getWorld() != null
                    && from.getWorld().getUID().equals(to.getWorld().getUID())
                    && (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                    && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
                return;
            }
            if (deniedAtLocation(player.getUniqueId(), to, ProtectionActionType.ENTRY)) {
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
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        try {
            Player player = event.getPlayer();
            Location to = event.getTo();
            if (player == null || to == null || to.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtLocation(player.getUniqueId(), to, ProtectionActionType.ENTRY)) {
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
    public void onVehicleEnter(VehicleEnterEvent event) {
        try {
            if (!(event.getEntered() instanceof Player player)) {
                return;
            }
            Vehicle vehicle = event.getVehicle();
            if (vehicle == null || vehicle.getLocation() == null
                    || vehicle.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtLocation(player.getUniqueId(), vehicle.getLocation(),
                    ProtectionActionType.VEHICLE_USE)) {
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
    public void onVehicleDamage(VehicleDamageEvent event) {
        try {
            if (!(event.getAttacker() instanceof Player player)) {
                return;
            }
            Vehicle vehicle = event.getVehicle();
            if (vehicle == null || vehicle.getLocation() == null
                    || vehicle.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtLocation(player.getUniqueId(), vehicle.getLocation(),
                    ProtectionActionType.VEHICLE_USE)) {
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
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        try {
            Entity clicked = event.getRightClicked();
            ProtectionActionType action;
            if (clicked instanceof ItemFrame) {
                action = ProtectionActionType.ITEM_FRAME;
            } else if (clicked instanceof ArmorStand) {
                action = ProtectionActionType.ARMOR_STAND;
            } else {
                return;
            }
            Player player = event.getPlayer();
            if (player == null || clicked.getLocation() == null
                    || clicked.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtLocation(player.getUniqueId(), clicked.getLocation(), action)) {
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
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        try {
            Player player = event.getPlayer();
            ArmorStand stand = event.getRightClicked();
            if (player == null || stand == null || stand.getLocation() == null
                    || stand.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtLocation(player.getUniqueId(), stand.getLocation(),
                    ProtectionActionType.ARMOR_STAND)) {
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
    public void onHangingPlace(HangingPlaceEvent event) {
        try {
            Player player = event.getPlayer();
            Hanging hanging = event.getEntity();
            Block support = event.getBlock();
            Location at = hanging != null ? hanging.getLocation() : null;
            if (at == null && support != null) {
                at = support.getLocation();
            }
            if (player == null || hanging == null || at == null || at.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            ProtectionActionType action = (hanging instanceof ItemFrame)
                    ? ProtectionActionType.ITEM_FRAME
                    : ProtectionActionType.HANGING_ENTITY;
            if (deniedAtLocation(player.getUniqueId(), at, action)) {
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
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        try {
            if (!(event.getRemover() instanceof Player player)) {
                return;
            }
            Hanging hanging = event.getEntity();
            if (hanging == null || hanging.getLocation() == null
                    || hanging.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            ProtectionActionType action = (hanging instanceof ItemFrame)
                    ? ProtectionActionType.ITEM_FRAME
                    : ProtectionActionType.HANGING_ENTITY;
            if (deniedAtLocation(player.getUniqueId(), hanging.getLocation(), action)) {
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
    public void onEntityInteract(EntityInteractEvent event) {
        try {
            Block block = event.getBlock();
            if (block == null || block.getType() != Material.FARMLAND) {
                return;
            }
            Entity entity = event.getEntity();
            if (entity == null || block.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtBlock(entity.getUniqueId(), block,
                    ProtectionActionType.FARMLAND_TRAMPLE)) {
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
    public void onFireSpread(BlockSpreadEvent event) {
        try {
            Block target = event.getBlock();
            Block source = event.getSource();
            if (target == null || target.getWorld() == null
                    || source == null || source.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            // BlockSpreadEvent also fires for grass, mushrooms and vines: only flame
            // spreading (fire block at the source, or fire as the spread result)
            // belongs to FIRE_SPREAD. Anything else stays vanilla.
            BlockState newState = event.getNewState();
            boolean sourceIsFire = source.getType() == Material.FIRE;
            boolean resultIsFire = newState != null && newState.getType() == Material.FIRE;
            if (!sourceIsFire && !resultIsFire) {
                return;
            }
            if (deniedAtBlock(ENVIRONMENT_ACTOR, source, ProtectionActionType.FIRE_SPREAD)
                    || deniedAtBlock(ENVIRONMENT_ACTOR, target, ProtectionActionType.FIRE_SPREAD)) {
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
    public void onFireBurn(BlockBurnEvent event) {
        try {
            Block block = event.getBlock();
            if (block == null || block.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtBlock(ENVIRONMENT_ACTOR, block, ProtectionActionType.FIRE_BURN)) {
                event.setCancelled(true);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean deniedAtBlock(UUID actor, Block block, ProtectionActionType action) {
        return deniedAt(actor, block.getWorld(), block.getX(), block.getZ(), action);
    }

    private boolean deniedAtLocation(UUID actor, Location location, ProtectionActionType action) {
        return deniedAt(actor, location.getWorld(), location.getBlockX(), location.getBlockZ(), action);
    }

    private boolean deniedAt(UUID actor, World world, int blockX, int blockZ,
                             ProtectionActionType action) {
        return engine.decideAt(actor, world.getUID(), blockX >> 4, blockZ >> 4, action).outcome()
                == PermissionState.DENY;
    }

    private static Location locationOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        try {
            Location direct = inventory.getLocation();
            if (direct != null) {
                return direct;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockState state) {
            return state.getLocation();
        }
        if (holder instanceof Entity entity) {
            return entity.getLocation();
        }
        return null;
    }
}
