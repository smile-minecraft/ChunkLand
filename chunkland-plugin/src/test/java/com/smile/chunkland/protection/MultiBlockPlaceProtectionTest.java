package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionDefaultsCache.ConfigView;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

/**
 * Multi-block placement (beds, doors, and any single action placing several
 * blocks) must deny when any affected part lands in denied space.
 *
 * <p>Built against the real target-version event shape:
 * {@code BlockMultiPlaceEvent} extends {@code BlockPlaceEvent} and reports
 * every affected part through {@code getReplacedBlockStates()}, while the
 * primary block stays available through {@code getBlockPlaced()}.
 */
class MultiBlockPlaceProtectionTest {

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

    private static BlockState stateProxy(Block block) {
        return (BlockState) Proxy.newProxyInstance(BlockState.class.getClassLoader(),
                new Class[]{BlockState.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBlock": return block;
                        case "getWorld": return block.getWorld();
                        case "getX": return block.getX();
                        case "getY": return block.getY();
                        case "getZ": return block.getZ();
                        case "getType": return block.getType();
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
                        case "getWorld": return world;
                        case "getName": return "TestPlayer";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            if (rt == float.class) return 0f;
                            return null;
                    }
                });
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static BlockPlaceEvent placeEvent(Block placed, Player player) throws Exception {
        BlockPlaceEvent event =
                (BlockPlaceEvent) unsafe().allocateInstance(BlockPlaceEvent.class);
        Field blockField = BlockEvent.class.getDeclaredField("block");
        blockField.setAccessible(true);
        blockField.set(event, placed);
        Field playerField = BlockPlaceEvent.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(event, player);
        return event;
    }

    private static BlockMultiPlaceEvent multiPlaceEvent(Block placed, Player player,
            List<BlockState> replaced) throws Exception {
        BlockMultiPlaceEvent event =
                (BlockMultiPlaceEvent) unsafe().allocateInstance(BlockMultiPlaceEvent.class);
        Field blockField = BlockEvent.class.getDeclaredField("block");
        blockField.setAccessible(true);
        blockField.set(event, placed);
        Field playerField = BlockPlaceEvent.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(event, player);
        Field replacedField = BlockMultiPlaceEvent.class.getDeclaredField("replacedStates");
        replacedField.setAccessible(true);
        replacedField.set(event, replaced);
        return event;
    }

