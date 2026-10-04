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
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.Test;

/**
 * Projectile damage carries the shooting player: an arrow, trident, snowball
 * or similar shot by a player must face the same verdict as a melee hit
 * ({@code PLAYER_DAMAGE_PLAYER} for players, {@code ENTITY_DAMAGE} for
 * everything else). A projectile with no player behind it (dispenser, mob,
 * nature, gone shooter) stays vanilla, matching direct non-player damage.
 */
class ProjectileDamageTest {

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

    private static Projectile arrowProxy(World world, int x, int y, int z, ProjectileSource shooter) {
        Location loc = new Location(world, x, y, z);
        return (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(),
                new Class[]{Projectile.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getShooter": return shooter;
                        case "getLocation": return loc;
                        case "getWorld": return world;
                        case "getUniqueId": return UUID.randomUUID();
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeArrow";
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

    private static LandSnapshot landAt(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
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

    private record Arena(UUID worldId, LandId landId, LandRegistryStore store, World world) {
    }

    private static Arena arena() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        return new Arena(worldId, landId, store, worldProxy(worldId));
    }

    @Test
    void playerArrowHittingPlayerInPvpDenyLandCancels() throws Exception {
        Arena fx = arena();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(fx.store(), PermissionState.DENY, seen));
        Player shooter = playerProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        Player victim = playerProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        EntityDamageByEntityEvent hit =
                damageEvent(arrowProxy(fx.world(), 5, 64, 5, shooter), victim);
        listener.onEntityDamage(hit);
        assertTrue(hit.isCancelled(), "a player-shot arrow in PVP DENY land must cancel like melee");
        assertEquals(ProtectionActionType.PLAYER_DAMAGE_PLAYER, seen.get(),
                "a player-shot arrow hitting a player must route to the PVP action");
    }

    @Test
    void playerArrowHittingPlayerWhenAllowedPasses() throws Exception {
        Arena fx = arena();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(fx.store(), PermissionState.ALLOW, null));
        Player shooter = playerProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        Player victim = playerProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        EntityDamageByEntityEvent hit =
                damageEvent(arrowProxy(fx.world(), 5, 64, 5, shooter), victim);
        listener.onEntityDamage(hit);
        assertFalse(hit.isCancelled(), "an authorised player-shot arrow must not be blocked");
    }

    @Test
    void playerArrowHittingProtectedEntityDenies() throws Exception {
        Arena fx = arena();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(fx.store(), PermissionState.DENY, seen));
        Player shooter = playerProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        Cow animal = cowProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        EntityDamageByEntityEvent hit =
                damageEvent(arrowProxy(fx.world(), 5, 64, 5, shooter), animal);
        listener.onEntityDamage(hit);
        assertTrue(hit.isCancelled(), "a player-shot arrow hitting a protected entity must cancel");
        assertEquals(ProtectionActionType.ENTITY_DAMAGE, seen.get(),
                "a player-shot arrow hitting a non-player must route to ENTITY_DAMAGE");
    }

    @Test
    void projectileWithoutPlayerSourceStaysVanilla() throws Exception {
        Arena fx = arena();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(fx.store(), PermissionState.DENY, seen));
        Player victim = playerProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        EntityDamageByEntityEvent ownerless =
                damageEvent(arrowProxy(fx.world(), 5, 64, 5, null), victim);
        listener.onEntityDamage(ownerless);
        assertFalse(ownerless.isCancelled(), "an ownerless projectile stays vanilla");
        Cow mobShot = cowProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        EntityDamageByEntityEvent mobArrow =
                damageEvent(arrowProxy(fx.world(), 5, 64, 5, mobShot), victim);
        listener.onEntityDamage(mobArrow);
        assertFalse(mobArrow.isCancelled(), "a mob-shot projectile stays vanilla");
        assertNull(seen.get(), "projectiles with no player behind them must not consult the engine");
    }

    @Test
    void shooterResolutionFailureFailsClosedWithoutThrowing() throws Exception {
        Arena fx = arena();
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(fx.store(), PermissionState.ALLOW, null));
        Player victim = playerProxy(UUID.randomUUID(), fx.world(), 5, 64, 5);
        Projectile broken = (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(),
                new Class[]{Projectile.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getShooter")) {
                        throw new RuntimeException("shooter backend boom");
                    }
                    return null;
                });
        EntityDamageByEntityEvent hit = damageEvent(broken, victim);
        assertDoesNotThrow(() -> listener.onEntityDamage(hit));
        assertTrue(hit.isCancelled(), "shooter resolution failure must fail closed");
    }
}
