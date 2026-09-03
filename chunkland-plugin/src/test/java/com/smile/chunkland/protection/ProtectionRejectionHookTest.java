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
import com.smile.chunkland.message.rejection.RejectionCooldown;
import com.smile.chunkland.message.rejection.RejectionNotifier;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Test;

class ProtectionRejectionHookTest {

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
                        case "getType": return Material.STONE;
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

    private static LandSnapshot landAt(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static ProtectionEngine engineWithSubjectDefault(LandRegistryStore store,
                                                              PermissionState state) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (action.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, state);
            }
            return new PermissionContext(action, false, List.of(), state,
                    PermissionState.INHERIT);
        });
    }

    private static final class CountingMessaging {
        final AtomicInteger renders = new AtomicInteger();
        final AtomicInteger sends = new AtomicInteger();
        volatile RuntimeException sendFailure;

        RejectionNotifier notifier() {
            RejectionNotifier.Sender sender = (player, message) -> {
                if (sendFailure != null) {
                    throw sendFailure;
                }
                sends.incrementAndGet();
            };
            RejectionNotifier.Renderer renderer = (player, action, decision) -> {
                renders.incrementAndGet();
                return Component.text("denied: " + action.name());
            };
            return new RejectionNotifier(sender, renderer,
                    new RejectionCooldown(Instant::now, Duration.ofSeconds(3)), Set.of());
        }
    }

    @Test
    void subjectDenyCancelsAndNotifies() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        World world = worldProxy(worldId);
        Block block = blockProxy(world, 5, 64, 5);
        Player player = playerProxy(UUID.randomUUID(), world, 5, 64, 5);

        CountingMessaging messaging = new CountingMessaging();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(store, PermissionState.DENY), messaging.notifier());
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);
        assertTrue(event.isCancelled(), "DENY must still cancel");
        assertEquals(1, messaging.sends.get(), "subject DENY must notify once");
        assertEquals(1, messaging.renders.get(), "Component must be built for the real send");
    }

    @Test
    void allowPathSendsNothing() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        World world = worldProxy(worldId);
        Block block = blockProxy(world, 5, 64, 5);
        Player player = playerProxy(UUID.randomUUID(), world, 5, 64, 5);

        CountingMessaging messaging = new CountingMessaging();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(store, PermissionState.ALLOW), messaging.notifier());
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);
        assertFalse(event.isCancelled());
        assertEquals(0, messaging.sends.get(), "ALLOW must send zero messages");
        assertEquals(0, messaging.renders.get(), "ALLOW must build zero Components");
    }

    @Test
    void landRuleDenyCancelsButStaysSilent() throws Exception {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        World world = worldProxy(worldId);

        CountingMessaging messaging = new CountingMessaging();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(store, PermissionState.DENY), messaging.notifier());
        Player damager = playerProxy(UUID.randomUUID(), world, 2, 64, 2);
        Player victim = playerProxy(UUID.randomUUID(), world, 2, 64, 2);
        EntityDamageByEntityEvent event = damageEvent(damager, victim);
        listener.onEntityDamage(event);
        assertTrue(event.isCancelled(), "PVP DENY must still cancel");
        assertEquals(0, messaging.sends.get(), "LAND_RULE deny must stay silent");
        assertEquals(0, messaging.renders.get(), "silent deny must build zero Components");
    }

    @Test
    void explosionPathNeverTouchesMessaging() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        World world = worldProxy(worldId);
        Block inLand = blockProxy(world, 5, 60, 5);
        Block wild = blockProxy(world, 900, 60, 900);
        List<Block> affected = new ArrayList<>(List.of(inLand, wild));

        CountingMessaging messaging = new CountingMessaging();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(store, PermissionState.DENY), messaging.notifier());
        Entity tnt = cowProxy(world, 5, 64, 5);
        EntityExplodeEvent event = new EntityExplodeEvent(
                tnt, new Location(world, 5, 64, 5), affected, 3.0f, ExplosionResult.DESTROY);
        listener.onEntityExplode(event);
        assertEquals(List.of(wild), affected, "per-block filtering must be unchanged");
        assertEquals(0, messaging.sends.get(), "ownerless blast must not notify");
        assertEquals(0, messaging.renders.get(), "ownerless blast must build zero Components");
    }

    @Test
    void messagingFailureNeverBreaksCancel() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        World world = worldProxy(worldId);
        Block block = blockProxy(world, 5, 64, 5);
        Player player = playerProxy(UUID.randomUUID(), world, 5, 64, 5);

        CountingMessaging messaging = new CountingMessaging();
        messaging.sendFailure = new RuntimeException("send boom");
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(store, PermissionState.DENY), messaging.notifier());
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        assertDoesNotThrow(() -> listener.onBlockBreak(event),
                "message failure must never leak into event dispatch");
        assertTrue(event.isCancelled(), "DENY must still cancel when messaging fails");
    }
}