    private static ProtectionEngine denyOnLandEngine(LandRegistryStore store) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (action.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, PermissionState.DENY);
            }
            if (action != ProtectionActionType.BLOCK_PLACE) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            return new PermissionContext(action, false, List.of(),
                    PermissionState.DENY, PermissionState.INHERIT);
        });
    }

    private record Fixture(UUID worldId, LandId landId, UUID owner,
            LandRegistryStore store, World world) {
    }

    private static Fixture landFixture() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, Instant.now(), Instant.now()))));
        return new Fixture(worldId, landId, owner, store, worldProxy(worldId));
    }

    @Test
    void bedAcrossLandBorderDenied() throws Exception {
        Fixture fx = landFixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        ProtectionListener listener = new ProtectionListener(denyOnLandEngine(fx.store()));
        // Bed foot in the wilderness (chunk 1,0), head inside the land (chunk 0,0):
        // the primary block alone looks innocent.
        Block foot = blockProxy(fx.world(), 16, 64, 5, Material.RED_BED);
        Block head = blockProxy(fx.world(), 15, 64, 5, Material.RED_BED);
        BlockMultiPlaceEvent event = multiPlaceEvent(foot, stranger,
                List.of(stateProxy(foot), stateProxy(head)));
        listener.onBlockMultiPlace(event);
        assertTrue(event.isCancelled(),
                "bed with its head inside denied land must cancel even though the foot is wild");
    }

    @Test
    void singlePlaceHandlerLeavesMultiPlaceToItsOwnHandler() throws Exception {
        Fixture fx = landFixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        ProtectionListener listener = new ProtectionListener(denyOnLandEngine(fx.store()));
        Block foot = blockProxy(fx.world(), 16, 64, 5, Material.RED_BED);
        Block head = blockProxy(fx.world(), 15, 64, 5, Material.RED_BED);
        BlockMultiPlaceEvent event = multiPlaceEvent(foot, stranger,
                List.of(stateProxy(foot), stateProxy(head)));
        listener.onBlockPlace(event);
        assertFalse(event.isCancelled(),
                "the single-place path must not handle multi-place instances: "
                        + "both handlers fire for one placement, exactly one may decide");
    }

    @Test
    void multiPlaceFullyInsideAllowedSpacePasses() throws Exception {
        Fixture fx = landFixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        ProtectionListener listener = new ProtectionListener(denyOnLandEngine(fx.store()));
        Block foot = blockProxy(fx.world(), 900, 64, 900, Material.RED_BED);
        Block head = blockProxy(fx.world(), 901, 64, 900, Material.RED_BED);
        BlockMultiPlaceEvent event = multiPlaceEvent(foot, stranger,
                List.of(stateProxy(foot), stateProxy(head)));
        listener.onBlockMultiPlace(event);
        assertFalse(event.isCancelled(), "multi-place clear of every land must still pass");
    }

    @Test
    void multiPlaceWithMissingPartListCancels() throws Exception {
        Fixture fx = landFixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        ProtectionListener listener = new ProtectionListener(denyOnLandEngine(fx.store()));
        Block foot = blockProxy(fx.world(), 900, 64, 900, Material.RED_BED);
        BlockMultiPlaceEvent event = multiPlaceEvent(foot, stranger, null);
        listener.onBlockMultiPlace(event);
        assertTrue(event.isCancelled(), "an unreadable part list must fail closed");
    }

    @Test
    void multiPlaceEventShapeMatchesTargetVersion() throws Exception {
        // Records the Bukkit reporting contract this handler relies on, read
        // off the real target-version classes instead of assumed coordinates.
        assertTrue(BlockPlaceEvent.class.isAssignableFrom(BlockMultiPlaceEvent.class),
                "multi-place must stay dispatchable as a place event");
        var partsGetter = BlockMultiPlaceEvent.class.getMethod("getReplacedBlockStates");
        assertEquals(List.class, partsGetter.getReturnType());
        assertTrue(partsGetter.getGenericReturnType().getTypeName()
                        .contains(BlockState.class.getSimpleName()),
                "parts must be reported as block states: " + partsGetter.getGenericReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> BlockMultiPlaceEvent.class.getDeclaredMethod("getHandlerList"),
                "no own handler list: the event shares the single-place list, "
                        + "so both handlers fire and the single-place one must stay out");
    }

    @Test
    void doorAcrossSubLandHeightBoundaryDenied() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId subId = new SubLandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        SubLandSnapshot loft = new SubLandSnapshot(subId, landId, "loft", 65, 255,
                Set.of(new ChunkKey(worldId, 0, 0)));
        LandSnapshot land = new LandSnapshot(landId, "Home", "home",
                OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(loft), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        PermissionBinding landBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_PLACE, PermissionState.ALLOW));
        PermissionBinding subBinding = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.BLOCK_PLACE, PermissionState.DENY));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landBinding)),
                Map.of(subId, List.of(subBinding)),
                Map.of(), Map.of());
        PermissionDefaultsSnapshot empty = PermissionDefaultsSnapshot.empty();
        ConfigView view = new ConfigView(empty,
                LandRuleService.fromRuleSnapshot(empty), 0L, Map.of());
        ProtectionListener listener = new ProtectionListener(new ProtectionEngine(
                store::snapshot,
                SnapshotPermissionContextProvider.atomic(() -> view, () -> auth)));
        World world = worldProxy(worldId);
        Player player = playerProxy(actor, world);
        // Door bottom below the loft (land ALLOW), top inside it (subland DENY).
        Block bottom = blockProxy(world, 5, 64, 5, Material.OAK_DOOR);
        Block top = blockProxy(world, 5, 65, 5, Material.OAK_DOOR);
        BlockMultiPlaceEvent event = multiPlaceEvent(bottom, player,
                List.of(stateProxy(bottom), stateProxy(top)));
        listener.onBlockMultiPlace(event);
        assertTrue(event.isCancelled(),
                "door with its top inside a denying height subland must cancel");
    }

    @Test
    void singleBlockPlaceUnaffected() throws Exception {
        Fixture fx = landFixture();
        Player stranger = playerProxy(UUID.randomUUID(), fx.world());
        ProtectionListener listener = new ProtectionListener(denyOnLandEngine(fx.store()));
        BlockPlaceEvent wild = placeEvent(
                blockProxy(fx.world(), 900, 64, 900, Material.STONE), stranger);
        listener.onBlockPlace(wild);
        assertFalse(wild.isCancelled(), "single wilderness placement must still pass");
        BlockPlaceEvent claimed = placeEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.STONE), stranger);
        listener.onBlockPlace(claimed);
        assertTrue(claimed.isCancelled(), "single placement in denied land must still cancel");
    }
}
