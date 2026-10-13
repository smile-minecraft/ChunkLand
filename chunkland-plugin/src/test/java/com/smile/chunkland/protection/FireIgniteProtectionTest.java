package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.Test;

/**
 * Ignition (the moment a block catches fire) is judged separately from flame
 * spread and burning: a player lighting fire answers as FIRE_IGNITE, while an
 * ownerless ignition (lava, lightning, spread, dispenser steel, mob-shot or
 * ownerless projectiles) answers the existing FIRE_SPREAD rule.
 */
class FireIgniteProtectionTest {

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

    /**
     * Shooting player: identity answers, position never does. Any position
     * read is a region-thread hazard on Folia, so it throws here.
     */
    private static Player shooterProxy(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
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
                        case "getOwnerUniqueId": return null;
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

    private record Fixture(UUID worldId, LandId landId, UUID owner,
                           LandRegistryStore store, World world) {
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner,
                                       int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static Fixture fixture() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, owner, 0, 0))));
        return new Fixture(worldId, landId, owner, store, worldProxy(worldId));
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

    private static Supplier<ConfigView> fixedViews() {
        PermissionDefaultsSnapshot empty = PermissionDefaultsSnapshot.empty();
        ConfigView view = new ConfigView(empty, LandRuleService.fromRuleSnapshot(empty), 0L, Map.of());
        return () -> view;
    }

    /**
     * Engine with an explicit verdict per chain: subject actions answer
     * {@code subjectState} for FIRE_IGNITE (DENY otherwise), rule actions
     * answer {@code ruleState}. Every consulted action lands in
     * {@code seen}, so tests can prove which chain decided.
     */
    private static ProtectionEngine engineWithChains(LandRegistryStore store,
                                                     PermissionState subjectState,
                                                     PermissionState ruleState,
                                                     Set<ProtectionActionType> seen) {
        return new ProtectionEngine(store::snapshot, (actor, landId, a, snapshot) -> {
            if (seen != null) {
                seen.add(a);
            }
            if (a.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, ruleState);
            }
            PermissionState subject = a == ProtectionActionType.FIRE_IGNITE
                    ? subjectState : PermissionState.DENY;
            return new PermissionContext(a, false, List.of(), subject, PermissionState.INHERIT);
        });
    }

    private static ProtectionEngine realEngine(LandRegistryStore store,
                                               LandAuthorisationSnapshot auth) {
        return new ProtectionEngine(store::snapshot,
                SnapshotPermissionContextProvider.atomic(fixedViews(), () -> auth));
    }

    @Test
    void strangerIgniteDeniedWhileOwnerAndGranteePass() {
        Fixture fx = fixture();
        UUID stranger = UUID.randomUUID();
        UUID grantee = UUID.randomUUID();
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(fx.landId(), Map.of(grantee, Set.of(ProtectionActionType.FIRE_IGNITE))),
                Map.of());
        ProtectionListener listener = new ProtectionListener(realEngine(fx.store(), auth));

        BlockIgniteEvent denied = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, playerProxy(stranger));
        listener.onBlockIgnite(denied);
        assertTrue(denied.isCancelled(), "stranger lighting fire in a land must cancel");

        BlockIgniteEvent owner = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, playerProxy(fx.owner()));
        listener.onBlockIgnite(owner);
        assertFalse(owner.isCancelled(), "owner lighting fire in own land must pass");

        BlockIgniteEvent granted = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, playerProxy(grantee));
        listener.onBlockIgnite(granted);
        assertFalse(granted.isCancelled(), "explicitly authorised player must pass");
    }

    @Test
    void wildernessIgniteStaysVanilla() {
        Fixture fx = fixture();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_IGNITE, PermissionState.DENY, null));
        BlockIgniteEvent wild = new BlockIgniteEvent(
                blockProxy(fx.world(), 916, 64, 900, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL,
                playerProxy(UUID.randomUUID()));
        denying.onBlockIgnite(wild);
        assertFalse(wild.isCancelled(), "wilderness ignition follows vanilla: never cancel");
    }

    @Test
    void ownerlessIgniteReadsFireSpreadRule() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.DENY, seen));
        BlockIgniteEvent lava = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.LAVA, (Entity) null);
        denying.onBlockIgnite(lava);
        assertTrue(lava.isCancelled(), "lava ignition under FIRE_SPREAD DENY must cancel");
        assertEquals(ProtectionActionType.FIRE_SPREAD, seen.get(),
                "ownerless ignition must consult FIRE_SPREAD");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.ALLOW, null));
        BlockIgniteEvent allowed = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.LAVA, (Entity) null);
        allowing.onBlockIgnite(allowed);
        assertFalse(allowed.isCancelled(), "lava ignition under FIRE_SPREAD ALLOW must pass");
    }

    @Test
    void ownerlessIgniteNeverConsultsFireIgnite() {
        Fixture fx = fixture();
        Set<ProtectionActionType> seen = new java.util.HashSet<>();
        ProtectionListener listener = new ProtectionListener(
                engineWithChains(fx.store(), PermissionState.DENY, PermissionState.ALLOW, seen));
        BlockIgniteEvent lightning = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.LIGHTNING, (Entity) null);
        listener.onBlockIgnite(lightning);
        assertFalse(lightning.isCancelled(),
                "ownerless ignition under FIRE_SPREAD ALLOW must pass");
        assertFalse(seen.contains(ProtectionActionType.FIRE_IGNITE),
                "ownerless ignition must not consult FIRE_IGNITE, saw: " + seen);
        assertTrue(seen.contains(ProtectionActionType.FIRE_SPREAD),
                "ownerless ignition must consult FIRE_SPREAD, saw: " + seen);
    }

    @Test
    void lightningAndUnknownCauseHaveExplicitOutcomes() {
        Fixture fx = fixture();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.DENY, null));
        BlockIgniteEvent lightning = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.LIGHTNING, (Entity) null);
        denying.onBlockIgnite(lightning);
        assertTrue(lightning.isCancelled(), "lightning ignition under FIRE_SPREAD DENY must cancel");

        BlockIgniteEvent unknown = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.SPREAD, (Entity) null, (Block) null);
        denying.onBlockIgnite(unknown);
        assertTrue(unknown.isCancelled(), "ignition with no source at all must still deny");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.ALLOW, null));
        BlockIgniteEvent lightningAllowed = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.LIGHTNING, (Entity) null);
        allowing.onBlockIgnite(lightningAllowed);
        assertFalse(lightningAllowed.isCancelled(),
                "lightning ignition under FIRE_SPREAD ALLOW must pass");
    }

    @Test
    void ignitingBlockAcrossBoundaryDeniesEitherEnd() {
        Fixture fx = fixture();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.DENY, null));
        // Lava out in the wilderness lighting a block inside the land.
        BlockIgniteEvent inward = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.LAVA, (Entity) null,
                blockProxy(fx.world(), 916, 64, 900, Material.LAVA));
        denying.onBlockIgnite(inward);
        assertTrue(inward.isCancelled(), "ignition reaching into a denying land must cancel");

        // Fire inside the land lighting a wilderness block: must not escape.
        BlockIgniteEvent outward = new BlockIgniteEvent(
                blockProxy(fx.world(), 916, 64, 900, Material.FIRE),
                BlockIgniteEvent.IgniteCause.SPREAD, (Entity) null,
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE));
        denying.onBlockIgnite(outward);
        assertTrue(outward.isCancelled(), "ignition escaping a denying land must cancel");

        // Both ends in the wilderness stay vanilla.
        BlockIgniteEvent wild = new BlockIgniteEvent(
                blockProxy(fx.world(), 916, 64, 900, Material.FIRE),
                BlockIgniteEvent.IgniteCause.SPREAD, (Entity) null,
                blockProxy(fx.world(), 915, 64, 900, Material.FIRE));
        denying.onBlockIgnite(wild);
        assertFalse(wild.isCancelled(), "wilderness-to-wilderness ignition follows vanilla");
    }

    @Test
    void dispenserFlintAndSteelReadsFireSpread() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.DENY, seen));
        BlockIgniteEvent steel = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, (Entity) null,
                blockProxy(fx.world(), 5, 64, 6, Material.DISPENSER));
        denying.onBlockIgnite(steel);
        assertTrue(steel.isCancelled(), "dispenser steel under FIRE_SPREAD DENY must cancel");
        assertEquals(ProtectionActionType.FIRE_SPREAD, seen.get(),
                "dispenser steel must consult FIRE_SPREAD, never a player action");
    }

    @Test
    void playerShotProjectileIgniteJudgedAsShooter() {
        Fixture fx = fixture();
        UUID archer = UUID.randomUUID();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_IGNITE, PermissionState.DENY, seen));
        BlockIgniteEvent shot = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.ARROW, arrowProxy(shooterProxy(archer)));
        denying.onBlockIgnite(shot);
        assertTrue(shot.isCancelled(), "player-shot ignition under FIRE_IGNITE DENY must cancel");
        assertEquals(ProtectionActionType.FIRE_IGNITE, seen.get(),
                "player-shot ignition must consult FIRE_IGNITE, never FIRE_SPREAD");

        ProtectionListener allowing = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_IGNITE, PermissionState.ALLOW, null));
        BlockIgniteEvent allowed = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FIREBALL, arrowProxy(shooterProxy(archer)));
        allowing.onBlockIgnite(allowed);
        assertFalse(allowed.isCancelled(), "player-shot ignition under FIRE_IGNITE ALLOW must pass");
    }

    @Test
    void nonPlayerProjectileIgniteReadsFireSpread() {
        Fixture fx = fixture();
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        ProtectionListener denying = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.FIRE_SPREAD, PermissionState.DENY, seen));
        Block dispenser = blockProxy(fx.world(), 5, 64, 6, Material.DISPENSER);
        BlockIgniteEvent dispensed = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.ARROW, arrowProxy(dispenserProxy(dispenser)));
        denying.onBlockIgnite(dispensed);
        assertTrue(dispensed.isCancelled(), "dispenser-shot ignition under FIRE_SPREAD DENY must cancel");
        assertEquals(ProtectionActionType.FIRE_SPREAD, seen.get(),
                "dispenser-shot ignition must consult FIRE_SPREAD");

        Set<ProtectionActionType> mobSeen = new java.util.HashSet<>();
        ProtectionListener ruleAllowing = new ProtectionListener(
                engineWithChains(fx.store(), PermissionState.DENY, PermissionState.ALLOW, mobSeen));
        BlockIgniteEvent mobShot = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FIREBALL, arrowProxy(mobProxy(UUID.randomUUID())));
        ruleAllowing.onBlockIgnite(mobShot);
        assertFalse(mobShot.isCancelled(),
                "mob-shot ignition under FIRE_SPREAD ALLOW must pass");
        assertFalse(mobSeen.contains(ProtectionActionType.FIRE_IGNITE),
                "mob-shot ignition must never consult the player-permission chain, saw: " + mobSeen);

        BlockIgniteEvent ownerless = new BlockIgniteEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.ARROW, arrowProxy(null));
        denying.onBlockIgnite(ownerless);
        assertTrue(ownerless.isCancelled(), "ownerless projectile ignition must deny");
    }

    @Test
    void spreadAndBurnStaySeparateFromIgnite() {
        Fixture fx = fixture();
        Set<ProtectionActionType> seen = new java.util.HashSet<>();
        ProtectionListener listener = new ProtectionListener(
                engineWithChains(fx.store(), PermissionState.DENY, PermissionState.ALLOW, seen));
        BlockSpreadEvent spread = new BlockSpreadEvent(
                blockProxy(fx.world(), 6, 64, 5, Material.AIR),
                blockProxy(fx.world(), 5, 64, 5, Material.FIRE),
                blockStateProxy(fx.world(), 6, 64, 5));
        listener.onFireSpread(spread);
        assertFalse(spread.isCancelled(),
                "flame spread under FIRE_SPREAD ALLOW must pass even when FIRE_IGNITE denies");
        BlockBurnEvent burn = new BlockBurnEvent(
                blockProxy(fx.world(), 5, 64, 5, Material.OAK_PLANKS));
        listener.onFireBurn(burn);
        assertFalse(burn.isCancelled(),
                "burning blocks under FIRE_BURN ALLOW must pass even when FIRE_IGNITE denies");
        assertFalse(seen.contains(ProtectionActionType.FIRE_IGNITE),
                "spread and burn must not consult FIRE_IGNITE, saw: " + seen);
    }

    @Test
    void sublandDenyWinsInsideCuboidAndDepthFallsBackToLand() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId subId = new SubLandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "Den",
                new Cuboid(0, 60, 0, 15, 70, 15), worldId);
        LandSnapshot land = new LandSnapshot(landId, "Home", "home", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                List.of(sub), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        World world = worldProxy(worldId);
        PermissionBinding landAllow = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.FIRE_IGNITE, PermissionState.ALLOW));
        PermissionBinding subDeny = new PermissionBinding(
                PermissionSubject.player(actor),
                new Permission(ProtectionActionType.FIRE_IGNITE, PermissionState.DENY));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(landAllow)),
                Map.of(subId, List.of(subDeny)),
                Map.of(), Map.of());
        ProtectionListener listener = new ProtectionListener(realEngine(store, auth));

        BlockIgniteEvent inside = new BlockIgniteEvent(
                blockProxy(world, 5, 64, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, playerProxy(actor));
        listener.onBlockIgnite(inside);
        assertTrue(inside.isCancelled(), "ignition inside a denying subland must cancel");

        BlockIgniteEvent above = new BlockIgniteEvent(
                blockProxy(world, 5, 80, 5, Material.FIRE),
                BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, playerProxy(actor));
        listener.onBlockIgnite(above);
        assertFalse(above.isCancelled(),
                "ignition above the subland depth follows the land chain");
    }

    @Test
    void placingFireStillDecidesAsBlockPlace() throws Exception {
        Fixture fx = fixture();
        Set<ProtectionActionType> seen = new java.util.HashSet<>();
        ProtectionListener listener = new ProtectionListener(
                engineWithChains(fx.store(), PermissionState.DENY, PermissionState.ALLOW, seen));
        sun.misc.Unsafe unsafe = unsafe();
        BlockPlaceEvent placing = (BlockPlaceEvent) unsafe.allocateInstance(BlockPlaceEvent.class);
        Field blockField = BlockEvent.class.getDeclaredField("block");
        blockField.setAccessible(true);
        blockField.set(placing, blockProxy(fx.world(), 5, 64, 5, Material.FIRE));
        Field playerField = BlockPlaceEvent.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(placing, playerProxy(UUID.randomUUID()));
        listener.onBlockPlace(placing);
        assertTrue(placing.isCancelled(),
                "placing fire under BLOCK_PLACE DENY must cancel");
        assertTrue(seen.contains(ProtectionActionType.BLOCK_PLACE),
                "placing fire must consult BLOCK_PLACE, saw: " + seen);
        assertFalse(seen.contains(ProtectionActionType.FIRE_IGNITE),
                "placing fire must not consult FIRE_IGNITE, saw: " + seen);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    @Test
    void fireIgniteWiringIsComplete() {
        assertEquals(DecisionSource.SUBJECT_PERMISSION,
                ProtectionActionType.FIRE_IGNITE.decisionSource());
        var routes = ProtectionActionRegistry.validated(ProtectionActionRegistry.defaults());
        assertEquals(DecisionSource.SUBJECT_PERMISSION,
                routes.get(ProtectionActionType.FIRE_IGNITE));
        assertTrue(DirectTrustWhitelist.isAllowed(ProtectionActionType.FIRE_IGNITE),
                "trust must cover ignition like the other everyday actions");
        assertTrue(DirectTrustWhitelist.parseAllowed("FIRE_IGNITE")
                .map(action -> action == ProtectionActionType.FIRE_IGNITE).orElse(false));
        var coverage = ProtectionCoverage.coverageOf(ProtectionActionType.FIRE_IGNITE);
        assertFalse(coverage.mechanism().isBlank(),
                "coverage must state the ignition enforcement path");
    }
}
