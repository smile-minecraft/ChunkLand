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
import io.papermc.paper.event.block.TargetHitEvent;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.Test;

/**
 * Arrows flipping protected redstone: a wooden button or plate answers the
 * arrow's touch through {@code EntityInteractEvent}, a target block through
 * {@code TargetHitEvent}. The verdict belongs to the shooting player at the
 * touched block, never to the arrow itself and never to wherever the shooter
 * happens to stand: no shooter position is read on this path.
 */
class ProjectileRedstoneTest {

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

    /**
     * Shooting player: identity answers, position never does. Any position
     * read is a region-thread hazard on Folia, so it throws here.
     */
    private static Player shooterProxy(UUID id, boolean online) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "isOnline": return online;
                        case "getName": return "Archer";
                        case "getLocation":
                            throw new RuntimeException("shooter position must not be read");
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

    private static Cow mobProxy(UUID id) {
        return (Cow) Proxy.newProxyInstance(Cow.class.getClassLoader(),
                new Class[]{Cow.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getLocation":
                            throw new RuntimeException("mob position must not be read");
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeMob";
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

    private static Projectile arrowProxy(ProjectileSource shooter) {
        UUID arrowId = UUID.randomUUID();
        return (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(),
                new Class[]{Projectile.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getShooter": return shooter;
                        case "getUniqueId": return arrowId;
                        case "getLocation":
                            throw new RuntimeException("arrow position must not be read");
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

    private static Projectile throwingArrowProxy() {
        return (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(),
                new Class[]{Projectile.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getShooter":
                            throw new RuntimeException("shooter backend boom");
                        case "getUniqueId": return UUID.randomUUID();
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "BrokenArrow";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    /** Block x for a chunk: chunk c holds blocks [c*16, c*16+15]. */
    private static int blockX(int chunkX) {
        return chunkX * 16 + 5;
    }

    /**
     * Arrow carrying an owner UUID: the primary shooter identity.
     * A {@code null} owner exercises the shooter-wrapper fallback.
     */
    private static Projectile arrowProxyWithOwner(ProjectileSource shooter, UUID ownerId) {
        UUID arrowId = UUID.randomUUID();
        return (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(),
                new Class[]{Projectile.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getShooter": return shooter;
                        case "getOwnerUniqueId": return ownerId;
                        case "getUniqueId": return arrowId;
                        case "getLocation":
                            throw new RuntimeException("arrow position must not be read");
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
     * Shooter wrapper whose identity read is broken: only the owner UUID
     * can still identify the shot.
     */
    private static Player brokenIdentityShooter() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            throw new RuntimeException("wrapper identity boom");
                        case "isOnline": return true;
                        case "getName": return "Archer";
                        case "getLocation":
                            throw new RuntimeException("shooter position must not be read");
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "BrokenShooter";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == double.class) return 0d;
                            return null;
                    }
                });
    }

    private record TwoLands(UUID worldId, UUID ownerB, LandRegistryStore store, World world) {
    }

    private static TwoLands twoLands() {
        UUID worldId = UUID.randomUUID();
        LandId landA = new LandId(UUID.randomUUID());
        LandId landB = new LandId(UUID.randomUUID());
        UUID ownerB = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                new LandSnapshot(landA, "LandA", "landa", OwnerRef.player(UUID.randomUUID()),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, Instant.now(), Instant.now()),
                new LandSnapshot(landB, "LandB", "landb", OwnerRef.player(ownerB),
                        worldId, Set.of(new ChunkKey(worldId, 1, 0)),
                        List.of(), 0, 0, Instant.now(), Instant.now()))));
        return new TwoLands(worldId, ownerB, store, worldProxy(worldId));
    }

    /**
     * Per-actor verdicts: the member answers {@code memberState}, every other
     * actor (strangers, arrows, the environment fallback) answers
     * {@code strangerState}. Rule actions stay undecided.
     */
    private static ProtectionEngine actorEngine(LandRegistryStore store, UUID member,
                                                PermissionState memberState,
                                                PermissionState strangerState,
                                                AtomicReference<ProtectionActionType> seen) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (seen != null) {
                seen.set(action);
            }
            if (action.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            PermissionState state = member != null && member.equals(actor)
                    ? memberState : strangerState;
            return new PermissionContext(action, false, List.of(), state,
                    PermissionState.INHERIT);
        });
    }

    @Test
    void strangerArrowOnButtonInForeignLandDeniesAsButtonUse() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        // Shot from land A, button in land B: the verdict is the shooter's
        // standing in B, not the flight path.
        Projectile arrow = arrowProxy(shooterProxy(UUID.randomUUID(), true));
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(touch);
        assertTrue(touch.isCancelled(), "stranger-shot arrow on a protected button must not press it");
        assertEquals(ProtectionActionType.BUTTON_USE, seen.get(),
                "arrow-pressed button must route to BUTTON_USE");
    }

    @Test
    void ownerArrowOnButtonPassesEvenWhenShotFromAnotherLand() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        Projectile arrow = arrowProxy(shooterProxy(fx.ownerB(), true));
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(touch);
        assertFalse(touch.isCancelled(), "owner-shot arrow on own button must still press it");
    }

