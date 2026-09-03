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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

/**
 * Covers the eight remaining P0 behaviours: ENTRY, VEHICLE_USE, ITEM_FRAME,
 * ARMOR_STAND, HANGING_ENTITY, FARMLAND_TRAMPLE, FIRE_SPREAD, and FIRE_BURN.
 */
class ProtectionP0RemainingListenerTest {

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

    private static BlockState blockStateProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (BlockState) Proxy.newProxyInstance(BlockState.class.getClassLoader(),
                new Class[]{BlockState.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld": return world;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeBlockState";
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

    private static ItemFrame itemFrameProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (ItemFrame) Proxy.newProxyInstance(ItemFrame.class.getClassLoader(),
                new Class[]{ItemFrame.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return UUID.randomUUID();
                        case "getWorld": return world;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeItemFrame";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private static Painting paintingProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Painting) Proxy.newProxyInstance(Painting.class.getClassLoader(),
                new Class[]{Painting.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return UUID.randomUUID();
                        case "getWorld": return world;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePainting";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private static ArmorStand armorStandProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (ArmorStand) Proxy.newProxyInstance(ArmorStand.class.getClassLoader(),
                new Class[]{ArmorStand.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return UUID.randomUUID();
                        case "getWorld": return world;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeArmorStand";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private static Vehicle vehicleProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Vehicle) Proxy.newProxyInstance(Vehicle.class.getClassLoader(),
                new Class[]{Vehicle.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return UUID.randomUUID();
                        case "getWorld": return world;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeVehicle";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (sun.misc.Unsafe) unsafeField.get(null);
    }

    private static void setPlayer(org.bukkit.event.Event event, Player player) throws Exception {
        Field playerField = PlayerEvent.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(event, player);
    }

    private static void setClickedEntity(PlayerInteractEntityEvent event, Entity entity)
            throws Exception {
        Field field = PlayerInteractEntityEvent.class.getDeclaredField("clickedEntity");
        field.setAccessible(true);
        field.set(event, entity);
    }

    private static PlayerArmorStandManipulateEvent armorStandEvent(Player player, ArmorStand stand)
            throws Exception {
        PlayerArmorStandManipulateEvent event = (PlayerArmorStandManipulateEvent)
                unsafe().allocateInstance(PlayerArmorStandManipulateEvent.class);
        setPlayer(event, player);
        setClickedEntity(event, stand);
        return event;
    }

    @SuppressWarnings("deprecation")
    private static HangingPlaceEvent hangingPlaceEvent(Hanging hanging, Player player, Block block) {
        return new HangingPlaceEvent(hanging, player, block, BlockFace.UP, EquipmentSlot.HAND);
    }

    private static HangingBreakByEntityEvent hangingBreakEvent(Hanging hanging, Entity remover)
            throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        HangingBreakByEntityEvent event = (HangingBreakByEntityEvent)
                unsafe.allocateInstance(HangingBreakByEntityEvent.class);
        long hangingOffset = unsafe.objectFieldOffset(
                org.bukkit.event.hanging.HangingEvent.class.getDeclaredField("hanging"));
        unsafe.putObject(event, hangingOffset, hanging);
        long removerOffset = unsafe.objectFieldOffset(
                HangingBreakByEntityEvent.class.getDeclaredField("remover"));
        unsafe.putObject(event, removerOffset, remover);
        long causeOffset = unsafe.objectFieldOffset(
                HangingBreakEvent.class.getDeclaredField("cause"));
        unsafe.putObject(event, causeOffset, HangingBreakEvent.RemoveCause.ENTITY);
        return event;
    }

    private static VehicleDamageEvent vehicleDamageEvent(Vehicle vehicle, Entity attacker)
            throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        VehicleDamageEvent event = (VehicleDamageEvent)
                unsafe.allocateInstance(VehicleDamageEvent.class);
        Field vehicleField = org.bukkit.event.vehicle.VehicleEvent.class.getDeclaredField("vehicle");
        vehicleField.setAccessible(true);
        vehicleField.set(event, vehicle);
        long attackerOffset = unsafe.objectFieldOffset(
                VehicleDamageEvent.class.getDeclaredField("attacker"));
        unsafe.putObject(event, attackerOffset, attacker);
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

    private static ProtectionEngine countingEngine(LandRegistryStore store,
                                                   ProtectionActionType action,
                                                   AtomicInteger lookups) {
        return new ProtectionEngine(store::snapshot, (actor, landId, a, snapshot) -> {
            lookups.incrementAndGet();
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

    private record Fixture(UUID worldId, LandId landId, UUID owner, LandRegistryStore store, World world) {
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
    void farmlandTrampleDeclaresSubjectPermissionAndIsRegistered() {
        assertEquals(DecisionSource.SUBJECT_PERMISSION,
                ProtectionActionType.FARMLAND_TRAMPLE.decisionSource());
        var routes = ProtectionActionRegistry.validated(ProtectionActionRegistry.defaults());
        assertEquals(DecisionSource.SUBJECT_PERMISSION, routes.get(ProtectionActionType.FARMLAND_TRAMPLE));
    }

    @Test
    void entryMoveAcrossChunksDenyCancelsAndAllowPasses() {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        Location from = new Location(fx.world(), 900, 64, 900);
        Location to = new Location(fx.world(), 5, 64, 5);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ENTRY, PermissionState.DENY, null));
        PlayerMoveEvent denied = new PlayerMoveEvent(stranger, from, to);
        denying.onPlayerMove(denied);
        assertTrue(denied.isCancelled(), "cross-chunk ENTRY DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ENTRY, PermissionState.ALLOW, null));
        PlayerMoveEvent allowed = new PlayerMoveEvent(stranger, from,
                new Location(fx.world(), 5, 64, 5));
        allowing.onPlayerMove(allowed);
        assertFalse(allowed.isCancelled(), "cross-chunk ENTRY ALLOW must pass");
    }

    @Test
    void entryMoveInsideSameChunkNeverConsultsEngine() {
        Fixture fx = fixture();
        AtomicInteger lookups = new AtomicInteger();
        ProtectionListener listener = new ProtectionListener(
                countingEngine(fx.store(), ProtectionActionType.ENTRY, lookups));
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        PlayerMoveEvent event = new PlayerMoveEvent(stranger,
                new Location(fx.world(), 5, 64, 5),
                new Location(fx.world(), 6, 64, 6));
        listener.onPlayerMove(event);
        assertFalse(event.isCancelled(), "same-chunk movement must follow vanilla");
        assertEquals(0, lookups.get(), "same-chunk movement must not consult the engine");
    }

    @Test
    void entryMoveWildernessToWildernessPasses() {
        Fixture fx = fixture();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ENTRY, PermissionState.DENY, null));
        PlayerMoveEvent event = new PlayerMoveEvent(
                playerProxy(UUID.randomUUID(), fx.world()),
                new Location(fx.world(), 900, 64, 900),
                new Location(fx.world(), 916, 64, 900));
        denying.onPlayerMove(event);
        assertFalse(event.isCancelled(), "wilderness movement follows vanilla: never cancel");
    }

    @Test
    void entryTeleportDestinationDenyCancelsAndWildernessPasses() {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        Location from = new Location(fx.world(), 900, 64, 900);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ENTRY, PermissionState.DENY, null));
        PlayerTeleportEvent denied = new PlayerTeleportEvent(stranger, from,
                new Location(fx.world(), 5, 64, 5));
        denying.onPlayerTeleport(denied);
        assertTrue(denied.isCancelled(), "teleport into a denying land must cancel");

        PlayerTeleportEvent wild = new PlayerTeleportEvent(stranger, from,
                new Location(fx.world(), 916, 64, 916));
        denying.onPlayerTeleport(wild);
        assertFalse(wild.isCancelled(), "teleport across wilderness follows vanilla");
    }

    @Test
    void realProviderGrantsOwnerEntryAndDeniesStranger() {
        Fixture fx = fixture();
        var provider = new SnapshotPermissionContextProvider(null, null);
        ProtectionListener listener =
                new ProtectionListener(new ProtectionEngine(fx.store()::snapshot, provider));
        Location from = new Location(fx.world(), 900, 64, 900);
        Location to = new Location(fx.world(), 5, 64, 5);

        PlayerMoveEvent ownerEvent =
                new PlayerMoveEvent(playerProxy(fx.owner(), fx.world()), from, to);
        listener.onPlayerMove(ownerEvent);
        assertFalse(ownerEvent.isCancelled(), "owner entering own land: must pass");

        PlayerMoveEvent strangerEvent =
                new PlayerMoveEvent(playerProxy(UUID.randomUUID(), fx.world()), from, to);
        listener.onPlayerMove(strangerEvent);
        assertTrue(strangerEvent.isCancelled(), "stranger entering a land: must cancel");
    }

    @Test
    void vehicleEnterDenyCancelsAndAllowPasses() {
        Fixture fx = fixture();
        Vehicle vehicle = vehicleProxy(fx.world(), 5, 64, 5);
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.VEHICLE_USE, PermissionState.DENY, null));
        VehicleEnterEvent denied = new VehicleEnterEvent(vehicle, stranger);
        denying.onVehicleEnter(denied);
        assertTrue(denied.isCancelled(), "VEHICLE_USE DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.VEHICLE_USE, PermissionState.ALLOW, null));
        VehicleEnterEvent allowed = new VehicleEnterEvent(vehicle, stranger);
        allowing.onVehicleEnter(allowed);
        assertFalse(allowed.isCancelled(), "VEHICLE_USE ALLOW must pass");
    }

    @Test
    void vehicleEnterWildernessPassesAndNonPlayerStaysVanilla() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.VEHICLE_USE, PermissionState.DENY, seen));

        Vehicle wildVehicle = vehicleProxy(fx.world(), 900, 64, 900);
        VehicleEnterEvent wild = new VehicleEnterEvent(wildVehicle,
                playerProxy(UUID.randomUUID(), fx.world()));
        listener.onVehicleEnter(wild);
        assertFalse(wild.isCancelled(), "wilderness vehicle entry follows vanilla");

        Vehicle landVehicle = vehicleProxy(fx.world(), 5, 64, 5);
        VehicleEnterEvent mob = new VehicleEnterEvent(landVehicle,
                cowProxy(fx.world(), 5, 64, 5));
        listener.onVehicleEnter(mob);
        assertFalse(mob.isCancelled(), "non-player entry stays vanilla here");
        assertNull(seen.get(), "non-player entry must not consult the engine");
    }

