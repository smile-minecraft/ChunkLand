package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

class ProtectionP0SubsetListenerTest {

    private static World worldProxy(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    private static Block blockProxy(World world, int x, int y, int z, Material type) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld": return world;
                        case "getX": return x;
                        case "getY": return y;
                        case "getZ": return z;
                        case "getType": return type;
                        case "getLocation": return new Location(world, x, y, z);
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeBlock";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static Player playerProxy(UUID id, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getName": return "TestPlayer";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static Cow cowProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Cow) Proxy.newProxyInstance(Cow.class.getClassLoader(),
                new Class[]{Cow.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return UUID.randomUUID();
                        case "getWorld": return world;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeCow";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private static Inventory inventoryProxy(Location location) {
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class[]{Inventory.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation": return location;
                        case "getHolder": return null;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeInventory";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static BlockPlaceEvent placeEvent(Block placed, Player player) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        BlockPlaceEvent event =
                (BlockPlaceEvent) unsafe.allocateInstance(BlockPlaceEvent.class);
        Field blockField = BlockEvent.class.getDeclaredField("block");
        blockField.setAccessible(true);
        blockField.set(event, placed);
        // BlockPlaceEvent carries its own player field; it does not extend PlayerEvent.
        Field playerField = BlockPlaceEvent.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(event, player);
        return event;
    }

    private static void fillBucketFields(org.bukkit.event.player.PlayerBucketEvent event,
                                         Player player, Block clicked) throws Exception {
        Field playerField = PlayerEvent.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(event, player);
        Field blockField =
                org.bukkit.event.player.PlayerBucketEvent.class.getDeclaredField("block");
        blockField.setAccessible(true);
        blockField.set(event, clicked);
        Field clickedField =
                org.bukkit.event.player.PlayerBucketEvent.class.getDeclaredField("blockClicked");
        clickedField.setAccessible(true);
        clickedField.set(event, clicked);
    }

    private static PlayerBucketEmptyEvent bucketEvent(Player player, Block clicked) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        PlayerBucketEmptyEvent event =
                (PlayerBucketEmptyEvent) unsafe.allocateInstance(PlayerBucketEmptyEvent.class);
        fillBucketFields(event, player, clicked);
        return event;
    }

    private static PlayerBucketFillEvent fillEvent(Player player, Block clicked) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        PlayerBucketFillEvent event =
                (PlayerBucketFillEvent) unsafe.allocateInstance(PlayerBucketFillEvent.class);
        fillBucketFields(event, player, clicked);
        return event;
    }

