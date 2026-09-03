package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.message.rejection.RejectionNotifier;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
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
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;

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
 * engine. Teleports always check the destination, for every teleport cause.
 * ENTRY denies and banned-inside stops delegate to
 * {@link EntryProtectionAdapter} for a throttled push-out that never loads a
 * chunk. Vehicles decide as
 * {@code VEHICLE_USE} (entering and player damage); item frames as
 * {@code ITEM_FRAME}, armor stands as {@code ARMOR_STAND}, and other
 * hangings as {@code HANGING_ENTITY}. Non-player actors on those paths stay
 * vanilla here; later milestones own them. Any other right-clicked entity
 * decides as {@code ENTITY_INTERACT}.
 *
 * <p>Redstone parts (wire, repeaters, comparators, daylight detectors,
 * tripwire hooks, target blocks, and every pressure plate) decide as
 * {@code REDSTONE_USE}: right-clicks at the block, steps onto plates by foot
 * ({@code PHYSICAL}) or by mob. Natural mob spawns decide as
 * {@code HOSTILE_MOB_SPAWN} for monsters and {@code PASSIVE_MOB_SPAWN} for
 * everything else; spawner blocks, eggs, breeding, and commands stay
 * vanilla. Mobs changing blocks (endermen, ravagers) decide as
 * {@code MOB_GRIEFING} under the fixed environmental actor.
 *
 * <p>A projectile landing far from its shooter decides as
 * {@code DISPENSER_CROSS_BOUNDARY} from the shooter position to the landing
 * block or entity: only genuine boundary involvement intervenes, so
 * same-land and wilderness landings stay vanilla, and a shooter that is
 * gone cannot prove a crossing. Landing and classification share one index
 * snapshot, use only coordinates already on the event, and never load a
 * chunk.
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

    private static final Set<Material> PLATE_TYPES = EnumSet.of(
            Material.OAK_PRESSURE_PLATE,
            Material.SPRUCE_PRESSURE_PLATE,
            Material.BIRCH_PRESSURE_PLATE,
            Material.JUNGLE_PRESSURE_PLATE,
            Material.ACACIA_PRESSURE_PLATE,
            Material.DARK_OAK_PRESSURE_PLATE,
            Material.MANGROVE_PRESSURE_PLATE,
            Material.CHERRY_PRESSURE_PLATE,
            Material.BAMBOO_PRESSURE_PLATE,
            Material.CRIMSON_PRESSURE_PLATE,
            Material.WARPED_PRESSURE_PLATE,
            Material.PALE_OAK_PRESSURE_PLATE,
            Material.STONE_PRESSURE_PLATE,
            Material.POLISHED_BLACKSTONE_PRESSURE_PLATE,
            Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
            Material.HEAVY_WEIGHTED_PRESSURE_PLATE);

    private static final Set<Material> REDSTONE_TYPES = EnumSet.of(
            Material.REDSTONE_WIRE,
            Material.REPEATER,
            Material.COMPARATOR,
            Material.DAYLIGHT_DETECTOR,
            Material.TRIPWIRE_HOOK,
            Material.TARGET);

    private final ProtectionEngine engine;
    private final RejectionNotifier rejectionNotifier;
    private final EntryProtectionAdapter entryAdapter;

    public ProtectionListener(ProtectionEngine engine) {
        this(engine, null, defaultEntryAdapter(engine));
    }

    /**
     * @param rejectionNotifier throttled deny notices for player-attributed
     *         paths; {@code null} keeps the listener silent (no message cost).
     *         Ownerless mechanics never notify regardless of this seam.
     */
    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier) {
        this(engine, rejectionNotifier, defaultEntryAdapter(engine));
    }

    /**
     * @param entryAdapter transit semantics for ENTRY denies (teleport
     *         coverage, throttled push-out, banned-inside stops); {@code null}
     *         selects the production default (no ban source wired yet, Bukkit
     *         loaded-check, engine ENTRY-validated targets, direct teleport,
     *         system clock). The null ban source is a deliberate honest
     *         downgrade: banned-inside enforcement stays inert until the ban
     *         storage milestone wires a real query (M3-06 gate).
     */
    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier,
                              EntryProtectionAdapter entryAdapter) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.rejectionNotifier = rejectionNotifier;
        this.entryAdapter = entryAdapter == null ? defaultEntryAdapter(engine) : entryAdapter;
    }

    private static EntryProtectionAdapter defaultEntryAdapter(ProtectionEngine engine) {
        Objects.requireNonNull(engine, "engine");
        EntryProtectionAdapter.EntryAllowedCheck entryCheck = (playerId, at) -> {
            try {
                if (playerId == null || at == null || at.getWorld() == null) {
                    return false;
                }
                return engine.decideAt(playerId, at.getWorld().getUID(),
                        at.getBlockX() >> 4, at.getBlockZ() >> 4,
                        EntryProtectionAdapter.entryAction()).outcome() != PermissionState.DENY;
            } catch (RuntimeException ex) {
                return false;
            }
        };
        return new EntryProtectionAdapter(Instant::now,
                EntryProtectionAdapter.DEFAULT_PUSH_OUT_COOLDOWN, null,
                EntryProtectionAdapter.ChunkLoadedCheck.bukkit(), entryCheck,
                (player, target) -> player.teleport(target));
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
        if (PLATE_TYPES.contains(type) || REDSTONE_TYPES.contains(type)) {
            return ProtectionActionType.REDSTONE_USE;
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
                notifyRejection(player, ProtectionActionType.BLOCK_BREAK, decision);
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
                notifyRejection(damager, action, decision);
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
            var placeDecision = decideAtBlock(player.getUniqueId(), placed,
                    ProtectionActionType.BLOCK_PLACE);
            if (placeDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.BLOCK_PLACE, placeDecision);
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
                Block floor = event.getClickedBlock();
                if (floor == null) {
                    return;
                }
                if (floor.getType() == Material.FARMLAND) {
                    Player player = event.getPlayer();
                    if (player == null || floor.getWorld() == null) {
                        event.setCancelled(true);
                        return;
                    }
                    var trampleDecision = decideAtBlock(player.getUniqueId(), floor,
                            ProtectionActionType.FARMLAND_TRAMPLE);
                    if (trampleDecision.outcome() == PermissionState.DENY) {
                        event.setCancelled(true);
                        notifyRejection(player, ProtectionActionType.FARMLAND_TRAMPLE,
                                trampleDecision);
                    }
                    return;
                }
                if (!PLATE_TYPES.contains(floor.getType())) {
                    return;
                }
                Player stepper = event.getPlayer();
                if (stepper == null || floor.getWorld() == null) {
                    event.setCancelled(true);
                    return;
                }
                var plateDecision = decideAtBlock(stepper.getUniqueId(), floor,
                        ProtectionActionType.REDSTONE_USE);
                if (plateDecision.outcome() == PermissionState.DENY) {
                    event.setCancelled(true);
                    notifyRejection(stepper, ProtectionActionType.REDSTONE_USE,
                            plateDecision);
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
            var interactDecision = decideAtBlock(player.getUniqueId(), clicked, action);
            if (interactDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, action, interactDecision);
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
            var bucketDecision = decideAtBlock(player.getUniqueId(), block,
                    ProtectionActionType.BUCKET_USE);
            if (bucketDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.BUCKET_USE, bucketDecision);
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
    public void onDispenserDispense(BlockDispenseEvent event) {
        try {
            Block source = event.getBlock();
            var velocity = event.getVelocity();
            if (source == null || source.getWorld() == null || velocity == null) {
                event.setCancelled(true);
                return;
            }
            // The ejected item starts at the dispenser and travels along the
            // velocity: the adjacent block in that direction is where a
            // crossing first lands. Far-flying projectiles that land several
            // chunks away are outside this check by design; judging the
            // eventual landing position is left to a future milestone.
            int[] target = dispenseTarget(source.getX(), source.getZ(),
                    velocity.getX(), velocity.getZ());
            if (CrossBoundaryDecider.crossDenied(engine, source.getWorld().getUID(),
                    source.getX(), source.getZ(), target[0], target[1],
                    ProtectionActionType.DISPENSER_CROSS_BOUNDARY,
                    ProtectionActionType.DISPENSER_CROSS_BOUNDARY)) {
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
     * Adjacent landing block for a dispense: one step from the source along
     * the sign of the velocity's chunk-plane components. A zero component
     * stays on the source axis, so a straight-down shot targets the block
     * below in the same chunk.
     *
     * @return two-element array {@code [x, z]}
     */
    static int[] dispenseTarget(int srcX, int srcZ, double velX, double velZ) {
        return new int[]{srcX + (int) Math.signum(velX), srcZ + (int) Math.signum(velZ)};
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
            Location current = from != null ? from : to;
            if (entryAdapter.isBannedInside(player.getUniqueId(), current)) {
                event.setCancelled(true);
                notifyBannedInside(player);
                entryAdapter.pushOut(player, null);
                return;
            }
            if (from != null && from.getWorld() != null
                    && from.getWorld().getUID().equals(to.getWorld().getUID())
                    && (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                    && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
                return;
            }
            var entryDecision = decideAtLocation(player.getUniqueId(), to,
                    ProtectionActionType.ENTRY);
            if (entryDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.ENTRY, entryDecision);
                entryAdapter.pushOut(player, from);
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
            Location from = event.getFrom();
            Location to = event.getTo();
            if (player == null || to == null || to.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            Location current = from != null ? from : to;
            if (entryAdapter.isBannedInside(player.getUniqueId(), current)) {
                event.setCancelled(true);
                notifyBannedInside(player);
                entryAdapter.pushOut(player, null);
                return;
            }
            var teleportDecision = decideAtLocation(player.getUniqueId(), to,
                    ProtectionActionType.ENTRY);
            if (teleportDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.ENTRY, teleportDecision);
                entryAdapter.pushOut(player, from);
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
            var enterDecision = decideAtLocation(player.getUniqueId(), vehicle.getLocation(),
                    ProtectionActionType.VEHICLE_USE);
            if (enterDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.VEHICLE_USE, enterDecision);
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
            var vehicleDecision = decideAtLocation(player.getUniqueId(), vehicle.getLocation(),
                    ProtectionActionType.VEHICLE_USE);
            if (vehicleDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.VEHICLE_USE, vehicleDecision);
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
                action = ProtectionActionType.ENTITY_INTERACT;
            }
            Player player = event.getPlayer();
            if (player == null || clicked.getLocation() == null
                    || clicked.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            var entityDecision = decideAtLocation(player.getUniqueId(),
                    clicked.getLocation(), action);
            if (entityDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, action, entityDecision);
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
            var standDecision = decideAtLocation(player.getUniqueId(), stand.getLocation(),
                    ProtectionActionType.ARMOR_STAND);
            if (standDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.ARMOR_STAND, standDecision);
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
            var hangingDecision = decideAtLocation(player.getUniqueId(), at, action);
            if (hangingDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, action, hangingDecision);
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
            var breakDecision = decideAtLocation(player.getUniqueId(),
                    hanging.getLocation(), action);
            if (breakDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, action, breakDecision);
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
            if (block == null) {
                return;
            }
            Entity entity = event.getEntity();
            if (entity == null || block.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            ProtectionActionType action;
            if (block.getType() == Material.FARMLAND) {
                action = ProtectionActionType.FARMLAND_TRAMPLE;
            } else if (PLATE_TYPES.contains(block.getType())) {
                action = ProtectionActionType.REDSTONE_USE;
            } else {
                return;
            }
            if (deniedAtBlock(entity.getUniqueId(), block, action)) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        try {
            Entity entity = event.getEntity();
            Block block = event.getBlock();
            if (entity == null || block == null || block.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            if (deniedAtBlock(ENVIRONMENT_ACTOR, block, ProtectionActionType.MOB_GRIEFING)) {
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
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        try {
            // Only natural spawns are ruled: spawner blocks, eggs, breeding,
            // and commands stay vanilla even when the spawn rules deny.
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
                return;
            }
            LivingEntity entity = event.getEntity();
            if (entity == null || entity.getLocation() == null
                    || entity.getLocation().getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            ProtectionActionType action = (entity instanceof Monster)
                    ? ProtectionActionType.HOSTILE_MOB_SPAWN
                    : ProtectionActionType.PASSIVE_MOB_SPAWN;
            if (deniedAtLocation(ENVIRONMENT_ACTOR, entity.getLocation(), action)) {
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
    public void onProjectileHit(ProjectileHitEvent event) {
        try {
            Projectile projectile = event.getEntity();
            Block hitBlock = event.getHitBlock();
            Entity hitEntity = event.getHitEntity();
            Location landing;
            if (hitBlock != null) {
                landing = hitBlock.getLocation();
            } else if (hitEntity != null) {
                landing = hitEntity.getLocation();
            } else if (projectile != null) {
                landing = projectile.getLocation();
            } else {
                landing = null;
            }
            if (projectile == null || landing == null || landing.getWorld() == null) {
                event.setCancelled(true);
                return;
            }
            int[] source = projectileSourceBlock(projectile);
            if (source == null) {
                // The shooter is gone or untracked: a crossing cannot be
                // proven, so the landing stays vanilla by design.
                return;
            }
            UUID worldId = landing.getWorld().getUID();
            LandRegistry snapshot;
            try {
                snapshot = engine.snapshot();
            } catch (RuntimeException ex) {
                event.setCancelled(true);
                return;
            }
            if (snapshot == null) {
                event.setCancelled(true);
                return;
            }
            var relation = CrossBoundaryDecider.relation(snapshot, worldId,
                    source[0] >> 4, source[1] >> 4,
                    landing.getBlockX() >> 4, landing.getBlockZ() >> 4);
            // Only genuine boundary involvement intervenes: a landing inside
            // the shooter's own land (or fully in the wild) is not a crossing.
            if (relation == CrossBoundaryDecider.Relation.WILDERNESS
                    || relation == CrossBoundaryDecider.Relation.SAME_LAND) {
                return;
            }
            if (landingDeniedOnSnapshot(snapshot, worldId, relation,
                    source[0], source[1], landing.getBlockX(), landing.getBlockZ())) {
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
     * Block-plane origin of a projectile: the shooting player or mob at its
     * current position, or the dispensing block. {@code null} when the
     * shooter is gone or untracked. Coordinates only, so no chunk loads.
     *
     * @return two-element array {@code [x, z]}
     */
    static int[] projectileSourceBlock(Projectile projectile) {
        ProjectileSource shooter;
        try {
            shooter = projectile.getShooter();
        } catch (RuntimeException ex) {
            return null;
        }
        if (shooter instanceof Player player) {
            Location at = safeLocation(player);
            if (at == null) {
                return null;
            }
            return new int[]{at.getBlockX(), at.getBlockZ()};
        }
        if (shooter instanceof BlockProjectileSource dispenser) {
            Block block;
            try {
                block = dispenser.getBlock();
            } catch (RuntimeException ex) {
                return null;
            }
            if (block == null) {
                return null;
            }
            return new int[]{block.getX(), block.getZ()};
        }
        if (shooter instanceof Entity mob) {
            Location at = safeLocation(mob);
            if (at == null) {
                return null;
            }
            return new int[]{at.getBlockX(), at.getBlockZ()};
        }
        return null;
    }

    private static Location safeLocation(Entity entity) {
        try {
            return entity.getLocation();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Either-end deny for a projectile landing on the one snapshot the
     * crossing was classified with, so a concurrent publish landing
     * mid-flight cannot mix versions. The wilderness side is always allow
     * (the engine short-circuits where no land owns the chunk), so only the
     * deciding ends are consulted.
     */
    private boolean landingDeniedOnSnapshot(LandRegistry snapshot, UUID worldId,
                                            CrossBoundaryDecider.Relation relation,
                                            int srcBlockX, int srcBlockZ,
                                            int dstBlockX, int dstBlockZ) {
        return switch (relation) {
            case WILDERNESS, SAME_LAND -> false;
            case WILD_TO_LAND -> landingDeniedAt(snapshot, worldId,
                    dstBlockX, dstBlockZ, ProtectionActionType.DISPENSER_CROSS_BOUNDARY);
            case LAND_TO_WILD -> landingDeniedAt(snapshot, worldId,
                    srcBlockX, srcBlockZ, ProtectionActionType.DISPENSER_CROSS_BOUNDARY);
            case CROSS_LAND -> landingDeniedAt(snapshot, worldId,
                    srcBlockX, srcBlockZ, ProtectionActionType.DISPENSER_CROSS_BOUNDARY)
                    || landingDeniedAt(snapshot, worldId,
                            dstBlockX, dstBlockZ, ProtectionActionType.DISPENSER_CROSS_BOUNDARY);
        };
    }

    private boolean landingDeniedAt(LandRegistry snapshot, UUID worldId,
                                    int blockX, int blockZ, ProtectionActionType action) {
        return engine.decideAtOnSnapshot(ProtectionListener.ENVIRONMENT_ACTOR,
                worldId, blockX >> 4, blockZ >> 4, action, snapshot).outcome()
                == PermissionState.DENY;
    }

    private boolean deniedAtBlock(UUID actor, Block block, ProtectionActionType action) {
        return deniedAt(actor, block.getWorld(), block.getX(), block.getZ(), action);
    }

    /**
     * Notifies the denied player through the rejection seam. Messaging is
     * best-effort and fully guarded: it never changes the cancel decision and
     * never leaks into event dispatch. Only player-attributed DENY branches
     * call this; ownerless mechanics never do.
     */
    private void notifyRejection(Player player, ProtectionActionType action,
                                 PermissionDecision decision) {
        RejectionNotifier notifier = this.rejectionNotifier;
        if (notifier == null) {
            return;
        }
        try {
            notifier.notifyDenied(player, action, decision);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Messaging-only notice for the banned-inside stop: enforcement comes from
     * the ban seam, so a synthetic DENY decision is built for the notifier and
     * never fed back into any decision. Best-effort and fully guarded, like
     * {@link #notifyRejection}.
     */
    private void notifyBannedInside(Player player) {
        RejectionNotifier notifier = this.rejectionNotifier;
        if (notifier == null) {
            return;
        }
        try {
            notifier.notifyDenied(player, ProtectionActionType.ENTRY,
                    new PermissionDecision(PermissionState.DENY,
                            DecisionSource.SUBJECT_PERMISSION,
                            "Banned inside this land: movement denied until pushed out"));
        } catch (RuntimeException ignored) {
        }
    }

    private PermissionDecision decideAtBlock(UUID actor, Block block,
                                             ProtectionActionType action) {
        return engine.decideAt(actor, block.getWorld().getUID(),
                block.getX() >> 4, block.getZ() >> 4, action);
    }

    private PermissionDecision decideAtLocation(UUID actor, Location location,
                                                ProtectionActionType action) {
        return engine.decideAt(actor, location.getWorld().getUID(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4, action);
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