    @Test
    void strangerArrowOnPlateDeniesAsRedstoneUse() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        Projectile arrow = arrowProxy(shooterProxy(UUID.randomUUID(), true));
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(touch);
        assertTrue(touch.isCancelled(), "stranger-shot arrow on a protected plate must not press it");
        assertEquals(ProtectionActionType.REDSTONE_USE, seen.get(),
                "arrow-pressed plate must route to REDSTONE_USE");
    }

    @Test
    void ownerArrowOnPlatePasses() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        Projectile arrow = arrowProxy(shooterProxy(fx.ownerB(), true));
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(touch);
        assertFalse(touch.isCancelled(), "owner-shot arrow on own plate must still press it");
    }

    @Test
    void strangerArrowOnTargetDeniesAsRedstoneUse() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        Projectile arrow = arrowProxy(shooterProxy(UUID.randomUUID(), true));
        TargetHitEvent hit = new TargetHitEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(hit);
        assertTrue(hit.isCancelled(), "stranger-shot arrow on a protected target must not light it");
        assertEquals(ProtectionActionType.REDSTONE_USE, seen.get(),
                "arrow-hit target must route to REDSTONE_USE");
    }

    @Test
    void ownerArrowOnTargetPasses() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        Projectile arrow = arrowProxy(shooterProxy(fx.ownerB(), true));
        TargetHitEvent hit = new TargetHitEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(hit);
        assertFalse(hit.isCancelled(), "owner-shot arrow on own target must still light it");
    }

    @Test
    void offlineStrangerStillDeniedByIdentity() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        // Offline but identifiable: the verdict still follows the UUID.
        Projectile arrow = arrowProxy(shooterProxy(UUID.randomUUID(), false));
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(touch);
        assertTrue(touch.isCancelled(), "offline stranger-shot arrow must still be denied");
    }

    @Test
    void offlineMemberStillPassesByIdentity() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        Projectile arrow = arrowProxy(shooterProxy(fx.ownerB(), false));
        TargetHitEvent hit = new TargetHitEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(hit);
        assertFalse(hit.isCancelled(), "offline member-shot arrow must still pass");
    }

    @Test
    void unknownShooterFailsClosedInLandButStaysVanillaInWild() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        // No shooter reference at all: an unidentifiable source could be an
        // offline player, so a land must deny rather than wave it through as
        // environment. Wilderness has no land to protect and stays vanilla.
        EntityInteractEvent inLand = new EntityInteractEvent(arrowProxy(null),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(inLand);
        assertTrue(inLand.isCancelled(), "unidentifiable source on a protected button must deny");

        int wild = blockX(9);
        EntityInteractEvent inWild = new EntityInteractEvent(arrowProxy(null),
                blockProxy(fx.world(), wild, 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(inWild);
        assertFalse(inWild.isCancelled(), "wilderness button follows vanilla");

        TargetHitEvent wildTarget = new TargetHitEvent(arrowProxy(null),
                blockProxy(fx.world(), wild, 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(wildTarget);
        assertFalse(wildTarget.isCancelled(), "wilderness target follows vanilla");
    }

    @Test
    void throwingShooterLookupFailsClosedInLandButStaysVanillaInWild() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        EntityInteractEvent inLand = new EntityInteractEvent(throwingArrowProxy(),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_PRESSURE_PLATE));
        assertDoesNotThrow(() -> listener.onEntityInteract(inLand));
        assertTrue(inLand.isCancelled(), "shooter lookup failure on a protected plate must deny");

        int wild = blockX(9);
        EntityInteractEvent inWild = new EntityInteractEvent(throwingArrowProxy(),
                blockProxy(fx.world(), wild, 64, 5, Material.OAK_PRESSURE_PLATE));
        assertDoesNotThrow(() -> listener.onEntityInteract(inWild));
        assertFalse(inWild.isCancelled(), "wilderness plate follows vanilla");
    }

    @Test
    void dispenserShotKeepsExistingVerdict() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        // Dispensers keep the verdict they always had (the arrow's own
        // standing, denied in a denying land): this change must neither open
        // nor close that path.
        Block source = blockProxy(fx.world(), 15, 64, 5, Material.DISPENSER);
        EntityInteractEvent inLand = new EntityInteractEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(inLand);
        assertTrue(inLand.isCancelled(), "dispenser-shot arrow keeps its existing land verdict");

        int wild = blockX(9);
        EntityInteractEvent inWild = new EntityInteractEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), wild, 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(inWild);
        assertFalse(inWild.isCancelled(), "wilderness dispenser shot follows vanilla");
    }

    @Test
    void dispenserShotButtonStaysVanilla() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        // Buttons were never judged through this event before the change:
        // a dispenser-shot arrow keeps vanilla even in a denying land.
        Block source = blockProxy(fx.world(), 15, 64, 5, Material.DISPENSER);
        EntityInteractEvent touch = new EntityInteractEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(touch);
        assertFalse(touch.isCancelled(), "dispenser-shot arrow on a button stays vanilla");
        assertNull(seen.get(), "unregulated button touch must not consult the engine");
    }

    @Test
    void mobShotButtonStaysVanilla() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        EntityInteractEvent touch = new EntityInteractEvent(
                arrowProxy(mobProxy(UUID.randomUUID())),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(touch);
        assertFalse(touch.isCancelled(), "mob-shot arrow on a button stays vanilla");
        assertNull(seen.get(), "unregulated button touch must not consult the engine");
    }

    @Test
    void dispenserShotTargetStaysVanilla() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        // No target listener existed before the change either.
        Block source = blockProxy(fx.world(), 15, 64, 5, Material.DISPENSER);
        TargetHitEvent hit = new TargetHitEvent(arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(hit);
        assertFalse(hit.isCancelled(), "dispenser-shot arrow on a target stays vanilla");
        assertNull(seen.get(), "unregulated target hit must not consult the engine");
    }

    @Test
    void mobShotTargetStaysVanilla() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        TargetHitEvent hit = new TargetHitEvent(arrowProxy(mobProxy(UUID.randomUUID())),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(hit);
        assertFalse(hit.isCancelled(), "mob-shot arrow on a target stays vanilla");
        assertNull(seen.get(), "unregulated target hit must not consult the engine");
    }

    @Test
    void mobShotPlateKeepsArrowVerdict() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        // Plates were judged before the change by the arrow's own standing,
        // and still are: denied in a denying land, vanilla in the wild.
        EntityInteractEvent inLand = new EntityInteractEvent(
                arrowProxy(mobProxy(UUID.randomUUID())),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(inLand);
        assertTrue(inLand.isCancelled(), "mob-shot arrow keeps its existing plate verdict");

        int wild = blockX(9);
        EntityInteractEvent inWild = new EntityInteractEvent(
                arrowProxy(mobProxy(UUID.randomUUID())),
                blockProxy(fx.world(), wild, 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(inWild);
        assertFalse(inWild.isCancelled(), "wilderness mob shot follows vanilla");
    }

    @Test
    void ownerUniqueIdIdentifiesOfflineShooter() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        // The owner UUID is the primary identity: an offline member is still
        // recognised through it.
        Projectile arrow = arrowProxyWithOwner(shooterProxy(fx.ownerB(), false), fx.ownerB());
        TargetHitEvent hit = new TargetHitEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(hit);
        assertFalse(hit.isCancelled(), "offline member identified by owner UUID must still pass");
    }

    @Test
    void ownerUniqueIdSurvivesBrokenShooterWrapper() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        // The wrapper identity read is broken, but the owner UUID still
        // identifies a stranger: the touch must deny, not pass or throw.
        Projectile arrow = arrowProxyWithOwner(brokenIdentityShooter(), UUID.randomUUID());
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.OAK_BUTTON));
        assertDoesNotThrow(() -> listener.onEntityInteract(touch));
        assertTrue(touch.isCancelled(), "stranger identified by owner UUID must still deny");
    }

    @Test
    void nonPlayerSkipsHoldInSublandAndDepth() {
        SubFixture fx = subFixture();
        ProtectionEngine engine = engineWithSubAuth(fx, PermissionState.ALLOW, PermissionState.DENY);
        ProtectionListener listener = new ProtectionListener(engine);
        Block source = blockProxy(fx.world(), 15, 64, 5, Material.DISPENSER);

        // Even inside a denying subland cuboid, dispenser-shot buttons stay vanilla.
        EntityInteractEvent insideButton = new EntityInteractEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), 5, 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(insideButton);
        assertFalse(insideButton.isCancelled(), "dispenser-shot button stays vanilla in a subland");

        TargetHitEvent insideTarget = new TargetHitEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), 5, 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(insideTarget);
        assertFalse(insideTarget.isCancelled(), "dispenser-shot target stays vanilla in a subland");

        // Plates stay judged at any depth: the arrow has no binding, so both
        // the denying subland and the default-deny land refuse it.
        EntityInteractEvent insidePlate = new EntityInteractEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), 5, 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(insidePlate);
        assertTrue(insidePlate.isCancelled(), "dispenser-shot plate keeps its verdict in a subland");

        EntityInteractEvent abovePlate = new EntityInteractEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(fx.world(), 5, 80, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(abovePlate);
        assertTrue(abovePlate.isCancelled(), "dispenser-shot plate keeps its verdict above the subland");
    }

    @Test
    void nonPlayerWildernessStaysVanilla() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        World world = worldProxy(UUID.randomUUID());
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(store, null,
                PermissionState.ALLOW, PermissionState.DENY, seen));
        int wild = blockX(9);
        Block source = blockProxy(world, 15, 64, 5, Material.DISPENSER);

        EntityInteractEvent button = new EntityInteractEvent(
                arrowProxy(dispenserProxy(source)),
                blockProxy(world, wild, 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(button);
        assertFalse(button.isCancelled(), "wilderness dispenser button follows vanilla");

        TargetHitEvent target = new TargetHitEvent(
                arrowProxy(mobProxy(UUID.randomUUID())),
                blockProxy(world, wild, 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(target);
        assertFalse(target.isCancelled(), "wilderness mob target follows vanilla");

        assertNull(seen.get(), "wilderness non-player touches must not consult the engine");
    }

    @Test
    void wildernessControlStaysVanillaForAllThree() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        World world = worldProxy(UUID.randomUUID());
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(store, null,
                PermissionState.ALLOW, PermissionState.DENY, seen));
        int wild = blockX(9);
        Projectile arrow = arrowProxy(shooterProxy(UUID.randomUUID(), true));

        EntityInteractEvent button = new EntityInteractEvent(arrow,
                blockProxy(world, wild, 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(button);
        assertFalse(button.isCancelled(), "wilderness button follows vanilla");

        EntityInteractEvent plate = new EntityInteractEvent(arrow,
                blockProxy(world, wild, 64, 5, Material.OAK_PRESSURE_PLATE));
        listener.onEntityInteract(plate);
        assertFalse(plate.isCancelled(), "wilderness plate follows vanilla");

        TargetHitEvent target = new TargetHitEvent(arrow,
                blockProxy(world, wild, 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(target);
        assertFalse(target.isCancelled(), "wilderness target follows vanilla");

        assertNull(seen.get(), "wilderness touches must not consult the engine");
    }

    @Test
    void stoneButtonAlsoRoutesToButtonUse() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        Projectile arrow = arrowProxy(shooterProxy(UUID.randomUUID(), true));
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.STONE_BUTTON));
        listener.onEntityInteract(touch);
        assertTrue(touch.isCancelled(), "stranger-shot arrow on a stone button must not press it");
        assertEquals(ProtectionActionType.BUTTON_USE, seen.get(),
                "every button kind must route to BUTTON_USE");
    }

    @Test
    void nonRedstoneTouchStaysVanillaWithoutConsulting() {
        TwoLands fx = twoLands();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, seen));
        Projectile arrow = arrowProxy(shooterProxy(UUID.randomUUID(), true));
        EntityInteractEvent touch = new EntityInteractEvent(arrow,
                blockProxy(fx.world(), blockX(1), 64, 5, Material.CHEST));
        listener.onEntityInteract(touch);
        assertFalse(touch.isCancelled(), "non-redstone touches stay vanilla");
        assertNull(seen.get(), "non-redstone touches must not consult the engine");
    }

    @Test
    void arrowOnFarmlandKeepsExistingVerdict() {
        TwoLands fx = twoLands();
        var listener = new ProtectionListener(actorEngine(fx.store(), fx.ownerB(),
                PermissionState.ALLOW, PermissionState.DENY, null));
        // Farmland keeps the verdict it always had (the touching entity's own
        // standing): this change does not touch that path.
        EntityInteractEvent inLand = new EntityInteractEvent(
                arrowProxy(shooterProxy(UUID.randomUUID(), true)),
                blockProxy(fx.world(), blockX(1), 64, 5, Material.FARMLAND));
        listener.onEntityInteract(inLand);
        assertTrue(inLand.isCancelled(), "farmland keeps its existing land verdict");
    }

    private record SubFixture(UUID worldId, LandRegistryStore store, World world, UUID actor) {
    }

    private static SubFixture subFixture() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId subId = new SubLandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        // Den covers x/z 0-15 but only y 60-70: depth matters.
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "Den",
                new Cuboid(0, 60, 0, 15, 70, 15), worldId);
        LandSnapshot land = new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        return new SubFixture(worldId, store, worldProxy(worldId), actor);
    }

    private static Supplier<ConfigView> fixedViews() {
        PermissionDefaultsSnapshot empty = PermissionDefaultsSnapshot.empty();
        ConfigView view = new ConfigView(empty, LandRuleService.fromRuleSnapshot(empty), 0L, Map.of());
        return () -> view;
    }

    @Test
    void sublandDenyWinsInsideCuboidAndDepthFallsBackToLand() {
        SubFixture fx = subFixture();
        ProtectionEngine engine = engineWithSubAuth(fx, PermissionState.ALLOW, PermissionState.DENY);
        ProtectionListener listener = new ProtectionListener(engine);

        // y=64 sits inside the den cuboid: the sub DENY wins over land ALLOW.
        EntityInteractEvent inside = new EntityInteractEvent(
                arrowProxy(shooterProxy(fx.actor(), true)),
                blockProxy(fx.world(), 5, 64, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(inside);
        assertTrue(inside.isCancelled(), "button inside a denying subland must not press");

        TargetHitEvent insideTarget = new TargetHitEvent(
                arrowProxy(shooterProxy(fx.actor(), true)),
                blockProxy(fx.world(), 5, 64, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(insideTarget);
        assertTrue(insideTarget.isCancelled(), "target inside a denying subland must not light");

        // y=80 is the same x/z but above the den: falls back to land ALLOW.
        EntityInteractEvent above = new EntityInteractEvent(
                arrowProxy(shooterProxy(fx.actor(), true)),
                blockProxy(fx.world(), 5, 80, 5, Material.OAK_BUTTON));
        listener.onEntityInteract(above);
        assertFalse(above.isCancelled(), "button above the subland depth follows the land");

        TargetHitEvent aboveTarget = new TargetHitEvent(
                arrowProxy(shooterProxy(fx.actor(), true)),
                blockProxy(fx.world(), 5, 80, 5, Material.TARGET), BlockFace.UP, 15);
        listener.onTargetHit(aboveTarget);
        assertFalse(aboveTarget.isCancelled(), "target above the subland depth follows the land");
    }

    private static ProtectionEngine engineWithSubAuth(SubFixture fx,
                                                      PermissionState landState,
                                                      PermissionState subState) {
        // Re-derive the land/sub ids from the published snapshot for bindings.
        LandRegistry snapshot = fx.store().snapshot();
        LandSnapshot published = snapshot.lands().values().iterator().next();
        LandId landId = published.id();
        SubLandId subId = published.subLands().get(0).id();
        PermissionBinding landButton = new PermissionBinding(
                PermissionSubject.player(fx.actor()),
                new Permission(ProtectionActionType.BUTTON_USE, landState));
        PermissionBinding subButton = new PermissionBinding(
                PermissionSubject.player(fx.actor()),
                new Permission(ProtectionActionType.BUTTON_USE, subState));
        PermissionBinding landRedstone = new PermissionBinding(
                PermissionSubject.player(fx.actor()),
                new Permission(ProtectionActionType.REDSTONE_USE, landState));
        PermissionBinding subRedstone = new PermissionBinding(
                PermissionSubject.player(fx.actor()),
                new Permission(ProtectionActionType.REDSTONE_USE, subState));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landButton, landRedstone)),
                Map.of(subId, List.of(subButton, subRedstone)),
                Map.of(), Map.of());
        return new ProtectionEngine(fx.store()::snapshot,
                SnapshotPermissionContextProvider.atomic(fixedViews(), () -> auth));
    }
}