    private static EntityDamageEvent damageEvent(Entity victim,
                                                 EntityDamageEvent.DamageCause cause) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        EntityDamageEvent event =
                (EntityDamageEvent) unsafe.allocateInstance(EntityDamageEvent.class);
        Field entityField = org.bukkit.event.entity.EntityEvent.class.getDeclaredField("entity");
        entityField.setAccessible(true);
        entityField.set(event, victim);
        Field causeField = EntityDamageEvent.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(event, cause);
        return event;
    }

    private static InventoryMoveItemEvent moveItemEvent(Inventory source, Inventory destination)
            throws Exception {
        // The public constructor rejects a null item, and building a real
        // ItemStack needs a server registry, so allocate directly: the listener
        // only reads source and destination.
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        InventoryMoveItemEvent event =
                (InventoryMoveItemEvent) unsafe.allocateInstance(InventoryMoveItemEvent.class);
        Field sourceField = InventoryMoveItemEvent.class.getDeclaredField("sourceInventory");
        sourceField.setAccessible(true);
        sourceField.set(event, source);
        Field destinationField = InventoryMoveItemEvent.class.getDeclaredField("destinationInventory");
        destinationField.setAccessible(true);
        destinationField.set(event, destination);
        return event;
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static ProtectionEngine engineFor(LandRegistryStore store,
                                              ProtectionActionType action,
                                              PermissionState state,
                                              AtomicReference<ProtectionActionType> seen) {
        return new ProtectionEngine(store::snapshot, (actor, landId, a, snapshot) -> {
            if (seen != null) {
                seen.set(a);
            }
            if (a.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, a == action ? state : PermissionState.INHERIT);
            }
            if (a != action) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            return new PermissionContext(a, false, List.of(), state, PermissionState.INHERIT);
        });
    }

    private record Fixture(UUID worldId, LandId landId, UUID owner, LandRegistryStore store, World world) {
    }

    private static ProtectionEngine countingDenyEngine(LandRegistryStore store,
                                                       ProtectionActionType action,
                                                       AtomicInteger landLookups) {
        // Same DENY semantics as engineFor: only land chunks reach the
        // provider (wilderness short-circuits to ALLOW inside decideAt),
        // so the counter records exactly the land-chunk lookups.
        return new ProtectionEngine(store::snapshot, (actor, landId, a, snapshot) -> {
            landLookups.incrementAndGet();
            if (a.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, a == action ? PermissionState.DENY : PermissionState.INHERIT);
            }
            if (a != action) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            return new PermissionContext(a, false, List.of(),
                    PermissionState.DENY, PermissionState.INHERIT);
        });
    }

    private static Fixture fixture() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, owner, 0, 0))));
        return new Fixture(worldId, landId, owner, store, worldProxy(worldId));
    }

    @Test
    void blockPlaceDenyCancelsAndAllowPasses() throws Exception {
        Fixture fx = fixture();
        UUID stranger = UUID.randomUUID();
        Block placed = blockProxy(fx.world(), 5, 64, 5, Material.STONE);
        Player player = playerProxy(stranger, fx.world());

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.BLOCK_PLACE, PermissionState.DENY, null));
        BlockPlaceEvent denied = placeEvent(placed, player);
        denying.onBlockPlace(denied);
        assertTrue(denied.isCancelled());

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.BLOCK_PLACE, PermissionState.ALLOW, null));
        BlockPlaceEvent allowed = placeEvent(placed, player);
        allowing.onBlockPlace(allowed);
        assertFalse(allowed.isCancelled());
    }

    @Test
    void blockPlaceInWildernessPasses() throws Exception {
        Fixture fx = fixture();
        Block placed = blockProxy(fx.world(), 900, 64, 900, Material.STONE);
        Player player = playerProxy(UUID.randomUUID(), fx.world());
        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.BLOCK_PLACE, PermissionState.DENY, null));
        BlockPlaceEvent event = placeEvent(placed, player);
        listener.onBlockPlace(event);
        assertFalse(event.isCancelled(), "wilderness follows vanilla: never cancel");
    }

    @Test
    void realProviderGrantsOwnerPlaceAndDeniesStranger() throws Exception {
        Fixture fx = fixture();
        var provider = new SnapshotPermissionContextProvider(null, null);
        ProtectionListener listener =
                new ProtectionListener(new ProtectionEngine(fx.store()::snapshot, provider));

        Block placed = blockProxy(fx.world(), 5, 64, 5, Material.STONE);
        BlockPlaceEvent ownerEvent = placeEvent(placed, playerProxy(fx.owner(), fx.world()));
        listener.onBlockPlace(ownerEvent);
        assertFalse(ownerEvent.isCancelled(), "owner places in own land: must pass");

        BlockPlaceEvent strangerEvent =
                placeEvent(placed, playerProxy(UUID.randomUUID(), fx.world()));
        listener.onBlockPlace(strangerEvent);
        assertTrue(strangerEvent.isCancelled(), "stranger places in a land: must cancel");
    }

    @Test
    void interactActionMapping() {
        assertEquals(ProtectionActionType.CONTAINER_OPEN,
                ProtectionListener.interactAction(Material.CHEST));
        assertEquals(ProtectionActionType.CONTAINER_OPEN,
                ProtectionListener.interactAction(Material.BARREL));
        assertEquals(ProtectionActionType.CONTAINER_OPEN,
                ProtectionListener.interactAction(Material.SHULKER_BOX));
        assertEquals(ProtectionActionType.WORKSTATION_USE,
                ProtectionListener.interactAction(Material.CRAFTING_TABLE));
        assertEquals(ProtectionActionType.WORKSTATION_USE,
                ProtectionListener.interactAction(Material.ANVIL));
        assertEquals(ProtectionActionType.DOOR_USE,
                ProtectionListener.interactAction(Material.OAK_DOOR));
        assertEquals(ProtectionActionType.DOOR_USE,
                ProtectionListener.interactAction(Material.OAK_TRAPDOOR));
        assertEquals(ProtectionActionType.BUTTON_USE,
                ProtectionListener.interactAction(Material.STONE_BUTTON));
        assertEquals(ProtectionActionType.LEVER_USE,
                ProtectionListener.interactAction(Material.LEVER));
        assertNull(ProtectionListener.interactAction(Material.STONE));
        assertNull(ProtectionListener.interactAction(Material.DIRT));
        assertNull(ProtectionListener.interactAction(null));
    }

    @Test
    void interactRoutesByBlockKindAndCancelsOnDeny() {
        Fixture fx = fixture();
        UUID stranger = UUID.randomUUID();
        Player player = playerProxy(stranger, fx.world());
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();

        for (var entry : List.of(
                new Object[]{Material.CHEST, ProtectionActionType.CONTAINER_OPEN},
                new Object[]{Material.CRAFTING_TABLE, ProtectionActionType.WORKSTATION_USE},
                new Object[]{Material.OAK_DOOR, ProtectionActionType.DOOR_USE},
                new Object[]{Material.STONE_BUTTON, ProtectionActionType.BUTTON_USE},
                new Object[]{Material.LEVER, ProtectionActionType.LEVER_USE})) {
            Material kind = (Material) entry[0];
            ProtectionActionType action = (ProtectionActionType) entry[1];
            ProtectionListener denying = new ProtectionListener(
                    engineFor(fx.store(), action, PermissionState.DENY, seen));
            Block clicked = blockProxy(fx.world(), 5, 64, 5, kind);
            PlayerInteractEvent event = new PlayerInteractEvent(
                    player, Action.RIGHT_CLICK_BLOCK, null, clicked, BlockFace.UP);
            denying.onPlayerInteract(event);
            assertTrue(event.isCancelled(), kind + " DENY must cancel");
            assertEquals(action, seen.get(), kind + " must route to " + action);

            ProtectionListener allowing = new ProtectionListener(
                    engineFor(fx.store(), action, PermissionState.ALLOW, null));
            PlayerInteractEvent passed = new PlayerInteractEvent(
                    player, Action.RIGHT_CLICK_BLOCK, null, clicked, BlockFace.UP);
            allowing.onPlayerInteract(passed);
            assertFalse(passed.isCancelled(), kind + " ALLOW must pass");
        }
    }

    @Test
    void interactIgnoresUnprotectedKindsAndClicks() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.BLOCK_BREAK, PermissionState.DENY, seen));
        Player player = playerProxy(UUID.randomUUID(), fx.world());

        Block stone = blockProxy(fx.world(), 5, 64, 5, Material.STONE);
        PlayerInteractEvent plain = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null, stone, BlockFace.UP);
        listener.onPlayerInteract(plain);
        assertFalse(plain.isCancelled(), "unprotected kinds stay vanilla");
        assertNull(seen.get(), "unprotected kinds must not consult the engine");

        Block chest = blockProxy(fx.world(), 5, 64, 5, Material.CHEST);
        PlayerInteractEvent leftClick = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, null, chest, BlockFace.UP);
        listener.onPlayerInteract(leftClick);
        assertFalse(leftClick.isCancelled(), "left click is not a use");
        assertNull(seen.get(), "left click must not consult the engine");
    }

    @Test
    void bucketDenyCancelsAndAllowPasses() throws Exception {
        Fixture fx = fixture();
        Player player = playerProxy(UUID.randomUUID(), fx.world());
        Block clicked = blockProxy(fx.world(), 5, 64, 5, Material.STONE);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.BUCKET_USE, PermissionState.DENY, null));
        PlayerBucketEmptyEvent deniedEmpty = bucketEvent(player, clicked);
        denying.onBucketEmpty(deniedEmpty);
        assertTrue(deniedEmpty.isCancelled(), "empty DENY must cancel");

        PlayerBucketFillEvent deniedFill = fillEvent(player, clicked);
        denying.onBucketFill(deniedFill);
        assertTrue(deniedFill.isCancelled(), "fill DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.BUCKET_USE, PermissionState.ALLOW, null));
        PlayerBucketEmptyEvent allowedEmpty = bucketEvent(player, clicked);
        allowing.onBucketEmpty(allowedEmpty);
        assertFalse(allowedEmpty.isCancelled(), "empty ALLOW must pass");

        PlayerBucketFillEvent allowedFill = fillEvent(player, clicked);
        allowing.onBucketFill(allowedFill);
        assertFalse(allowedFill.isCancelled(), "fill ALLOW must pass");
    }

    @Test
    void pistonDenyCancelsAndAllowPasses() {
        Fixture fx = fixture();
        Block piston = blockProxy(fx.world(), 5, 64, 5, Material.PISTON);
        Block moved = blockProxy(fx.world(), 6, 64, 5, Material.STONE);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.PISTON_MOVE, PermissionState.DENY, null));
        BlockPistonExtendEvent deniedExtend =
                new BlockPistonExtendEvent(piston, List.of(moved), BlockFace.EAST);
        denying.onPistonExtend(deniedExtend);
        assertTrue(deniedExtend.isCancelled());
        BlockPistonRetractEvent deniedRetract =
                new BlockPistonRetractEvent(piston, List.of(moved), BlockFace.EAST);
        denying.onPistonRetract(deniedRetract);
        assertTrue(deniedRetract.isCancelled());

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.PISTON_MOVE, PermissionState.ALLOW, null));
        BlockPistonExtendEvent allowedExtend =
                new BlockPistonExtendEvent(piston, List.of(moved), BlockFace.EAST);
        allowing.onPistonExtend(allowedExtend);
        assertFalse(allowedExtend.isCancelled());
        BlockPistonRetractEvent allowedRetract =
                new BlockPistonRetractEvent(piston, List.of(moved), BlockFace.EAST);
        allowing.onPistonRetract(allowedRetract);
        assertFalse(allowedRetract.isCancelled());
    }

    @Test
    void fluidDenyCancelsAndAllowPasses() {
        Fixture fx = fixture();
        Block from = blockProxy(fx.world(), 5, 64, 5, Material.WATER);
        Block to = blockProxy(fx.world(), 6, 64, 5, Material.AIR);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FLUID_FLOW, PermissionState.DENY, null));
        BlockFromToEvent denied = new BlockFromToEvent(from, to);
        denying.onFluidFlow(denied);
        assertTrue(denied.isCancelled());

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FLUID_FLOW, PermissionState.ALLOW, null));
        BlockFromToEvent allowed = new BlockFromToEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.WATER),
                blockProxy(fx.world(), 6, 64, 5, Material.AIR));
        allowing.onFluidFlow(allowed);
        assertFalse(allowed.isCancelled());
    }

    @Test
    void hopperDenyCancelsAndAllowPasses() throws Exception {
        Fixture fx = fixture();
        Inventory inside = inventoryProxy(new Location(fx.world(), 5, 64, 5));
        Inventory outside = inventoryProxy(new Location(fx.world(), 900, 64, 900));

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HOPPER_TRANSFER, PermissionState.DENY, null));
        InventoryMoveItemEvent denied = moveItemEvent(outside, inside);
        denying.onHopperTransfer(denied);
        assertTrue(denied.isCancelled());

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HOPPER_TRANSFER, PermissionState.ALLOW, null));
        InventoryMoveItemEvent allowed = moveItemEvent(outside, inside);
        allowing.onHopperTransfer(allowed);
        assertFalse(allowed.isCancelled());
    }

    @Test
    void explosionFiltersPerBlockInsteadOfCancelling() {
        Fixture fx = fixture();
        Entity tnt = cowProxy(fx.world(), 5, 64, 5);
        Block inLand = blockProxy(fx.world(), 5, 60, 5, Material.STONE);
        Block wild = blockProxy(fx.world(), 900, 60, 900, Material.STONE);
        List<Block> affected = new ArrayList<>(List.of(inLand, wild));

        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.EXPLOSION_TERRAIN, PermissionState.DENY, null));
        EntityExplodeEvent entityBoom = new EntityExplodeEvent(
                tnt, new Location(fx.world(), 5, 64, 5), affected, 3.0f, ExplosionResult.DESTROY);
        listener.onEntityExplode(entityBoom);
        assertFalse(entityBoom.isCancelled(), "one protected block must not cancel the whole blast");
        assertEquals(List.of(wild), affected, "only the protected block is filtered out");

        List<Block> affectedByBlock = new ArrayList<>(List.of(inLand, wild));
        BlockExplodeEvent blockBoom = new BlockExplodeEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.TNT), null, affectedByBlock, 3.0f,
                ExplosionResult.DESTROY);
        listener.onBlockExplode(blockBoom);
        assertFalse(blockBoom.isCancelled());
        assertEquals(List.of(wild), affectedByBlock);
    }

    @Test
    void explosionEntityDamageDenyCancelsAndOtherCausesPass() throws Exception {
        Fixture fx = fixture();
        Entity victim = cowProxy(fx.world(), 5, 64, 5);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.EXPLOSION_ENTITY, PermissionState.DENY, null));
        EntityDamageEvent denied = damageEvent(victim, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        denying.onExplosionEntityDamage(denied);
        assertTrue(denied.isCancelled());

        EntityDamageEvent blockBoom = damageEvent(victim, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION);
        denying.onExplosionEntityDamage(blockBoom);
        assertTrue(blockBoom.isCancelled());

        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.EXPLOSION_ENTITY, PermissionState.DENY, seen));
        EntityDamageEvent melee = damageEvent(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        listener.onExplosionEntityDamage(melee);
        assertFalse(melee.isCancelled(), "non-explosion damage stays vanilla here");
        assertNull(seen.get(), "non-explosion damage must not consult the engine");
    }

    @Test
    void fluidOutflowFromLandCancels() {
        Fixture fx = fixture();
        Block from = blockProxy(fx.world(), 5, 64, 5, Material.WATER);
        Block to = blockProxy(fx.world(), 900, 64, 900, Material.AIR);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FLUID_FLOW, PermissionState.DENY, null));
        BlockFromToEvent outflow = new BlockFromToEvent(from, to);
        denying.onFluidFlow(outflow);
        assertTrue(outflow.isCancelled(), "land -> wilderness outflow must cancel");
    }

    @Test
    void fluidWildernessToWildernessPasses() {
        Fixture fx = fixture();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FLUID_FLOW, PermissionState.DENY, null));
        BlockFromToEvent wild = new BlockFromToEvent(
                blockProxy(fx.world(), 900, 64, 900, Material.WATER),
                blockProxy(fx.world(), 916, 64, 900, Material.AIR));
        denying.onFluidFlow(wild);
        assertFalse(wild.isCancelled(), "wilderness flow follows vanilla: never cancel");
    }

    @Test
    void pistonPushIntoLandCancels() {
        Fixture fx = fixture();
        // Piston and pushed block both sit in the wilderness (chunk 1,0);
        // the push destination (x=15) is inside the land (chunk 0,0).
        Block piston = blockProxy(fx.world(), 17, 64, 5, Material.PISTON);
        Block moved = blockProxy(fx.world(), 16, 64, 5, Material.STONE);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.PISTON_MOVE, PermissionState.DENY, null));
        BlockPistonExtendEvent pushIn =
                new BlockPistonExtendEvent(piston, List.of(moved), BlockFace.WEST);
        denying.onPistonExtend(pushIn);
        assertTrue(pushIn.isCancelled(), "wilderness -> land push must cancel");
    }

    @Test
    void pistonRetractPullIntoLandCancels() {
        Fixture fx = fixture();
        // Sticky piston at x=14 (land) facing EAST: head at x=15, pulled
        // block at x=16 (wilderness) returns toward the piston to x=15
        // (land); the land destination DENY must cancel the pull.
        Block piston = blockProxy(fx.world(), 14, 64, 5, Material.STICKY_PISTON);
        Block moved = blockProxy(fx.world(), 16, 64, 5, Material.STONE);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.PISTON_MOVE, PermissionState.DENY, null));
        BlockPistonRetractEvent pullIn =
                new BlockPistonRetractEvent(piston, List.of(moved), BlockFace.EAST);
        denying.onPistonRetract(pullIn);
        assertTrue(pullIn.isCancelled(), "wilderness -> land pull must cancel");
    }

    @Test
    void pistonRetractPullsTowardPiston() {
        Fixture fx = fixture();

        // Sticky piston at x=14 (land) facing EAST: head at x=15, pulled
        // block at x=16 (wilderness) returns to x=16 - direction = x=15
        // (land). Land destination DENY must cancel: exactly one land
        // lookup (the destination; the source is wilderness).
        AtomicInteger landLookups = new AtomicInteger();
        ProtectionListener listener = new ProtectionListener(
                countingDenyEngine(fx.store(), ProtectionActionType.PISTON_MOVE, landLookups));
        Block piston = blockProxy(fx.world(), 14, 64, 5, Material.STICKY_PISTON);
        Block moved = blockProxy(fx.world(), 16, 64, 5, Material.STONE);
        BlockPistonRetractEvent pullIn =
                new BlockPistonRetractEvent(piston, List.of(moved), BlockFace.EAST);
        listener.onPistonRetract(pullIn);
        assertTrue(pullIn.isCancelled(), "wilderness -> land pull must cancel");
        assertEquals(1, landLookups.get(),
                "retract destination must be current - direction (x=15, land)");

        // Sticky piston at x=18 (wilderness) facing WEST: head at x=17,
        // pulled block at x=16 returns to x=17. Both ends wilderness, so
        // vanilla passes and no land lookup may happen (x=15 must not be
        // queried: that is current + direction, the extend formula).
        AtomicInteger wildLookups = new AtomicInteger();
        ProtectionListener wild = new ProtectionListener(
                countingDenyEngine(fx.store(), ProtectionActionType.PISTON_MOVE, wildLookups));
        Block wildPiston = blockProxy(fx.world(), 18, 64, 5, Material.STICKY_PISTON);
        Block wildMoved = blockProxy(fx.world(), 16, 64, 5, Material.STONE);
        BlockPistonRetractEvent wildPull =
                new BlockPistonRetractEvent(wildPiston, List.of(wildMoved), BlockFace.WEST);
        wild.onPistonRetract(wildPull);
        assertFalse(wildPull.isCancelled(),
                "wilderness -> wilderness pull follows vanilla: never cancel");
        assertEquals(0, wildLookups.get(),
                "retract must query x=17 (wilderness), never x=15 (land)");
    }

    @Test
    void pistonExtendPushesAlongDirection() {
        Fixture fx = fixture();

        // Piston at x=17 (wilderness) facing WEST pushes x=16 to
        // x=16 + direction = x=15 (land): must cancel, exactly one land
        // lookup (the destination; the source is wilderness).
        AtomicInteger inLookups = new AtomicInteger();
        ProtectionListener pushIn = new ProtectionListener(
                countingDenyEngine(fx.store(), ProtectionActionType.PISTON_MOVE, inLookups));
        Block pushPiston = blockProxy(fx.world(), 17, 64, 5, Material.PISTON);
        Block pushMoved = blockProxy(fx.world(), 16, 64, 5, Material.STONE);
        BlockPistonExtendEvent push =
                new BlockPistonExtendEvent(pushPiston, List.of(pushMoved), BlockFace.WEST);
        pushIn.onPistonExtend(push);
        assertTrue(push.isCancelled(), "wilderness -> land push must cancel");
        assertEquals(1, inLookups.get(),
                "extend destination must be current + direction (x=15, land)");

        // Piston at x=14 (land) facing EAST pushes x=15 (land) to x=16
        // (wilderness): source DENY cancels, exactly one land lookup
        // (the source; the destination is wilderness).
        AtomicInteger outLookups = new AtomicInteger();
        ProtectionListener pushOut = new ProtectionListener(
                countingDenyEngine(fx.store(), ProtectionActionType.PISTON_MOVE, outLookups));
        Block outPiston = blockProxy(fx.world(), 14, 64, 5, Material.PISTON);
        Block outMoved = blockProxy(fx.world(), 15, 64, 5, Material.STONE);
        BlockPistonExtendEvent pushOutEvent =
                new BlockPistonExtendEvent(outPiston, List.of(outMoved), BlockFace.EAST);
        pushOut.onPistonExtend(pushOutEvent);
        assertTrue(pushOutEvent.isCancelled(), "land -> wilderness push must cancel");
        assertEquals(1, outLookups.get(),
                "extend destination x=16 is wilderness: only the source is a land lookup");
    }

    @Test
    void pistonWildernessToWildernessPasses() {
        Fixture fx = fixture();
        Block piston = blockProxy(fx.world(), 918, 64, 900, Material.PISTON);
        Block moved = blockProxy(fx.world(), 917, 64, 900, Material.STONE);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.PISTON_MOVE, PermissionState.DENY, null));
        BlockPistonExtendEvent wild =
                new BlockPistonExtendEvent(piston, List.of(moved), BlockFace.WEST);
        denying.onPistonExtend(wild);
        assertFalse(wild.isCancelled(), "wilderness piston follows vanilla: never cancel");
        // Sticky piston at x=919 facing WEST: head at x=918, pulled block
        // at x=917 returns to x=918; all wilderness, so vanilla passes.
        Block wildSticky = blockProxy(fx.world(), 919, 64, 900, Material.STICKY_PISTON);
        Block wildPulled = blockProxy(fx.world(), 917, 64, 900, Material.STONE);
        BlockPistonRetractEvent wildPull =
                new BlockPistonRetractEvent(wildSticky, List.of(wildPulled), BlockFace.WEST);
        denying.onPistonRetract(wildPull);
        assertFalse(wildPull.isCancelled(), "wilderness retract follows vanilla: never cancel");
    }

    @Test
    void hopperUnknownEndpointCancels() throws Exception {
        Fixture fx = fixture();
        Inventory unknown = inventoryProxy(null);
        Inventory wild = inventoryProxy(new Location(fx.world(), 900, 64, 900));

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HOPPER_TRANSFER, PermissionState.ALLOW, null));
        InventoryMoveItemEvent noSource = moveItemEvent(unknown, wild);
        allowing.onHopperTransfer(noSource);
        assertTrue(noSource.isCancelled(), "unlocatable source must fail closed even when ALLOW");

        InventoryMoveItemEvent noDestination = moveItemEvent(wild, unknown);
        allowing.onHopperTransfer(noDestination);
        assertTrue(noDestination.isCancelled(), "unlocatable destination must fail closed");
    }

    @Test
    void hopperProtectedSourceToWildernessCancels() throws Exception {
        Fixture fx = fixture();
        Inventory inside = inventoryProxy(new Location(fx.world(), 5, 64, 5));
        Inventory outside = inventoryProxy(new Location(fx.world(), 900, 64, 900));

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HOPPER_TRANSFER, PermissionState.DENY, null));
        InventoryMoveItemEvent outflow = moveItemEvent(inside, outside);
        denying.onHopperTransfer(outflow);
        assertTrue(outflow.isCancelled(), "protected source -> wilderness must cancel");
    }

    @Test
    void hopperWildernessToWildernessPasses() throws Exception {
        Fixture fx = fixture();
        Inventory first = inventoryProxy(new Location(fx.world(), 900, 64, 900));
        Inventory second = inventoryProxy(new Location(fx.world(), 916, 64, 900));

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HOPPER_TRANSFER, PermissionState.DENY, null));
        InventoryMoveItemEvent wild = moveItemEvent(first, second);
        denying.onHopperTransfer(wild);
        assertFalse(wild.isCancelled(), "wilderness hoppers follow vanilla: never cancel");
    }

    @SuppressWarnings("unchecked")
    private static Set<Material> materialSet(String field) throws Exception {
        Field declared = ProtectionListener.class.getDeclaredField(field);
        declared.setAccessible(true);
        return (Set<Material>) declared.get(null);
    }

    @Test
    void interactActionCoversEveryShulkerBox() throws Exception {
        List<Material> expected = List.of(
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
        assertEquals(17, materialSet("SHULKER_TYPES").size(),
                "all 17 shulker variants must be classified");
        for (Material kind : expected) {
            assertTrue(materialSet("SHULKER_TYPES").contains(kind),
                    "missing shulker classification: " + kind);
            assertEquals(ProtectionActionType.CONTAINER_OPEN,
                    ProtectionListener.interactAction(kind),
                    kind + " must route to CONTAINER_OPEN");
        }
    }

    @Test
    void interactActionCoversEveryCopperDoor() throws Exception {
        List<Material> expected = List.of(
                Material.COPPER_DOOR,
                Material.EXPOSED_COPPER_DOOR,
                Material.WEATHERED_COPPER_DOOR,
                Material.OXIDIZED_COPPER_DOOR,
                Material.WAXED_COPPER_DOOR,
                Material.WAXED_EXPOSED_COPPER_DOOR,
                Material.WAXED_WEATHERED_COPPER_DOOR,
                Material.WAXED_OXIDIZED_COPPER_DOOR,
                Material.COPPER_TRAPDOOR,
                Material.EXPOSED_COPPER_TRAPDOOR,
                Material.WEATHERED_COPPER_TRAPDOOR,
                Material.OXIDIZED_COPPER_TRAPDOOR,
                Material.WAXED_COPPER_TRAPDOOR,
                Material.WAXED_EXPOSED_COPPER_TRAPDOOR,
                Material.WAXED_WEATHERED_COPPER_TRAPDOOR,
                Material.WAXED_OXIDIZED_COPPER_TRAPDOOR);
        for (Material kind : expected) {
            assertTrue(materialSet("DOOR_TYPES").contains(kind),
                    "missing door classification: " + kind);
            assertEquals(ProtectionActionType.DOOR_USE,
                    ProtectionListener.interactAction(kind),
                    kind + " must route to DOOR_USE");
        }
    }

    @Test
    void interactActionCoversPaleOakSeries() throws Exception {
        for (Material kind : List.of(
                Material.PALE_OAK_DOOR,
                Material.PALE_OAK_TRAPDOOR,
                Material.PALE_OAK_FENCE_GATE)) {
            assertTrue(materialSet("DOOR_TYPES").contains(kind),
                    "missing door classification: " + kind);
            assertEquals(ProtectionActionType.DOOR_USE,
                    ProtectionListener.interactAction(kind),
                    kind + " must route to DOOR_USE");
        }
        assertTrue(materialSet("BUTTON_TYPES").contains(Material.PALE_OAK_BUTTON),
                "missing button classification: PALE_OAK_BUTTON");
        assertEquals(ProtectionActionType.BUTTON_USE,
                ProtectionListener.interactAction(Material.PALE_OAK_BUTTON));
    }

    @Test
    void decisionFailureCancelsNewHandlers() throws Exception {
        Fixture fx = fixture();
        ProtectionEngine exploding = new ProtectionEngine(fx.store()::snapshot,
                (actor, id, action, snapshot) -> {
                    throw new RuntimeException("decision backend boom");
                });
        ProtectionListener listener = new ProtectionListener(exploding);
        Player player = playerProxy(UUID.randomUUID(), fx.world());

        BlockPlaceEvent place = placeEvent(blockProxy(fx.world(), 1, 64, 1, Material.STONE), player);
        listener.onBlockPlace(place);
        assertTrue(place.isCancelled());

        PlayerInteractEvent use = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                null, blockProxy(fx.world(), 1, 64, 1, Material.CHEST), BlockFace.UP);
        listener.onPlayerInteract(use);
        assertTrue(use.isCancelled());

        BlockFromToEvent flow = new BlockFromToEvent(blockProxy(fx.world(), 1, 64, 1, Material.WATER),
                BlockFace.EAST);
        listener.onFluidFlow(flow);
        assertTrue(flow.isCancelled());

        EntityDamageEvent boom = damageEvent(cowProxy(fx.world(), 1, 64, 1),
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        listener.onExplosionEntityDamage(boom);
        assertTrue(boom.isCancelled());
    }
}
