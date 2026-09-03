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
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.Test;

/**
 * Projectile landing (M3-08 handover): the adjacent-block dispense check
 * cannot see arrows that fly several chunks, so the landing point is judged
 * as a {@code DISPENSER_CROSS_BOUNDARY} crossing from the shooter position.
 * Only genuine boundary involvement intervenes: same-land and wilderness
 * landings stay vanilla, and an unknown shooter cannot prove a crossing.
 */
class ProjectileLandingTest {

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

    private static Player shooterProxy(UUID id, World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getName": return "Archer";
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeShooter";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private static BlockProjectileSource dispenserProxy(Block block) {
        return (BlockProjectileSource) Proxy.newProxyInstance(
                BlockProjectileSource.class.getClassLoader(),
                new Class[]{BlockProjectileSource.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBlock": return block;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeDispenser";
                        default: return null;
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

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private record TwoLands(UUID worldId, LandId landA, LandId landB,
                            LandRegistryStore store, World world) {
    }

    private static TwoLands twoLands() {
        UUID worldId = UUID.randomUUID();
        LandId landA = new LandId(UUID.randomUUID());
        LandId landB = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, landA, UUID.randomUUID(), 0, 0),
                landAt(worldId, landB, UUID.randomUUID(), 1, 0))));
        return new TwoLands(worldId, landA, landB, store, worldProxy(worldId));
    }

    /** Block x for a chunk: chunk c holds blocks [c*16, c*16+15]. */
    private static int blockX(int chunkX) {
        return chunkX * 16 + 5;
    }

    private static ProtectionEngine ruleEngine(LandRegistryStore store, LandRuleLookup lookup) {
        return new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(lookup, null));
    }

    private static ProtectionEngine actionEngine(LandRegistryStore store,
                                                 ProtectionActionType action,
                                                 PermissionState state) {
        return new ProtectionEngine(store::snapshot, (actor, landId, a, snapshot) -> {
            if (a.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, a == action ? state : PermissionState.INHERIT);
            }
            return new PermissionContext(a, false, List.of(),
                    PermissionState.INHERIT, PermissionState.INHERIT);
        });
    }

    @Test
    void crossLandLandingDenyCancels() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.DENY));
        // Shot from land A (chunk 0) lands on a block in land B (chunk 1).
        Projectile arrow = arrowProxy(fx.world(), blockX(1), 64, 5,
                shooterProxy(UUID.randomUUID(), fx.world(), blockX(0), 64, 5));
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, null,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.STONE));
        listener.onProjectileHit(hit);
        assertTrue(hit.isCancelled(), "A -> B landing DENY must cancel");
    }

    @Test
    void crossLandLandingAllowPasses() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.ALLOW));
        Projectile arrow = arrowProxy(fx.world(), blockX(1), 64, 5,
                shooterProxy(UUID.randomUUID(), fx.world(), blockX(0), 64, 5));
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, null,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.STONE));
        listener.onProjectileHit(hit);
        assertFalse(hit.isCancelled(), "A -> B landing ALLOW must pass");
    }

    @Test
    void sameLandLandingPassesEvenWhenRuleDenies() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.DENY));
        int inA = blockX(0);
        Projectile arrow = arrowProxy(fx.world(), inA, 64, 5,
                shooterProxy(UUID.randomUUID(), fx.world(), inA, 64, 6));
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, null,
                blockProxy(fx.world(), inA, 64, 5, Material.STONE));
        listener.onProjectileHit(hit);
        assertFalse(hit.isCancelled(),
                "landing inside the shooter's own land is not a crossing: must pass");
    }

    @Test
    void wildernessLandingPassesWithoutConsulting() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.DENY));
        int wild = blockX(9);
        Projectile arrow = arrowProxy(fx.world(), wild, 64, 5,
                shooterProxy(UUID.randomUUID(), fx.world(), wild, 64, 6));
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, null,
                blockProxy(fx.world(), wild, 64, 5, Material.STONE));
        listener.onProjectileHit(hit);
        assertFalse(hit.isCancelled(), "wild -> wild landing follows vanilla");
    }

    @Test
    void wildIntoDenyingLandCancels() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(ruleEngine(fx.store(),
                (id, rule, snapshot) -> Optional.of(
                        rule == LandRuleType.MOB_GRIEFING ? PermissionState.DENY : PermissionState.ALLOW)));
        int inA = blockX(0);
        int wild = blockX(9);
        Projectile arrow = arrowProxy(fx.world(), inA, 64, 5,
                shooterProxy(UUID.randomUUID(), fx.world(), wild, 64, 5));
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, null,
                blockProxy(fx.world(), inA, 64, 5, Material.STONE));
        listener.onProjectileHit(hit);
        assertTrue(hit.isCancelled(), "wild -> land landing must deny when the land rule denies");
    }

    @Test
    void dispenserShotAcrossLandsCancels() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.DENY));
        // Dispenser at the last block of chunk (0,0); the arrow lands in chunk (1,0).
        Block source = blockProxy(fx.world(), 15, 64, 5, Material.DISPENSER);
        Projectile arrow = arrowProxy(fx.world(), blockX(1), 64, 5, dispenserProxy(source));
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, null,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.STONE));
        listener.onProjectileHit(hit);
        assertTrue(hit.isCancelled(), "dispenser A -> B landing DENY must cancel");
    }

    @Test
    void unknownShooterCannotProveACrossingSoStaysVanilla() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.DENY));
        int inB = blockX(1);
        Projectile arrow = arrowProxy(fx.world(), inB, 64, 5, null);
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, null,
                blockProxy(fx.world(), inB, 64, 5, Material.STONE));
        listener.onProjectileHit(hit);
        assertFalse(hit.isCancelled(),
                "a shooter that is gone cannot prove a crossing: must stay vanilla");
    }

    @Test
    void missingLandingFailsClosed() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.ALLOW));
        Projectile arrow = arrowProxy(fx.world(), blockX(1), 64, 5,
                shooterProxy(UUID.randomUUID(), fx.world(), blockX(0), 64, 5));
        // No hit block and no hit entity: the projectile location backs the landing.
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, (org.bukkit.entity.Entity) null,
                (Block) null);
        listener.onProjectileHit(hit);
        assertFalse(hit.isCancelled(), "projectile-position fallback still judges the crossing");
    }
}