    @Test
    void vehicleDamageByPlayerDenyCancels() throws Exception {
        Fixture fx = fixture();
        Vehicle vehicle = vehicleProxy(fx.world(), 5, 64, 5);
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.VEHICLE_USE, PermissionState.DENY, null));
        VehicleDamageEvent denied = vehicleDamageEvent(vehicle, stranger);
        denying.onVehicleDamage(denied);
        assertTrue(denied.isCancelled(), "breaking a vehicle in a denying land must cancel");

        Vehicle wildVehicle = vehicleProxy(fx.world(), 900, 64, 900);
        VehicleDamageEvent wild = vehicleDamageEvent(wildVehicle, stranger);
        denying.onVehicleDamage(wild);
        assertFalse(wild.isCancelled(), "wilderness vehicle damage follows vanilla");
    }

    @Test
    void itemFrameInteractDenyCancelsAndWildernessPasses() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        ItemFrame frame = itemFrameProxy(fx.world(), 5, 64, 5);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ITEM_FRAME, PermissionState.DENY, seen));
        PlayerInteractEntityEvent denied = new PlayerInteractEntityEvent(stranger, frame);
        denying.onPlayerInteractEntity(denied);
        assertTrue(denied.isCancelled(), "ITEM_FRAME DENY must cancel");
        assertEquals(ProtectionActionType.ITEM_FRAME, seen.get());

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ITEM_FRAME, PermissionState.ALLOW, null));
        PlayerInteractEntityEvent allowed = new PlayerInteractEntityEvent(stranger, frame);
        allowing.onPlayerInteractEntity(allowed);
        assertFalse(allowed.isCancelled(), "ITEM_FRAME ALLOW must pass");

        ItemFrame wildFrame = itemFrameProxy(fx.world(), 900, 64, 900);
        PlayerInteractEntityEvent wild = new PlayerInteractEntityEvent(stranger, wildFrame);
        denying.onPlayerInteractEntity(wild);
        assertFalse(wild.isCancelled(), "wilderness item frame follows vanilla");
    }

    @Test
    void realProviderGrantsOwnerItemFrameAndDeniesStranger() {
        Fixture fx = fixture();
        var provider = new SnapshotPermissionContextProvider(null, null);
        ProtectionListener listener =
                new ProtectionListener(new ProtectionEngine(fx.store()::snapshot, provider));
        ItemFrame frame = itemFrameProxy(fx.world(), 5, 64, 5);

        PlayerInteractEntityEvent ownerEvent =
                new PlayerInteractEntityEvent(playerProxy(fx.owner(), fx.world()), frame);
        listener.onPlayerInteractEntity(ownerEvent);
        assertFalse(ownerEvent.isCancelled(), "owner uses own item frame: must pass");

        PlayerInteractEntityEvent strangerEvent = new PlayerInteractEntityEvent(
                playerProxy(UUID.randomUUID(), fx.world()), frame);
        listener.onPlayerInteractEntity(strangerEvent);
        assertTrue(strangerEvent.isCancelled(), "stranger uses a land item frame: must cancel");
    }

    @Test
    void armorStandManipulateDenyCancelsAndWildernessPasses() throws Exception {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        ArmorStand stand = armorStandProxy(fx.world(), 5, 64, 5);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ARMOR_STAND, PermissionState.DENY, null));
        PlayerArmorStandManipulateEvent denied = armorStandEvent(stranger, stand);
        denying.onArmorStandManipulate(denied);
        assertTrue(denied.isCancelled(), "ARMOR_STAND DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ARMOR_STAND, PermissionState.ALLOW, null));
        PlayerArmorStandManipulateEvent allowed = armorStandEvent(stranger, stand);
        allowing.onArmorStandManipulate(allowed);
        assertFalse(allowed.isCancelled(), "ARMOR_STAND ALLOW must pass");

        ArmorStand wildStand = armorStandProxy(fx.world(), 900, 64, 900);
        PlayerArmorStandManipulateEvent wild = armorStandEvent(stranger, wildStand);
        denying.onArmorStandManipulate(wild);
        assertFalse(wild.isCancelled(), "wilderness armor stand follows vanilla");
    }

    @Test
    void hangingPlaceRoutesByHangingKind() throws Exception {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        Block support = blockProxy(fx.world(), 5, 64, 5, Material.STONE);

        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denyingFrame = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ITEM_FRAME, PermissionState.DENY, seen));
        HangingPlaceEvent framePlace =
                hangingPlaceEvent(itemFrameProxy(fx.world(), 5, 65, 5), stranger, support);
        denyingFrame.onHangingPlace(framePlace);
        assertTrue(framePlace.isCancelled(), "item frame placement DENY must cancel");
        assertEquals(ProtectionActionType.ITEM_FRAME, seen.get());

        ProtectionListener denyingHanging = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HANGING_ENTITY, PermissionState.DENY, seen));
        HangingPlaceEvent paintingPlace =
                hangingPlaceEvent(paintingProxy(fx.world(), 5, 65, 5), stranger, support);
        denyingHanging.onHangingPlace(paintingPlace);
        assertTrue(paintingPlace.isCancelled(), "painting placement DENY must cancel");
        assertEquals(ProtectionActionType.HANGING_ENTITY, seen.get());

        Block wildSupport = blockProxy(fx.world(), 900, 64, 900, Material.STONE);
        HangingPlaceEvent wild = hangingPlaceEvent(
                paintingProxy(fx.world(), 900, 65, 900), stranger, wildSupport);
        denyingHanging.onHangingPlace(wild);
        assertFalse(wild.isCancelled(), "wilderness hanging placement follows vanilla");
    }

    @Test
    void hangingBreakByPlayerDenyCancelsAndNonPlayerStaysVanilla() throws Exception {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HANGING_ENTITY, PermissionState.DENY, null));
        HangingBreakByEntityEvent denied =
                hangingBreakEvent(paintingProxy(fx.world(), 5, 64, 5), stranger);
        denying.onHangingBreak(denied);
        assertTrue(denied.isCancelled(), "HANGING_ENTITY DENY must cancel");

        HangingBreakByEntityEvent wild =
                hangingBreakEvent(paintingProxy(fx.world(), 900, 64, 900), stranger);
        denying.onHangingBreak(wild);
        assertFalse(wild.isCancelled(), "wilderness hanging break follows vanilla");

        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.HANGING_ENTITY, PermissionState.DENY, seen));
        HangingBreakByEntityEvent mobBreak = hangingBreakEvent(
                paintingProxy(fx.world(), 5, 64, 5), cowProxy(fx.world(), 5, 64, 5));
        listener.onHangingBreak(mobBreak);
        assertFalse(mobBreak.isCancelled(), "non-player break stays vanilla here");
        assertNull(seen.get(), "non-player break must not consult the engine");
    }

    @Test
    void hangingBreakRoutesItemFrameSeparately() throws Exception {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();

        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ITEM_FRAME, PermissionState.DENY, seen));
        HangingBreakByEntityEvent broken =
                hangingBreakEvent(itemFrameProxy(fx.world(), 5, 64, 5), stranger);
        listener.onHangingBreak(broken);
        assertTrue(broken.isCancelled(), "item frame break DENY must cancel");
        assertEquals(ProtectionActionType.ITEM_FRAME, seen.get());
    }

    @Test
    void farmlandTramplePhysicalDenyCancelsAndWildernessPasses() {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        Block soil = blockProxy(fx.world(), 5, 64, 5, Material.FARMLAND);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FARMLAND_TRAMPLE, PermissionState.DENY, null));
        PlayerInteractEvent denied =
                new PlayerInteractEvent(stranger, Action.PHYSICAL, null, soil, BlockFace.UP);
        denying.onPlayerInteract(denied);
        assertTrue(denied.isCancelled(), "FARMLAND_TRAMPLE DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FARMLAND_TRAMPLE, PermissionState.ALLOW, null));
        PlayerInteractEvent allowed =
                new PlayerInteractEvent(stranger, Action.PHYSICAL, null, soil, BlockFace.UP);
        allowing.onPlayerInteract(allowed);
        assertFalse(allowed.isCancelled(), "FARMLAND_TRAMPLE ALLOW must pass");

        Block wildSoil = blockProxy(fx.world(), 900, 64, 900, Material.FARMLAND);
        PlayerInteractEvent wild =
                new PlayerInteractEvent(stranger, Action.PHYSICAL, null, wildSoil, BlockFace.UP);
        denying.onPlayerInteract(wild);
        assertFalse(wild.isCancelled(), "wilderness trample follows vanilla");
    }

    @Test
    void physicalOnNonFarmlandStaysVanilla() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FARMLAND_TRAMPLE, PermissionState.DENY, seen));
        PlayerInteractEvent plate = new PlayerInteractEvent(
                playerProxy(UUID.randomUUID(), fx.world()), Action.PHYSICAL, null,
                blockProxy(fx.world(), 5, 64, 5, Material.STONE_PRESSURE_PLATE), BlockFace.UP);
        listener.onPlayerInteract(plate);
        assertFalse(plate.isCancelled(), "pressure plates stay vanilla");
        assertNull(seen.get(), "non-farmland PHYSICAL must not consult the engine");
    }

    @Test
    void entityTrampleDenyCancelsAndWildernessPasses() {
        Fixture fx = fixture();
        Block soil = blockProxy(fx.world(), 5, 64, 5, Material.FARMLAND);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FARMLAND_TRAMPLE, PermissionState.DENY, null));
        EntityInteractEvent denied =
                new EntityInteractEvent(cowProxy(fx.world(), 5, 64, 5), soil);
        denying.onEntityInteract(denied);
        assertTrue(denied.isCancelled(), "mob trample DENY must cancel");

        EntityInteractEvent wild = new EntityInteractEvent(cowProxy(fx.world(), 900, 64, 900),
                blockProxy(fx.world(), 900, 64, 900, Material.FARMLAND));
        denying.onEntityInteract(wild);
        assertFalse(wild.isCancelled(), "wilderness mob trample follows vanilla");
    }

    @Test
    void realProviderGrantsOwnerTrampleAndDeniesStranger() {
        Fixture fx = fixture();
        var provider = new SnapshotPermissionContextProvider(null, null);
        ProtectionListener listener =
                new ProtectionListener(new ProtectionEngine(fx.store()::snapshot, provider));
        Block soil = blockProxy(fx.world(), 5, 64, 5, Material.FARMLAND);

        PlayerInteractEvent ownerEvent = new PlayerInteractEvent(
                playerProxy(fx.owner(), fx.world()), Action.PHYSICAL, null, soil, BlockFace.UP);
        listener.onPlayerInteract(ownerEvent);
        assertFalse(ownerEvent.isCancelled(), "owner trampling own soil: must pass");

        PlayerInteractEvent strangerEvent = new PlayerInteractEvent(
                playerProxy(UUID.randomUUID(), fx.world()), Action.PHYSICAL, null, soil, BlockFace.UP);
        listener.onPlayerInteract(strangerEvent);
        assertTrue(strangerEvent.isCancelled(), "stranger trampling land soil: must cancel");
    }

    @Test
    void fireSpreadDenyCancelsAndWildernessPasses() {
        Fixture fx = fixture();
        Block source = blockProxy(fx.world(), 5, 64, 5, Material.FIRE);
        Block target = blockProxy(fx.world(), 6, 64, 5, Material.AIR);

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.DENY, null));
        BlockSpreadEvent denied = new BlockSpreadEvent(target, source,
                blockStateProxy(fx.world(), 6, 64, 5));
        denying.onFireSpread(denied);
        assertTrue(denied.isCancelled(), "FIRE_SPREAD DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.ALLOW, null));
        BlockSpreadEvent allowed = new BlockSpreadEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.AIR),
                blockProxy(fx.world(), 4, 64, 5, Material.FIRE),
                blockStateProxy(fx.world(), 5, 64, 5));
        allowing.onFireSpread(allowed);
        assertFalse(allowed.isCancelled(), "FIRE_SPREAD ALLOW must pass");

        BlockSpreadEvent wild = new BlockSpreadEvent(
                blockProxy(fx.world(), 916, 64, 900, Material.AIR),
                blockProxy(fx.world(), 915, 64, 900, Material.FIRE),
                blockStateProxy(fx.world(), 916, 64, 900));
        denying.onFireSpread(wild);
        assertFalse(wild.isCancelled(), "wilderness fire spread follows vanilla");
    }

    @Test
    void nonFireSpreadPassesEvenWhenFireSpreadDeny() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.DENY, seen));
        // grass spreading to dirt: no flame on either end, must stay vanilla.
        BlockSpreadEvent grass = new BlockSpreadEvent(
                blockProxy(fx.world(), 6, 64, 5, Material.GRASS_BLOCK),
                blockProxy(fx.world(), 5, 64, 5, Material.GRASS_BLOCK),
                blockStateProxy(fx.world(), 6, 64, 5));
        denying.onFireSpread(grass);
        assertFalse(grass.isCancelled(),
                "non-fire spread must follow vanilla even when FIRE_SPREAD is DENY");
        assertNull(seen.get(), "non-fire spread must not consult the protection engine");
    }

    @Test
    void fireBurnDenyCancelsAndWildernessPasses() {
        Fixture fx = fixture();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_BURN, PermissionState.DENY, null));
        BlockBurnEvent denied = new BlockBurnEvent(blockProxy(fx.world(), 5, 64, 5, Material.OAK_PLANKS));
        denying.onFireBurn(denied);
        assertTrue(denied.isCancelled(), "FIRE_BURN DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_BURN, PermissionState.ALLOW, null));
        BlockBurnEvent allowed = new BlockBurnEvent(blockProxy(fx.world(), 5, 64, 5, Material.OAK_PLANKS));
        allowing.onFireBurn(allowed);
        assertFalse(allowed.isCancelled(), "FIRE_BURN ALLOW must pass");

        BlockBurnEvent wild = new BlockBurnEvent(blockProxy(fx.world(), 900, 64, 900, Material.OAK_PLANKS));
        denying.onFireBurn(wild);
        assertFalse(wild.isCancelled(), "wilderness fire burn follows vanilla");
    }

    @Test
    void decisionFailureCancelsRemainingHandlers() throws Exception {
        Fixture fx = fixture();
        ProtectionEngine exploding = new ProtectionEngine(fx.store()::snapshot,
                (actor, id, action, snapshot) -> {
                    throw new RuntimeException("decision backend boom");
                });
        ProtectionListener listener = new ProtectionListener(exploding);
        Player player = playerProxy(UUID.randomUUID(), fx.world());

        PlayerMoveEvent move = new PlayerMoveEvent(player,
                new Location(fx.world(), 900, 64, 900), new Location(fx.world(), 1, 64, 1));
        listener.onPlayerMove(move);
        assertTrue(move.isCancelled(), "ENTRY decision failure must fail closed");

        PlayerTeleportEvent teleport = new PlayerTeleportEvent(player,
                new Location(fx.world(), 900, 64, 900), new Location(fx.world(), 1, 64, 1));
        listener.onPlayerTeleport(teleport);
        assertTrue(teleport.isCancelled(), "teleport decision failure must fail closed");

        VehicleEnterEvent enter =
                new VehicleEnterEvent(vehicleProxy(fx.world(), 1, 64, 1), player);
        listener.onVehicleEnter(enter);
        assertTrue(enter.isCancelled(), "vehicle decision failure must fail closed");

        PlayerInteractEntityEvent frame = new PlayerInteractEntityEvent(
                player, itemFrameProxy(fx.world(), 1, 64, 1));
        listener.onPlayerInteractEntity(frame);
        assertTrue(frame.isCancelled(), "item frame decision failure must fail closed");

        PlayerArmorStandManipulateEvent stand =
                armorStandEvent(player, armorStandProxy(fx.world(), 1, 64, 1));
        listener.onArmorStandManipulate(stand);
        assertTrue(stand.isCancelled(), "armor stand decision failure must fail closed");

        HangingPlaceEvent place = hangingPlaceEvent(paintingProxy(fx.world(), 1, 65, 1), player,
                blockProxy(fx.world(), 1, 64, 1, Material.STONE));
        listener.onHangingPlace(place);
        assertTrue(place.isCancelled(), "hanging decision failure must fail closed");

        HangingBreakByEntityEvent broken =
                hangingBreakEvent(paintingProxy(fx.world(), 1, 64, 1), player);
        listener.onHangingBreak(broken);
        assertTrue(broken.isCancelled(), "hanging break decision failure must fail closed");

        PlayerInteractEvent trample = new PlayerInteractEvent(player, Action.PHYSICAL, null,
                blockProxy(fx.world(), 1, 64, 1, Material.FARMLAND), BlockFace.UP);
        listener.onPlayerInteract(trample);
        assertTrue(trample.isCancelled(), "trample decision failure must fail closed");

        BlockSpreadEvent spread = new BlockSpreadEvent(
                blockProxy(fx.world(), 1, 64, 1, Material.AIR),
                blockProxy(fx.world(), 1, 64, 2, Material.FIRE),
                blockStateProxy(fx.world(), 1, 64, 1));
        listener.onFireSpread(spread);
        assertTrue(spread.isCancelled(), "fire spread decision failure must fail closed");

        BlockBurnEvent burn =
                new BlockBurnEvent(blockProxy(fx.world(), 1, 64, 1, Material.OAK_PLANKS));
        listener.onFireBurn(burn);
        assertTrue(burn.isCancelled(), "fire burn decision failure must fail closed");
    }
}
