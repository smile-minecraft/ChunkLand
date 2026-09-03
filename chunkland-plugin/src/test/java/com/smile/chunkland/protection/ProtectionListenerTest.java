package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityEvent;
import org.junit.jupiter.api.Test;

class ProtectionListenerTest {

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

    private static Block blockProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld": return world;
                        case "getX": return x;
                        case "getY": return y;
                        case "getZ": return z;
                        case "getLocation": return loc;
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

    private static Player playerProxy(UUID id, World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getWorld": return world;
                        case "getLocation": return loc;
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

    private static Cow cowProxy(UUID id, World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Cow) Proxy.newProxyInstance(Cow.class.getClassLoader(),
                new Class[]{Cow.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
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

    /**
     * Allocates a damage event without running its constructor: the Paper
     * constructor touches the server damage registry, which does not exist in
     * unit tests. Only the fields the listener reads are populated.
     */
    private static EntityDamageByEntityEvent damageEvent(Entity damager, Entity victim)
            throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        EntityDamageByEntityEvent ev = (EntityDamageByEntityEvent) unsafe.allocateInstance(
                EntityDamageByEntityEvent.class);
        long damagerOffset = unsafe.objectFieldOffset(
                EntityDamageByEntityEvent.class.getDeclaredField("damager"));
        unsafe.putObject(ev, damagerOffset, damager);
        Field entityField = EntityEvent.class.getDeclaredField("entity");
        entityField.setAccessible(true);
        entityField.set(ev, victim);
        return ev;
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, int chunkX, int chunkZ) {        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static ProtectionEngine engineWithSubjectDefault(LandRegistryStore store,
                                                             PermissionState state,
                                                             AtomicReference<ProtectionActionType> seen) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (seen != null) {
                seen.set(action);
            }
            if (action.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, state);
            }
            return new PermissionContext(action, false, List.of(), state, PermissionState.INHERIT);
        });
    }

    @Test
    void handlersDeclareExplicitPriorityAndIgnoreCancelled() throws Exception {
        assertTrue(Listener.class.isAssignableFrom(ProtectionListener.class));
        var onBreak = ProtectionListener.class.getDeclaredMethod("onBlockBreak", BlockBreakEvent.class);
        EventHandler breakHandler = onBreak.getAnnotation(EventHandler.class);
        assertNotNull(breakHandler, "block handler must be a native Bukkit handler");
        assertEquals(EventPriority.HIGHEST, breakHandler.priority());
        assertTrue(breakHandler.ignoreCancelled());

        var onDamage = ProtectionListener.class.getDeclaredMethod(
                "onEntityDamage", EntityDamageByEntityEvent.class);
        EventHandler damageHandler = onDamage.getAnnotation(EventHandler.class);
        assertNotNull(damageHandler, "damage handler must be a native Bukkit handler");
        assertEquals(EventPriority.HIGHEST, damageHandler.priority());
        assertTrue(damageHandler.ignoreCancelled());
    }

    @Test
    void blockBreakDenyCancelsAndAllowPasses() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        World world = worldProxy(worldId);
        // block (5, 64, 5) sits in chunk (0, 0)
        Block block = blockProxy(world, 5, 64, 5);
        Player player = playerProxy(actor, world, 5, 64, 5);

        ProtectionListener denying =
                new ProtectionListener(engineWithSubjectDefault(store, PermissionState.DENY, null));
        BlockBreakEvent denied = new BlockBreakEvent(block, player);
        denying.onBlockBreak(denied);
        assertTrue(denied.isCancelled(), "DENY must cancel the break");

        ProtectionListener allowing =
                new ProtectionListener(engineWithSubjectDefault(store, PermissionState.ALLOW, null));
        BlockBreakEvent allowed = new BlockBreakEvent(block, player);
        allowing.onBlockBreak(allowed);
        assertFalse(allowed.isCancelled(), "ALLOW must not cancel the break");
    }

    @Test
    void blockBreakInWildernessPasses() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        UUID worldId = UUID.randomUUID();
        World world = worldProxy(worldId);
        Block block = blockProxy(world, 100, 64, 100);
        Player player = playerProxy(UUID.randomUUID(), world, 100, 64, 100);
        ProtectionListener listener =
                new ProtectionListener(engineWithSubjectDefault(store, PermissionState.DENY, null));
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);
        assertFalse(event.isCancelled(), "wilderness follows vanilla: never cancel");
    }

    @Test
    void decisionFailureCancelsEvent() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        ProtectionEngine exploding = new ProtectionEngine(store::snapshot,
                (actor, id, action, snapshot) -> {
                    throw new RuntimeException("decision backend boom");
                });
        ProtectionListener listener = new ProtectionListener(exploding);
        World world = worldProxy(worldId);
        Block block = blockProxy(world, 1, 64, 1);
        Player player = playerProxy(UUID.randomUUID(), world, 1, 64, 1);
        BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
        listener.onBlockBreak(breakEvent);
        assertTrue(breakEvent.isCancelled(), "decision failure must fail closed");

        Player damager = playerProxy(UUID.randomUUID(), world, 1, 64, 1);
        Player victim = playerProxy(UUID.randomUUID(), world, 1, 64, 1);
        EntityDamageByEntityEvent damageEvent = damageEvent(damager, victim);
        listener.onEntityDamage(damageEvent);
        assertTrue(damageEvent.isCancelled(), "damage decision failure must fail closed");
    }

    @Test
    void pvpAndEntityDamageTakeSeparateRoutes() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        World world = worldProxy(worldId);
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener =
                new ProtectionListener(engineWithSubjectDefault(store, PermissionState.ALLOW, seen));

        Player damager = playerProxy(UUID.randomUUID(), world, 2, 64, 2);
        Player victim = playerProxy(UUID.randomUUID(), world, 2, 64, 2);
        EntityDamageByEntityEvent pvp = damageEvent(damager, victim);
        listener.onEntityDamage(pvp);
        assertEquals(ProtectionActionType.PLAYER_DAMAGE_PLAYER, seen.get(),
                "player harming a player must route to the PVP action");
        assertFalse(pvp.isCancelled());

        Cow animal = cowProxy(UUID.randomUUID(), world, 2, 64, 2);
        EntityDamageByEntityEvent mobHit = damageEvent(damager, animal);
        listener.onEntityDamage(mobHit);
        assertEquals(ProtectionActionType.ENTITY_DAMAGE, seen.get(),
                "player harming a non-player entity must route to ENTITY_DAMAGE");
        assertFalse(mobHit.isCancelled());
    }

    @Test
    void pvpDenyCancelsButWildernessPasses() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        World world = worldProxy(worldId);
        ProtectionListener denying =
                new ProtectionListener(engineWithSubjectDefault(store, PermissionState.DENY, null));

        Player damager = playerProxy(UUID.randomUUID(), world, 3, 64, 3);
        Player victim = playerProxy(UUID.randomUUID(), world, 3, 64, 3);
        EntityDamageByEntityEvent inside = damageEvent(damager, victim);
        denying.onEntityDamage(inside);
        assertTrue(inside.isCancelled(), "PVP DENY inside a land must cancel");

        // far away: chunk (40, 40) is wilderness
        Player farDamager = playerProxy(UUID.randomUUID(), world, 645, 64, 645);
        Player farVictim = playerProxy(UUID.randomUUID(), world, 645, 64, 645);
        EntityDamageByEntityEvent outside = damageEvent(farDamager, farVictim);
        denying.onEntityDamage(outside);
        assertFalse(outside.isCancelled(), "PVP in wilderness must follow vanilla");
    }

    @Test
    void nonPlayerDamagerIsLeftToVanilla() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        World world = worldProxy(worldId);
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener =
                new ProtectionListener(engineWithSubjectDefault(store, PermissionState.DENY, seen));
        Cow damager = cowProxy(UUID.randomUUID(), world, 1, 64, 1);
        Player victim = playerProxy(UUID.randomUUID(), world, 1, 64, 1);
        EntityDamageByEntityEvent event = damageEvent(damager, victim);
        listener.onEntityDamage(event);
        assertFalse(event.isCancelled(), "non-player damage stays vanilla in this skeleton");
        assertNull(seen.get(), "non-player damage must not consult the protection engine yet");
    }

    @Test
    void subjectBindingDecidesEntityDamage() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                (a, id, action, snapshot) -> new PermissionContext(action, false,
                        List.of(new PermissionBinding(PermissionSubject.player(actor),
                                new Permission(action, PermissionState.DENY))),
                        PermissionState.INHERIT, PermissionState.INHERIT));
        ProtectionListener listener = new ProtectionListener(engine);
        World world = worldProxy(worldId);
        Player damager = playerProxy(actor, world, 4, 64, 4);
        Cow animal = cowProxy(UUID.randomUUID(), world, 4, 64, 4);
        EntityDamageByEntityEvent event = damageEvent(damager, animal);
        listener.onEntityDamage(event);
        assertTrue(event.isCancelled(), "subject DENY on ENTITY_DAMAGE must cancel");
    }
}
