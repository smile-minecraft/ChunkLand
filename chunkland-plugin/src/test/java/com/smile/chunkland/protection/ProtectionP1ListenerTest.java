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
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

/**
 * P1 behaviours: REDSTONE_USE (right-click on redstone parts, stepping on
 * pressure plates by foot or by mob), ENTITY_INTERACT (right-click on a
 * generic entity), MOB_GRIEFING (mob changing a block), and natural mob
 * spawns split into HOSTILE_MOB_SPAWN / PASSIVE_MOB_SPAWN.
 */
class ProtectionP1ListenerTest {

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

    private static Player playerProxy(UUID id) {
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

    private static Monster monsterProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Monster) Proxy.newProxyInstance(Monster.class.getClassLoader(),
                new Class[]{Monster.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return UUID.randomUUID();
                        case "getWorld": return world;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeMonster";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private static BlockData blockDataProxy() {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
                new Class[]{BlockData.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMaterial": return Material.AIR;
                        case "getAsString": return "minecraft:air";
                        case "clone": return proxy;
                        case "matches": return false;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeBlockData";
                        default: return null;
                    }
                });
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private record Fixture(UUID worldId, LandRegistryStore store, World world) {
    }

    private static Fixture fixture() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, new LandId(UUID.randomUUID()), UUID.randomUUID(), 0, 0))));
        return new Fixture(worldId, store, worldProxy(worldId));
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

    @Test
    void redstoneRightClickDenyCancelsAllowPassesAndWildPasses() {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID());
        Block repeater = blockProxy(fx.world(), 5, 64, 5, Material.REPEATER);
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.REDSTONE_USE, PermissionState.DENY, seen));
        PlayerInteractEvent denied =
                new PlayerInteractEvent(stranger, Action.RIGHT_CLICK_BLOCK, null, repeater, BlockFace.UP);
        denying.onPlayerInteract(denied);
        assertTrue(denied.isCancelled(), "REDSTONE_USE DENY must cancel");
        assertEquals(ProtectionActionType.REDSTONE_USE, seen.get());

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.REDSTONE_USE, PermissionState.ALLOW, null));
        PlayerInteractEvent allowed = new PlayerInteractEvent(stranger,
                Action.RIGHT_CLICK_BLOCK, null,
                blockProxy(fx.world(), 5, 64, 5, Material.REPEATER), BlockFace.UP);
        allowing.onPlayerInteract(allowed);
        assertFalse(allowed.isCancelled(), "REDSTONE_USE ALLOW must pass");

        PlayerInteractEvent wild = new PlayerInteractEvent(stranger,
                Action.RIGHT_CLICK_BLOCK, null,
                blockProxy(fx.world(), 900, 64, 900, Material.REPEATER), BlockFace.UP);
        denying.onPlayerInteract(wild);
        assertFalse(wild.isCancelled(), "wilderness redstone follows vanilla");
    }

    @Test
    void pressurePlateStepDenyCancels() {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID());
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.REDSTONE_USE, PermissionState.DENY, null));
        PlayerInteractEvent stepped = new PlayerInteractEvent(stranger, Action.PHYSICAL, null,
                blockProxy(fx.world(), 5, 64, 5, Material.STONE_PRESSURE_PLATE), BlockFace.UP);
        denying.onPlayerInteract(stepped);
        assertTrue(stepped.isCancelled(), "stepping on a land pressure plate DENY must cancel");

        PlayerInteractEvent wild = new PlayerInteractEvent(stranger, Action.PHYSICAL, null,
                blockProxy(fx.world(), 900, 64, 900, Material.STONE_PRESSURE_PLATE), BlockFace.UP);
        denying.onPlayerInteract(wild);
        assertFalse(wild.isCancelled(), "wilderness pressure plate follows vanilla");
    }

    @Test
    void entityInteractDenyCancelsAllowPassesAndWildPasses() {
        Fixture fx = fixture();
        Player stranger = playerProxy(UUID.randomUUID());
        Cow cow = cowProxy(fx.world(), 5, 64, 5);
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();

        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ENTITY_INTERACT, PermissionState.DENY, seen));
        PlayerInteractEntityEvent denied = new PlayerInteractEntityEvent(stranger, cow);
        denying.onPlayerInteractEntity(denied);
        assertTrue(denied.isCancelled(), "ENTITY_INTERACT DENY must cancel");
        assertEquals(ProtectionActionType.ENTITY_INTERACT, seen.get());

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.ENTITY_INTERACT, PermissionState.ALLOW, null));
        PlayerInteractEntityEvent allowed =
                new PlayerInteractEntityEvent(stranger, cowProxy(fx.world(), 5, 64, 5));
        allowing.onPlayerInteractEntity(allowed);
        assertFalse(allowed.isCancelled(), "ENTITY_INTERACT ALLOW must pass");

        PlayerInteractEntityEvent wild = new PlayerInteractEntityEvent(stranger,
                cowProxy(fx.world(), 900, 64, 900));
        denying.onPlayerInteractEntity(wild);
        assertFalse(wild.isCancelled(), "wilderness entity interaction follows vanilla");
    }

    @Test
    void mobGriefingDenyCancelsAndWildPasses() {
        Fixture fx = fixture();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.MOB_GRIEFING, PermissionState.DENY, null));
        EntityChangeBlockEvent denied = new EntityChangeBlockEvent(
                cowProxy(fx.world(), 5, 64, 5),
                blockProxy(fx.world(), 5, 64, 5, Material.DIRT), blockDataProxy());
        denying.onEntityChangeBlock(denied);
        assertTrue(denied.isCancelled(), "MOB_GRIEFING DENY must cancel");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.MOB_GRIEFING, PermissionState.ALLOW, null));
        EntityChangeBlockEvent allowed = new EntityChangeBlockEvent(
                cowProxy(fx.world(), 5, 64, 5),
                blockProxy(fx.world(), 5, 64, 5, Material.DIRT), blockDataProxy());
        allowing.onEntityChangeBlock(allowed);
        assertFalse(allowed.isCancelled(), "MOB_GRIEFING ALLOW must pass");

        EntityChangeBlockEvent wild = new EntityChangeBlockEvent(
                cowProxy(fx.world(), 900, 64, 900),
                blockProxy(fx.world(), 900, 64, 900, Material.DIRT), blockDataProxy());
        denying.onEntityChangeBlock(wild);
        assertFalse(wild.isCancelled(), "wilderness mob griefing follows vanilla");
    }

    @Test
    void naturalHostileSpawnDenyCancelsAndRoutesHostileAction() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(engineFor(fx.store(),
                ProtectionActionType.HOSTILE_MOB_SPAWN, PermissionState.DENY, seen));
        LivingEntity monster = monsterProxy(fx.world(), 5, 64, 5);
        CreatureSpawnEvent denied =
                new CreatureSpawnEvent(monster, CreatureSpawnEvent.SpawnReason.NATURAL);
        denying.onCreatureSpawn(denied);
        assertTrue(denied.isCancelled(), "natural hostile spawn DENY must cancel");
        assertEquals(ProtectionActionType.HOSTILE_MOB_SPAWN, seen.get());
    }

    @Test
    void naturalPassiveSpawnDenyCancelsAndRoutesPassiveAction() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(engineFor(fx.store(),
                ProtectionActionType.PASSIVE_MOB_SPAWN, PermissionState.DENY, seen));
        CreatureSpawnEvent denied = new CreatureSpawnEvent(cowProxy(fx.world(), 5, 64, 5),
                CreatureSpawnEvent.SpawnReason.NATURAL);
        denying.onCreatureSpawn(denied);
        assertTrue(denied.isCancelled(), "natural passive spawn DENY must cancel");
        assertEquals(ProtectionActionType.PASSIVE_MOB_SPAWN, seen.get());

        CreatureSpawnEvent wild = new CreatureSpawnEvent(cowProxy(fx.world(), 900, 64, 900),
                CreatureSpawnEvent.SpawnReason.NATURAL);
        denying.onCreatureSpawn(wild);
        assertFalse(wild.isCancelled(), "wilderness natural spawn follows vanilla");
    }

    @Test
    void spawnerSpawnStaysVanillaEvenWhenSpawnRulesDeny() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(engineFor(fx.store(),
                ProtectionActionType.HOSTILE_MOB_SPAWN, PermissionState.DENY, seen));
        CreatureSpawnEvent spawner = new CreatureSpawnEvent(monsterProxy(fx.world(), 5, 64, 5),
                CreatureSpawnEvent.SpawnReason.SPAWNER);
        denying.onCreatureSpawn(spawner);
        assertFalse(spawner.isCancelled(), "spawner blooms stay vanilla: only natural spawns are ruled");
        assertNull(seen.get(), "spawner spawns must not consult the engine");
    }

    @Test
    void decisionFailureCancelsP1Handlers() {
        Fixture fx = fixture();
        ProtectionEngine exploding = new ProtectionEngine(fx.store()::snapshot,
                (actor, id, action, snapshot) -> {
                    throw new RuntimeException("decision backend boom");
                });
        ProtectionListener listener = new ProtectionListener(exploding);
        Player player = playerProxy(UUID.randomUUID());

        PlayerInteractEvent redstone = new PlayerInteractEvent(player,
                Action.RIGHT_CLICK_BLOCK, null,
                blockProxy(fx.world(), 1, 64, 1, Material.REPEATER), BlockFace.UP);
        listener.onPlayerInteract(redstone);
        assertTrue(redstone.isCancelled(), "redstone decision failure must fail closed");

        PlayerInteractEntityEvent interact =
                new PlayerInteractEntityEvent(player, cowProxy(fx.world(), 1, 64, 1));
        listener.onPlayerInteractEntity(interact);
        assertTrue(interact.isCancelled(), "entity interact decision failure must fail closed");

        EntityChangeBlockEvent grief = new EntityChangeBlockEvent(
                cowProxy(fx.world(), 1, 64, 1),
                blockProxy(fx.world(), 1, 64, 1, Material.DIRT), blockDataProxy());
        listener.onEntityChangeBlock(grief);
        assertTrue(grief.isCancelled(), "mob griefing decision failure must fail closed");

        CreatureSpawnEvent spawn = new CreatureSpawnEvent(monsterProxy(fx.world(), 1, 64, 1),
                CreatureSpawnEvent.SpawnReason.NATURAL);
        listener.onCreatureSpawn(spawn);
        assertTrue(spawn.isCancelled(), "spawn decision failure must fail closed");
    }
}
