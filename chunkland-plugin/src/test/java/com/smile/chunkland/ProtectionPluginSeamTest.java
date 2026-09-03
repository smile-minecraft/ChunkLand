package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.ProtectionListener;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

class ProtectionPluginSeamTest {

    private static LandSnapshot landOwnedBy(UUID worldId, LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static void assertHighestIgnoreCancelled(String method, Class<?> event) throws Exception {
        var handler = ProtectionListener.class.getDeclaredMethod(method, event);
        EventHandler mark = handler.getAnnotation(EventHandler.class);
        assertNotNull(mark, method + " must be a native Bukkit handler");
        assertEquals(EventPriority.HIGHEST, mark.priority(), method + " must run at HIGHEST");
        assertTrue(mark.ignoreCancelled(), method + " must ignore cancelled events");
    }

    @Test
    void startupEngineCoversEveryAction() {
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(new LandRegistryStore());
        assertNotNull(engine);
        for (ProtectionActionType action : ProtectionActionType.values()) {
            assertNotNull(engine.routeOf(action),
                    "startup engine must route every action: " + action);
        }
    }

    @Test
    void startupEngineLeavesWildernessAlone() {
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(new LandRegistryStore());
        var decision = engine.decideAt(UUID.randomUUID(), UUID.randomUUID(), 0, 0,
                ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                "empty registry means wilderness: vanilla, never DENY");
    }

    @Test
    void startupEngineGrantsOwnerBlockPlaceInOwnLand() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landOwnedBy(worldId, landId, owner, 0, 0))));
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(store);
        var decision = engine.decide(owner, landId, ProtectionActionType.BLOCK_PLACE);
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                "owner acts in own land from snapshot ownership: must ALLOW");
    }

    @Test
    void startupEngineDeniesStrangerBlockPlace() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(
                List.of(landOwnedBy(worldId, landId, UUID.randomUUID(), 0, 0))));
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(store);
        var decision = engine.decide(UUID.randomUUID(), landId, ProtectionActionType.BLOCK_PLACE);
        assertEquals(PermissionState.DENY, decision.outcome(),
                "stranger with no grant inside a land must stay DENY");
    }

    @Test
    void startupEngineDeniesPvpWithNoRuleSource() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landOwnedBy(worldId, landId, owner, 0, 0))));
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(store);
        // Owner guarantee never rescues a LAND_RULE action, and no rule source
        // is wired yet, so even the owner is DENY here.
        assertEquals(PermissionState.DENY,
                engine.decide(owner, landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome());
        assertEquals(PermissionState.DENY,
                engine.decide(UUID.randomUUID(), landId, ProtectionActionType.PLAYER_DAMAGE_PLAYER).outcome());
    }

    @Test
    void formalWiringInterceptsAfterPublish() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landOwnedBy(worldId, landId, owner, 0, 0))));
        org.bukkit.World world = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.World.class.getClassLoader(),
                new Class[]{org.bukkit.World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> worldId;
                    case "getName" -> "world";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeWorld";
                    default -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) yield false;
                        if (rt == int.class) yield 0;
                        if (rt == long.class) yield 0L;
                        yield null;
                    }
                });
        ProtectionEngine engine = ChunkLandPlugin.buildProtectionEngine(store);
        ProtectionListener listener = new ProtectionListener(engine);

        // Fluid outflow: source inside the published land, destination wilderness.
        org.bukkit.block.Block from = blockAt(world, 5, 64, 5, org.bukkit.Material.WATER);
        org.bukkit.block.Block to = blockAt(world, 900, 64, 900, org.bukkit.Material.AIR);
        org.bukkit.event.block.BlockFromToEvent outflow =
                new org.bukkit.event.block.BlockFromToEvent(from, to);
        listener.onFluidFlow(outflow);
        assertTrue(outflow.isCancelled(),
                "published land -> wilderness outflow must cancel through formal wiring");

        // Piston push-in: wilderness block pushed into the published land.
        org.bukkit.block.Block piston = blockAt(world, 17, 64, 5, org.bukkit.Material.PISTON);
        org.bukkit.block.Block moved = blockAt(world, 16, 64, 5, org.bukkit.Material.STONE);
        org.bukkit.event.block.BlockPistonExtendEvent pushIn =
                new org.bukkit.event.block.BlockPistonExtendEvent(
                        piston, List.of(moved), org.bukkit.block.BlockFace.WEST);
        listener.onPistonExtend(pushIn);
        assertTrue(pushIn.isCancelled(),
                "wilderness -> published land push must cancel through formal wiring");
    }

    private static org.bukkit.block.Block blockAt(org.bukkit.World world, int x, int y, int z,
                                                  org.bukkit.Material type) {
        return (org.bukkit.block.Block) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.block.Block.class.getClassLoader(),
                new Class[]{org.bukkit.block.Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getType" -> type;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeBlock";
                    default -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) yield false;
                        if (rt == int.class) yield 0;
                        yield null;
                    }
                });
    }

    @Test
    void listenerDeclaresP0SubsetHandlers() throws Exception {
        assertHighestIgnoreCancelled("onBlockPlace",
                org.bukkit.event.block.BlockPlaceEvent.class);
        assertHighestIgnoreCancelled("onPlayerInteract",
                org.bukkit.event.player.PlayerInteractEvent.class);
        assertHighestIgnoreCancelled("onBucketFill",
                org.bukkit.event.player.PlayerBucketFillEvent.class);
        assertHighestIgnoreCancelled("onBucketEmpty",
                org.bukkit.event.player.PlayerBucketEmptyEvent.class);
        assertHighestIgnoreCancelled("onPistonExtend",
                org.bukkit.event.block.BlockPistonExtendEvent.class);
        assertHighestIgnoreCancelled("onPistonRetract",
                org.bukkit.event.block.BlockPistonRetractEvent.class);
        assertHighestIgnoreCancelled("onFluidFlow",
                org.bukkit.event.block.BlockFromToEvent.class);
        assertHighestIgnoreCancelled("onHopperTransfer",
                org.bukkit.event.inventory.InventoryMoveItemEvent.class);
        assertHighestIgnoreCancelled("onEntityExplode",
                org.bukkit.event.entity.EntityExplodeEvent.class);
        assertHighestIgnoreCancelled("onBlockExplode",
                org.bukkit.event.block.BlockExplodeEvent.class);
        assertHighestIgnoreCancelled("onExplosionEntityDamage",
                org.bukkit.event.entity.EntityDamageEvent.class);
    }

    @Test
    void pluginDeclaresProtectionWiring() throws Exception {
        assertNotNull(ChunkLandPlugin.class.getDeclaredField("protectionStore"));
        assertNotNull(ChunkLandPlugin.class.getDeclaredField("protectionEngine"));
        assertNotNull(ChunkLandPlugin.class.getDeclaredField("protectionListener"));
        assertNotNull(ChunkLandPlugin.class.getDeclaredMethod("registerProtectionListener",
                com.smile.chunkland.protection.ProtectionListener.class));
    }
}
