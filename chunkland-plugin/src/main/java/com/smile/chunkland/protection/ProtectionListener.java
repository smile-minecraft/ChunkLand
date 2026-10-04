package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.message.rejection.RejectionNotifier;
import com.smile.chunkland.message.rejection.RejectionSite;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.SubLandIndex;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
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
import org.bukkit.event.block.BlockMultiPlaceEvent;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.plugin.Plugin;

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
 * decides as {@code ENTITY_DAMAGE} (subject path). A projectile counts as
 * its shooting player, so an arrow, trident, snowball or similar shot by a
 * player faces the same verdict as a melee hit. Damage with no player
 * behind it stays vanilla here; later milestones own that path.
 *
 * <p>Right-click interaction on a block maps to one subject action by block
 * kind: item-holding blocks (chests, barrels, hoppers, shulker boxes,
 * dispensers, droppers, crafters, copper chests, chiseled bookshelves,
 * jukeboxes, lecterns, decorated pots, composters, vaults) to
 * {@code CONTAINER_OPEN}, crafting and processing
 * blocks to {@code WORKSTATION_USE}, doors, trapdoors, and fence gates to
 * {@code DOOR_USE}, buttons to {@code BUTTON_USE}, and levers to
 * {@code LEVER_USE}. Anything else stays vanilla. Stepping onto farmland
 * ({@code PHYSICAL} on soil, by foot or by mob) decides as
 * {@code FARMLAND_TRAMPLE}. World mechanics without a player actor (pistons,
 * fluids, hoppers, explosions, fire) decide under a fixed
 * environmental actor, which can never match a land owner.
 *
 * <p>Movement decides as {@code ENTRY} at the destination. Looking around
 * inside one block never consults the engine, and walking inside one chunk
 * without crossing a subland boundary does not either: the covering subland
 * (or its absence) is compared at both ends on the same memory-only
 * snapshot, so an unchanged covering carries the same verdict as the origin.
 * Teleports always check the destination, for every teleport cause.
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

    /**
     * Blocks whose right-click reaches stored items. Dispensers, droppers,
     * and crafters open an inventory like any chest, so they guard the same
     * action even though redstone can also drive them.
     */
    private static final Set<Material> CONTAINER_TYPES = EnumSet.of(
            Material.CHEST,
            Material.TRAPPED_CHEST,
            Material.BARREL,
            Material.ENDER_CHEST,
            Material.HOPPER,
            Material.DISPENSER,
            Material.DROPPER,
            Material.CRAFTER,
            Material.COPPER_CHEST,
            Material.EXPOSED_COPPER_CHEST,
            Material.WEATHERED_COPPER_CHEST,
            Material.OXIDIZED_COPPER_CHEST,
            Material.WAXED_COPPER_CHEST,
            Material.WAXED_EXPOSED_COPPER_CHEST,
            Material.WAXED_WEATHERED_COPPER_CHEST,
            Material.WAXED_OXIDIZED_COPPER_CHEST,
            Material.CHISELED_BOOKSHELF,
            Material.JUKEBOX,
            Material.LECTERN,
            Material.DECORATED_POT,
            Material.COMPOSTER,
            Material.VAULT);

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

    private static final Logger LOG = Logger.getLogger(
            EntryProtectionAdapter.class.getName());

    private final ProtectionEngine engine;
    private final RejectionNotifier rejectionNotifier;
    private final EntryProtectionAdapter entryAdapter;
    private final EntryDenialParticleFeedback entryDenialParticles;
    private final ActionDenialParticleFeedback actionDenialParticles;

    public ProtectionListener(ProtectionEngine engine) {
        this(engine, null, defaultEntryAdapter(engine, null), null);
    }

    /**
     * @param rejectionNotifier throttled deny notices for player-attributed
     *         paths; {@code null} keeps the listener silent (no message cost).
     *         Ownerless mechanics never notify regardless of this seam.
     */
    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier) {
        this(engine, rejectionNotifier, defaultEntryAdapter(engine, null), null);
    }

    /**
     * @param entryAdapter transit semantics for ENTRY denies (teleport
     *         coverage, throttled push-out, banned-inside stops); {@code null}
     *         selects the default adapter (Bukkit loaded-check, engine
     *         ENTRY-validated targets, an unwired ejection transport that refuses,
     *         system clock) with no ban source wired, so the inside check skips.
     *         Production wiring passes the adapter from
     *         {@link #productionEntryAdapter} instead, which also carries the
     *         live plugin the transport defers onto.
     */
    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier,
                              EntryProtectionAdapter entryAdapter) {
        this(engine, rejectionNotifier, entryAdapter, null);
    }

    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier,
                              EntryProtectionAdapter entryAdapter,
                              EntryDenialParticleFeedback entryDenialParticles) {
        this(engine, rejectionNotifier, entryAdapter, entryDenialParticles, null);
    }

    /**
     * @param actionDenialParticles player-only highlight of a denied block or
     *         entity target; {@code null} keeps those denies without a mark
     */
    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier,
                              EntryProtectionAdapter entryAdapter,
                              EntryDenialParticleFeedback entryDenialParticles,
                              ActionDenialParticleFeedback actionDenialParticles) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.rejectionNotifier = rejectionNotifier;
        this.entryAdapter = entryAdapter == null ? defaultEntryAdapter(engine, null) : entryAdapter;
        this.entryDenialParticles = entryDenialParticles;
        this.actionDenialParticles = actionDenialParticles;
    }

    /**
     * Production listener shape: the ENTRY-validated push-out path reading
     * banned-inside stops from the given ban source. A {@code null} ban
     * source keeps the honest downgrade (inside check skips).
     *
     * <p>These overloads build the adapter without a plugin, so the ejection
     * transport refuses and push-out stays cancel-only. Production wiring must
     * go through {@link #productionEntryAdapter} so the transport can defer
     * onto the player's region thread.
     *
     * @param bans ban read seam over the immutable runtime snapshot; memory
     *             reads only, never storage
     */
    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier,
                              EntryProtectionAdapter.BanLookup bans) {
        this(engine, rejectionNotifier, defaultEntryAdapter(engine, bans), null);
    }

    public ProtectionListener(ProtectionEngine engine, RejectionNotifier rejectionNotifier,
                              EntryProtectionAdapter.BanLookup bans,
                              EntryDenialParticleFeedback entryDenialParticles) {
        this(engine, rejectionNotifier, defaultEntryAdapter(engine, bans), entryDenialParticles);
    }

    /**
     * Ticks the ejection waits before travelling. One tick is enough and is
     * the minimum the platform can honour: the restore that undoes an inline
     * teleport happens in the same dispatch, so any deferral has to land on a
     * later tick than the cancelled event.
     */
    private static final long PUSH_OUT_DELAY_TICKS = 1L;

    /**
     * Adapter for the constructors that take no plugin: same ENTRY-validated
     * target selection, but the ejection transport refuses, so a push-out
     * stays cancel-only instead of teleporting from a thread that cannot own
     * the player. Production wiring goes through
     * {@link #productionEntryAdapter}.
     */
    private static EntryProtectionAdapter defaultEntryAdapter(ProtectionEngine engine,
            EntryProtectionAdapter.BanLookup bans) {
        Objects.requireNonNull(engine, "engine");
        return new EntryProtectionAdapter(Instant::now,
                EntryProtectionAdapter.DEFAULT_PUSH_OUT_COOLDOWN, bans,
                EntryProtectionAdapter.ChunkLoadedCheck.bukkit(),
                entryCheckFor(engine), pushOutSink(null));
    }

    /**
     * Production transit adapter: the ENTRY-validated push-out path reading
     * banned-inside stops from the given ban source.
     *
     * @param bans   ban read seam over the immutable runtime snapshot; memory
     *               reads only, never storage
     * @param plugin the live plugin instance that owns the ejection transport.
     *               A {@code null} leaves the transport refusing, so a push-out
     *               fails closed with an observable record instead of
     *               teleporting from a thread that cannot own the player.
     */
    public static EntryProtectionAdapter productionEntryAdapter(ProtectionEngine engine,
            EntryProtectionAdapter.BanLookup bans, Plugin plugin) {
        return productionEntryAdapter(engine, bans, plugin, null);
    }

    /**
     * Production transit adapter with live push-out tuning.
     *
     * @param tuning cooldown and retreat distance read from the current
     *               config on every deny; {@code null} keeps the built-in
     *               window and distance
     */
    public static EntryProtectionAdapter productionEntryAdapter(ProtectionEngine engine,
            EntryProtectionAdapter.BanLookup bans, Plugin plugin,
            EntryProtectionAdapter.Tuning tuning) {
        Objects.requireNonNull(engine, "engine");
        return new EntryProtectionAdapter(Instant::now,
                EntryProtectionAdapter.RETREAT_PUSH_OUT_COOLDOWN, bans,
                EntryProtectionAdapter.ChunkLoadedCheck.bukkit(),
                entryCheckFor(engine), standingRoom(), tuning, pushOutSink(plugin));
    }

    /** Heights tried around the retreat candidate, nearest first. */
    private static final int[] LANDING_OFFSETS = {0, 1, -1, 2, -2, 3, -3};

    /** Heights tried around a distant escape landing, nearest first. */
    private static final int[] FAR_LANDING_OFFSETS = farLandingOffsets(16);

    private static int[] farLandingOffsets(int reach) {
        int[] offsets = new int[reach * 2 + 1];
        for (int step = 1; step <= reach; step++) {
            offsets[step * 2 - 1] = step;
            offsets[step * 2] = -step;
        }
        return offsets;
    }

    /**
     * Production standing-room probe for the retreat landing.
     *
     * <p>Blocks are read only when the candidate chunk is already loaded and
     * owned by the calling region thread, so nothing is loaded and no other
     * region is touched; anything else answers {@code null} and the retreat
     * falls back to a shorter distance. A spot qualifies when feet and head
     * are free and harmless and there is something to stand on. Flying,
     * gliding and swimming players need no floor.
     */
    static EntryProtectionAdapter.LandingCheck standingRoom() {
        return new EntryProtectionAdapter.LandingCheck() {
            @Override
            public Location settle(Player player, Location candidate) {
                return standingSpot(player, candidate, LANDING_OFFSETS);
            }

            @Override
            public Location settleFar(Player player, Location candidate) {
                return standingSpot(player, candidate, FAR_LANDING_OFFSETS);
            }
        };
    }

    private static Location standingSpot(Player player, Location candidate, int[] offsets) {
        try {
            World world = candidate.getWorld();
            if (world == null) {
                return null;
            }
            int x = candidate.getBlockX();
            int z = candidate.getBlockZ();
            if (!world.isChunkLoaded(x >> 4, z >> 4)
                    || !org.bukkit.Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) {
                return null;
            }
            boolean airborne = player.isFlying() || player.isGliding();
            int baseY = candidate.getBlockY();
            for (int offset : offsets) {
                int y = baseY + offset;
                if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
                    continue;
                }
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (!freeAndHarmless(feet) || !freeAndHarmless(head)) {
                    continue;
                }
                Block floor = world.getBlockAt(x, y - 1, z);
                boolean swimming = feet.getType() == Material.WATER;
                boolean supported = floor.getType().isSolid() && !hazardous(floor.getType());
                if (!supported && !swimming && !(airborne && offset == 0)) {
                    continue;
                }
                Location landing = candidate.clone();
                if (offset != 0) {
                    landing.setY(y);
                }
                return landing;
            }
            return null;
        } catch (RuntimeException | LinkageError unverifiable) {
            return null;
        }
    }

    private static boolean freeAndHarmless(Block block) {
        Material type = block.getType();
        return block.isPassable() && !hazardous(type);
    }

    private static boolean hazardous(Material type) {
        return type == Material.LAVA
                || type == Material.FIRE
                || type == Material.SOUL_FIRE
                || type == Material.MAGMA_BLOCK
                || type == Material.CAMPFIRE
                || type == Material.SOUL_CAMPFIRE
                || type == Material.CACTUS
                || type == Material.SWEET_BERRY_BUSH
                || type == Material.WITHER_ROSE
                || type == Material.POWDER_SNOW
                || type == Material.END_PORTAL
                || type == Material.NETHER_PORTAL;
    }

    private static EntryProtectionAdapter.EntryAllowedCheck entryCheckFor(
            ProtectionEngine engine) {
        return (playerId, at) -> {
            try {
                if (playerId == null || at == null || at.getWorld() == null) {
                    return false;
                }
                return engine.decideAtBlock(playerId, at.getWorld().getUID(),
                        at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                        EntryProtectionAdapter.entryAction()).outcome() != PermissionState.DENY;
            } catch (RuntimeException ex) {
                return false;
            }
        };
    }

    /**
     * Production ejection transport: hands the landing to the player's own
     * Folia thread for a later tick, then travels over {@code teleportAsync}
     * there.
     *
     * <p>The deferral is load-bearing, not politeness. Paper fires
     * {@code PlayerMoveEvent} from inside
     * {@code ServerGamePacketListenerImpl#handleMovePlayer} and, once the
     * event comes back cancelled, immediately restores the pre-move position
     * with {@code internalTeleport(from)}. On the player's own region thread
     * {@code teleportAsync} resolves inline through the same-region fast path,
     * so an ejection issued from the handler is applied and then undone in the
     * same call — the player stays exactly where they were, nothing throws,
     * and the future still reports success. On the deferred tick the restore
     * has already happened, so the ejection stands.
     *
     * <p>Sync {@code Entity#teleport} stays unused: it is broken on Folia. The
     * rescue pass the adapter registered only ever waives the origin ban stop
     * for the validated landing, never the destination ENTRY check. Failures
     * are reported rather than dropped: a refused hop answers {@code false},
     * and a refused or failed teleport is logged when the platform reports it
     * on completion.
     */
    static EntryProtectionAdapter.PushOutSink pushOutSink(Plugin plugin) {
        if (plugin == null) {
            return (player, target) -> false;
        }
        return (player, target) -> {
            try {
                ScheduledTask scheduled = player.getScheduler().runDelayed(plugin,
                        started -> teleportOnPlayerThread(player, target), null,
                        PUSH_OUT_DELAY_TICKS);
                return scheduled != null;
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "ChunkLand push-out could not hand the landing to the "
                        + "player's region thread: " + ex.getMessage(), ex);
                return false;
            }
        };
    }

    private static void teleportOnPlayerThread(Player player, Location target) {
        try {
            player.teleportAsync(target).whenComplete((moved, error) -> {
                if (error != null) {
                    LOG.log(Level.WARNING, "ChunkLand push-out teleport failed for "
                            + player.getUniqueId() + ": " + error.getMessage(), error);
                } else if (!Boolean.TRUE.equals(moved)) {
                    LOG.warning("ChunkLand push-out teleport was refused by the platform for "
                            + player.getUniqueId() + "; the player stays where they are");
                }
            });
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "ChunkLand push-out teleport threw for "
                    + player.getUniqueId() + ": " + ex.getMessage(), ex);
        }
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
            var decision = engine.decideAtBlock(
                    player.getUniqueId(),
                    block.getWorld().getUID(),
                    block.getX(), block.getY(), block.getZ(),
                    ProtectionActionType.BLOCK_BREAK);
            if (decision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                rejectAtBlock(player, ProtectionActionType.BLOCK_BREAK, decision, block);
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
            Player damager = shootingPlayer(event.getDamager());
            if (damager == null) {
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
            var decision = engine.decideAtBlock(
                    damager.getUniqueId(),
                    location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                    action);
            if (decision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                rejectAtSpot(damager, action, decision, location);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Player behind a damage event: the damager itself, or the shooting player
     * of a projectile. In-memory getters only, so no chunk loads or blocking
     * I/O; a shooter lookup failure propagates to the caller's fail-closed
     * catch. {@code null} when no player is behind the damage, which stays
     * vanilla by design.
     */
    private static Player shootingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        try {
            if (event instanceof BlockMultiPlaceEvent) {
                // Owned by onBlockMultiPlace below: this Bukkit version gives
                // the multi-place event no handler list of its own, so both
                // handlers fire for one placement and this one must stay out.
                return;
            }
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
                rejectAtBlock(player, ProtectionActionType.BLOCK_PLACE, placeDecision, placed);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * One placement action affecting several blocks (beds, doors, and
     * whatever else the server reports this way) denies when any affected
     * part lands in denied space, primary block included.
     *
     * <p>Every part comes from the event itself ({@code getBlockPlaced} plus
     * {@code getReplacedBlockStates}), so the check stays coordinate-only:
     * no chunk is loaded and no I/O happens however many parts there are.
     * Any missing block, world, or part list cancels (fail-closed).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        try {
            Player player = event.getPlayer();
            Block placed = event.getBlockPlaced();
            List<BlockState> replaced;
            try {
                replaced = event.getReplacedBlockStates();
            } catch (RuntimeException ex) {
                replaced = null;
            }
            if (player == null || placed == null || placed.getWorld() == null
                    || replaced == null) {
                event.setCancelled(true);
                return;
            }
            // The primary block is checked on its own because some versions
            // only list the secondary parts; a second lookup of the same
            // coordinates answers from the decision cache.
            var primaryDecision = decideAtBlock(player.getUniqueId(), placed,
                    ProtectionActionType.BLOCK_PLACE);
            if (primaryDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                rejectAtBlock(player, ProtectionActionType.BLOCK_PLACE, primaryDecision,
                        placed);
                return;
            }
            for (BlockState state : replaced) {
                Block part = state == null ? null : state.getBlock();
                if (part == null || part.getWorld() == null) {
                    event.setCancelled(true);
                    return;
                }
                var partDecision = decideAtBlock(player.getUniqueId(), part,
                        ProtectionActionType.BLOCK_PLACE);
                if (partDecision.outcome() == PermissionState.DENY) {
                    event.setCancelled(true);
                    rejectAtBlock(player, ProtectionActionType.BLOCK_PLACE, partDecision,
                            part);
                    return;
                }
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
                                trampleDecision, siteOf(floor));
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
                            plateDecision, siteOf(floor));
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
                rejectAtBlock(player, action, interactDecision, clicked);
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
                rejectAtBlock(player, ProtectionActionType.BUCKET_USE, bucketDecision, block);
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
            if (pistonMoveDenied(event.getBlock(), event.getBlocks(), event.getDirection(), false)) {
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
            if (pistonMoveDenied(event.getBlock(), event.getBlocks(), event.getDirection(), true)) {
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
     * Checks every moved block at its current position and at its destination,
     * and the piston itself against everything it reaches. A move that stays
     * inside one land decides as {@code PISTON_MOVE}; a move that touches a
     * land boundary decides as {@code BLOCK_MOVE_IN}/{@code BLOCK_MOVE_OUT},
     * so a piston standing outside a land cannot rearrange blocks inside it
     * and an extending head cannot reach across the border either.
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
    private boolean pistonMoveDenied(Block piston, List<Block> moved, BlockFace direction,
                                     boolean retract) {
        if (piston == null || piston.getWorld() == null || moved == null || direction == null) {
            return true;
        }
        UUID worldId = piston.getWorld().getUID();
        if (!retract && pistonReachDenied(worldId, piston,
                piston.getX() + direction.getModX(),
                piston.getY() + direction.getModY(),
                piston.getZ() + direction.getModZ())) {
            return true;
        }
        int sign = retract ? -1 : 1;
        for (Block block : moved) {
            if (block == null || block.getWorld() == null) {
                return true;
            }
            if (pistonReachDenied(worldId, piston, block.getX(), block.getY(), block.getZ())) {
                return true;
            }
            if (CrossBoundaryDecider.mechanicDenied(engine, worldId,
                    block.getX(), block.getY(), block.getZ(),
                    block.getX() + sign * direction.getModX(),
                    block.getY() + sign * direction.getModY(),
                    block.getZ() + sign * direction.getModZ(),
                    ProtectionActionType.PISTON_MOVE,
                    ProtectionActionType.BLOCK_MOVE_IN,
                    ProtectionActionType.BLOCK_MOVE_OUT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The piston against one block it reaches (its extending head or a block
     * it moves). Only a land boundary between the two intervenes: inside one
     * land the moved block's own check already reads {@code PISTON_MOVE}.
     */
    private boolean pistonReachDenied(UUID worldId, Block piston,
                                      int blockX, int blockY, int blockZ) {
        if (!engine.isRegistryReady()) {
            return true;
        }
        LandRegistry snapshot;
        try {
            snapshot = engine.snapshot();
        } catch (RuntimeException ex) {
            return true;
        }
        if (snapshot == null) {
            return true;
        }
        var relation = CrossBoundaryDecider.relation(snapshot, worldId,
                piston.getX() >> 4, piston.getZ() >> 4, blockX >> 4, blockZ >> 4);
        if (relation == CrossBoundaryDecider.Relation.WILDERNESS
                || relation == CrossBoundaryDecider.Relation.SAME_LAND) {
            return false;
        }
        return CrossBoundaryDecider.mechanicDenied(engine, worldId,
                piston.getX(), piston.getY(), piston.getZ(), blockX, blockY, blockZ,
                ProtectionActionType.PISTON_MOVE,
                ProtectionActionType.BLOCK_MOVE_IN,
                ProtectionActionType.BLOCK_MOVE_OUT);
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
            if (!from.getWorld().getUID().equals(to.getWorld().getUID())
                    || CrossBoundaryDecider.mechanicDenied(engine, from.getWorld().getUID(),
                            from.getX(), from.getY(), from.getZ(),
                            to.getX(), to.getY(), to.getZ(),
                            ProtectionActionType.FLUID_FLOW,
                            ProtectionActionType.FLUID_ENTER,
                            ProtectionActionType.FLUID_EXIT)) {
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
            if (!source.getWorld().getUID().equals(destination.getWorld().getUID())
                    || CrossBoundaryDecider.mechanicDenied(engine, source.getWorld().getUID(),
                            source.getBlockX(), source.getBlockY(), source.getBlockZ(),
                            destination.getBlockX(), destination.getBlockY(),
                            destination.getBlockZ(),
                            ProtectionActionType.HOPPER_TRANSFER,
                            ProtectionActionType.ITEM_TRANSFER_IN,
                            ProtectionActionType.ITEM_TRANSFER_OUT)) {
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
                entryAdapter.pushOutOfBan(player, current);
                return;
            }
            if (from != null && from.getWorld() != null
                    && from.getWorld().getUID().equals(to.getWorld().getUID())) {
                if (sameBlock(from, to)) {
                    return;
                }
                if (sameChunk(from, to)) {
                    checkSameChunkEntry(player, from, to, event);
                    return;
                }
            }
            var entryDecision = decideAtLocation(player.getUniqueId(), to,
                    ProtectionActionType.ENTRY);
            cancelOnEntryDeny(player, from, to, event, entryDecision);
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * ENTRY check for movement inside one X/Z chunk. The chunk alone cannot
     * tell subland crossings apart (a precise subland can split one chunk
     * horizontally, and stacked sublands split it vertically), so the
     * covering subland at both ends is compared on one memory-only snapshot:
     * an unchanged covering carries the same verdict as the origin and skips
     * the decision, while a crossing (or any unreadable state) falls through
     * to a destination ENTRY check. Snapshot reads only, so no chunk loads,
     * no storage, and no blocking I/O happen here.
     */
    private void checkSameChunkEntry(Player player, Location from, Location to,
            PlayerMoveEvent event) {
        LandRegistry snapshot = currentSnapshot();
        if (snapshot != null) {
            try {
                if (sameSubLandCovering(snapshot, from, to)) {
                    return;
                }
            } catch (RuntimeException ex) {
                snapshot = null;
            }
        }
        PermissionDecision entryDecision = snapshot == null
                ? decideAtLocation(player.getUniqueId(), to, ProtectionActionType.ENTRY)
                : engine.decideAtBlockOnSnapshot(player.getUniqueId(), to.getWorld().getUID(),
                        to.getBlockX(), to.getBlockY(), to.getBlockZ(),
                        ProtectionActionType.ENTRY, snapshot);
        cancelOnEntryDeny(player, from, to, event, entryDecision);
    }

    private void cancelOnEntryDeny(Player player, Location from, Location to,
            PlayerMoveEvent event, PermissionDecision entryDecision) {
        if (entryDecision.outcome() == PermissionState.DENY) {
            event.setCancelled(true);
            notifyRejection(player, ProtectionActionType.ENTRY, entryDecision, to);
            entryAdapter.pushOut(player, from, to);
            showEntryDenialParticle(player, to);
        }
    }

    private LandRegistry currentSnapshot() {
        try {
            return engine.snapshot();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Whether both ends of an in-chunk move sit under the same covering
     * subland (or outside every subland). The same chunk always means the
     * same owning land, so only the subland covering can differ; wilderness
     * has no ENTRY deny by construction and reads as unchanged. Memory-only
     * index reads.
     */
    private boolean sameSubLandCovering(LandRegistry snapshot, Location from, Location to) {
        LandId landId = snapshot.findLandId(to.getWorld().getUID(),
                to.getBlockX() >> 4, to.getBlockZ() >> 4);
        if (landId == null) {
            return true;
        }
        SubLandIndex index = snapshot.subLandIndex(landId);
        if (index == null) {
            return true;
        }
        SubLandSnapshot fromCover = index.findAtBlock(
                from.getBlockX(), from.getBlockY(), from.getBlockZ());
        SubLandSnapshot toCover = index.findAtBlock(
                to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (fromCover == null || toCover == null) {
            return fromCover == toCover;
        }
        return fromCover.id().equals(toCover.id());
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private static boolean sameChunk(Location from, Location to) {
        return (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4);
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
            // A rescue teleport departs from the denying area by definition,
            // so its own origin always reads as banned-inside. The adapter
            // hands that teleport a single-use pass for its validated
            // landing; only that arrival skips this stop, and the
            // destination ENTRY check below still runs. The pass is claimed
            // for every arrival up front: ENTRY-deny rescues land where the
            // player is not banned, and gating the claim on the ban stop
            // would leave their pass behind.
            boolean rescued;
            try {
                rescued = entryAdapter.consumePushOutPass(player.getUniqueId(), to);
            } catch (RuntimeException ex) {
                rescued = false;
            }
            if (entryAdapter.isBannedInside(player.getUniqueId(), current)
                    && !rescued) {
                event.setCancelled(true);
                notifyBannedInside(player);
                entryAdapter.pushOutOfBan(player, current);
                return;
            }
            var teleportDecision = decideAtLocation(player.getUniqueId(), to,
                    ProtectionActionType.ENTRY);
            if (teleportDecision.outcome() == PermissionState.DENY) {
                event.setCancelled(true);
                notifyRejection(player, ProtectionActionType.ENTRY, teleportDecision, to);
                entryAdapter.pushOut(player, from);
                showEntryDenialParticle(player, to);
            }
        } catch (RuntimeException ex) {
            try {
                event.setCancelled(true);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            if (event == null || event.getPlayer() == null) {
                return;
            }
            try {
                entryAdapter.discardPushOutPass(event.getPlayer().getUniqueId());
            } catch (RuntimeException ignored) {
            }
            if (actionDenialParticles != null) {
                actionDenialParticles.forget(event.getPlayer().getUniqueId());
            }
            if (entryDenialParticles == null) {
                return;
            }
            entryDenialParticles.forget(event.getPlayer().getUniqueId());
        } catch (RuntimeException ignored) {
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
                rejectAtSpot(player, ProtectionActionType.VEHICLE_USE, enterDecision,
                        vehicle.getLocation());
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
                rejectAtSpot(player, ProtectionActionType.VEHICLE_USE, vehicleDecision,
                        vehicle.getLocation());
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
                rejectAtSpot(player, action, entityDecision, clicked.getLocation());
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
                rejectAtSpot(player, ProtectionActionType.ARMOR_STAND, standDecision,
                        stand.getLocation());
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
                rejectAtSpot(player, action, hangingDecision, at);
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
                rejectAtSpot(player, action, breakDecision, hanging.getLocation());
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
        return deniedAt(actor, block.getWorld(), block.getX(), block.getY(), block.getZ(), action);
    }

    /**
     * Notifies the denied player through the rejection seam. Messaging is
     * best-effort and fully guarded: it never changes the cancel decision and
     * never leaks into event dispatch. Only player-attributed DENY branches
     * call this; ownerless mechanics never do.
     */
    private void notifyRejection(Player player, ProtectionActionType action,
                                 PermissionDecision decision, Location at) {
        notifyRejection(player, action, decision, siteOf(at));
    }

    private void notifyRejection(Player player, ProtectionActionType action,
                                 PermissionDecision decision, RejectionSite site) {
        RejectionNotifier notifier = this.rejectionNotifier;
        if (notifier == null) {
            return;
        }
        try {
            notifier.notifyDenied(player, action, decision, site);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Deny site for the notice, from coordinates the event already carries.
     * An unreadable position answers {@code null}, which keeps the notice
     * generic instead of dropping it.
     */
    private static RejectionSite siteOf(Location at) {
        try {
            if (at == null || at.getWorld() == null) {
                return null;
            }
            return new RejectionSite(at.getWorld().getUID(),
                    at.getBlockX(), at.getBlockY(), at.getBlockZ());
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static RejectionSite siteOf(Block block) {
        try {
            return new RejectionSite(block.getWorld().getUID(),
                    block.getX(), block.getY(), block.getZ());
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /**
     * Player-attributed deny on a block: the notice names the land, and the
     * denied block is outlined for that player. Both are best-effort and
     * never change the cancel decision.
     */
    private void rejectAtBlock(Player player, ProtectionActionType action,
                               PermissionDecision decision, Block block) {
        notifyRejection(player, action, decision, siteOf(block));
        ActionDenialParticleFeedback feedback = this.actionDenialParticles;
        if (feedback == null || RejectionNotifier.isSilentByDefault(action)) {
            return;
        }
        try {
            feedback.showBlock(player, block.getWorld().getUID(),
                    block.getX(), block.getY(), block.getZ());
        } catch (RuntimeException ignored) {
            // Particle feedback is best effort and cannot change enforcement.
        }
    }

    /**
     * Player-attributed deny on an entity or other free spot: the notice
     * names the land, and the spot is ringed for that player. Rule-decided
     * denies stay unmarked, matching their silent notice.
     */
    private void rejectAtSpot(Player player, ProtectionActionType action,
                              PermissionDecision decision, Location at) {
        notifyRejection(player, action, decision, at);
        ActionDenialParticleFeedback feedback = this.actionDenialParticles;
        if (feedback == null || at == null || RejectionNotifier.isSilentByDefault(action)) {
            return;
        }
        try {
            feedback.showSpot(player, at.getWorld().getUID(), at.getX(), at.getY(), at.getZ());
        } catch (RuntimeException ignored) {
            // Particle feedback is best effort and cannot change enforcement.
        }
    }

    private void showEntryDenialParticle(Player player, Location destination) {
        EntryDenialParticleFeedback feedback = this.entryDenialParticles;
        if (feedback == null) {
            return;
        }
        try {
            feedback.show(player, destination);
        } catch (RuntimeException ignored) {
            // Particle feedback is best effort and cannot change enforcement.
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
                            RejectionNotifier.BANNED_INSIDE_REASON));
        } catch (RuntimeException ignored) {
        }
    }

    private PermissionDecision decideAtBlock(UUID actor, Block block,
                                             ProtectionActionType action) {
        return engine.decideAtBlock(actor, block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), action);
    }

    private PermissionDecision decideAtLocation(UUID actor, Location location,
                                                ProtectionActionType action) {
        return engine.decideAtBlock(actor, location.getWorld().getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action);
    }

    private boolean deniedAtLocation(UUID actor, Location location, ProtectionActionType action) {
        return deniedAt(actor, location.getWorld(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), action);
    }

    private boolean deniedAt(UUID actor, World world, int blockX, int blockY, int blockZ,
                             ProtectionActionType action) {
        return engine.decideAtBlock(actor, world.getUID(), blockX, blockY, blockZ, action).outcome()
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
