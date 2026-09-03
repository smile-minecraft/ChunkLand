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
import com.smile.chunkland.protection.CrossBoundaryDecider.Relation;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Cross-boundary matrix: the seven directional actions reuse their source
 * rule, and the four source/destination combinations resolve consistently.
 */
class CrossBoundaryMatrixTest {

    /** Cross action -> the source rule it must reuse (no new rule types). */
    static final Map<ProtectionActionType, LandRuleType> EXPECTED_SOURCE_RULE = Map.of(
            ProtectionActionType.BLOCK_MOVE_IN, LandRuleType.PISTON,
            ProtectionActionType.BLOCK_MOVE_OUT, LandRuleType.PISTON,
            ProtectionActionType.FLUID_ENTER, LandRuleType.FLUID_FLOW,
            ProtectionActionType.FLUID_EXIT, LandRuleType.FLUID_FLOW,
            ProtectionActionType.ITEM_TRANSFER_IN, LandRuleType.HOPPER_TRANSFER,
            ProtectionActionType.ITEM_TRANSFER_OUT, LandRuleType.HOPPER_TRANSFER,
            ProtectionActionType.DISPENSER_CROSS_BOUNDARY, LandRuleType.MOB_GRIEFING);

    private static LandSnapshot landAt(UUID worldId, LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(owner),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    @Test
    void crossActionsReuseTheirSourceRule() {
        for (var entry : EXPECTED_SOURCE_RULE.entrySet()) {
            AtomicReference<LandRuleType> seen = new AtomicReference<>();
            LandRuleLookup capture = (id, rule, snapshot) -> {
                seen.set(rule);
                return Optional.of(PermissionState.ALLOW);
            };
            UUID worldId = UUID.randomUUID();
            LandId landId = new LandId(UUID.randomUUID());
            LandRegistryStore store = new LandRegistryStore();
            store.publish(LandRegistry.from(List.of(landAt(worldId, landId, UUID.randomUUID(), 0, 0))));
            var engine = new ProtectionEngine(store::snapshot,
                    new SnapshotPermissionContextProvider(capture, null));
            assertEquals(PermissionState.ALLOW,
                    engine.decide(UUID.randomUUID(), landId, entry.getKey()).outcome(),
                    entry.getKey() + " must follow its source rule");
            assertEquals(entry.getValue(), seen.get(),
                    entry.getKey() + " must read rule " + entry.getValue());
        }
    }

    @Test
    void crossActionsDenyWhenTheirSourceRuleDenies() {
        for (ProtectionActionType action : EXPECTED_SOURCE_RULE.keySet()) {
            UUID worldId = UUID.randomUUID();
            LandId landId = new LandId(UUID.randomUUID());
            LandRegistryStore store = new LandRegistryStore();
            store.publish(LandRegistry.from(List.of(landAt(worldId, landId, UUID.randomUUID(), 0, 0))));
            var engine = new ProtectionEngine(store::snapshot,
                    new SnapshotPermissionContextProvider(
                            (id, rule, snapshot) -> Optional.of(PermissionState.DENY), null));
            assertEquals(PermissionState.DENY,
                    engine.decide(UUID.randomUUID(), landId, action).outcome(),
                    action + " must deny when its source rule denies");
        }
    }

    // --- Relation classification: pure index reads --------------------------

    private record TwoLands(UUID worldId, LandId landA, LandId landB, LandRegistryStore store, World world) {
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

    @Test
    void relationClassifiesFourCombinationsFromIndexOnly() {
        TwoLands fx = twoLands();
        LandRegistry snapshot = fx.store().snapshot();
        // Land A owns chunk (0,0), land B owns chunk (1,0); chunk (9,9) is wild.
        assertEquals(Relation.WILDERNESS,
                CrossBoundaryDecider.relation(snapshot, fx.worldId(), 9, 9, 8, 8));
        assertEquals(Relation.WILD_TO_LAND,
                CrossBoundaryDecider.relation(snapshot, fx.worldId(), 9, 9, 0, 0));
        assertEquals(Relation.LAND_TO_WILD,
                CrossBoundaryDecider.relation(snapshot, fx.worldId(), 0, 0, 9, 9));
        assertEquals(Relation.CROSS_LAND,
                CrossBoundaryDecider.relation(snapshot, fx.worldId(), 0, 0, 1, 0));
        assertEquals(Relation.SAME_LAND,
                CrossBoundaryDecider.relation(snapshot, fx.worldId(), 0, 0, 0, 0));
    }

    // --- Pure matrix: wild side always allows --------------------------------

    @Test
    void matrixWildernessAlwaysPassesAndCrossLandNeedsBothEnds() {
        for (PermissionState src : PermissionState.values()) {
            for (PermissionState dst : PermissionState.values()) {
                assertFalse(CrossBoundaryDecider.denied(Relation.WILDERNESS, src, dst),
                        "wild -> wild must pass for " + src + "/" + dst);
                assertEquals(dst != PermissionState.ALLOW,
                        CrossBoundaryDecider.denied(Relation.WILD_TO_LAND, src, dst),
                        "wild -> land follows destination for " + src + "/" + dst);
                assertEquals(src != PermissionState.ALLOW,
                        CrossBoundaryDecider.denied(Relation.LAND_TO_WILD, src, dst),
                        "land -> wild follows source for " + src + "/" + dst);
                assertEquals(src != PermissionState.ALLOW || dst != PermissionState.ALLOW,
                        CrossBoundaryDecider.denied(Relation.CROSS_LAND, src, dst),
                        "land A -> land B needs both ends for " + src + "/" + dst);
                assertEquals(dst != PermissionState.ALLOW,
                        CrossBoundaryDecider.denied(Relation.SAME_LAND, src, dst),
                        "same land decides once at the destination for " + src + "/" + dst);
            }
        }
    }

    @Test
    void matrixTreatsMissingOutcomeAsDeny() {
        assertTrue(CrossBoundaryDecider.denied(Relation.WILD_TO_LAND,
                PermissionState.ALLOW, null));
        assertTrue(CrossBoundaryDecider.denied(Relation.LAND_TO_WILD,
                null, PermissionState.ALLOW));
        assertTrue(CrossBoundaryDecider.denied(Relation.CROSS_LAND,
                PermissionState.ALLOW, PermissionState.INHERIT));
    }

    // --- Engine matrix per directional pair -----------------------------------

    private static ProtectionEngine ruleEngine(LandRegistryStore store, LandRuleLookup lookup) {
        return new ProtectionEngine(store::snapshot,
                new SnapshotPermissionContextProvider(lookup, null));
    }

    /** Block x for a chunk: chunk c holds blocks [c*16, c*16+15]. */
    private static int blockX(int chunkX) {
        return chunkX * 16 + 5;
    }

    @Test
    void everyDirectionalPairHonoursFourCombinations() {
        List<ProtectionActionType[]> pairs = List.of(
                new ProtectionActionType[]{ProtectionActionType.BLOCK_MOVE_IN, ProtectionActionType.BLOCK_MOVE_OUT},
                new ProtectionActionType[]{ProtectionActionType.FLUID_ENTER, ProtectionActionType.FLUID_EXIT},
                new ProtectionActionType[]{ProtectionActionType.ITEM_TRANSFER_IN, ProtectionActionType.ITEM_TRANSFER_OUT},
                new ProtectionActionType[]{ProtectionActionType.DISPENSER_CROSS_BOUNDARY,
                        ProtectionActionType.DISPENSER_CROSS_BOUNDARY});
        int wild = blockX(9);
        int inA = blockX(0);
        int inB = blockX(1);
        int lane = 5;
        for (ProtectionActionType[] pair : pairs) {
            ProtectionActionType in = pair[0];
            ProtectionActionType out = pair[1];

            TwoLands fx = twoLands();
            var denyEngine = ruleEngine(fx.store(),
                    (id, rule, snapshot) -> Optional.of(PermissionState.DENY));
            assertFalse(CrossBoundaryDecider.crossDenied(denyEngine, fx.worldId(),
                    wild, lane, wild, lane, in, out), pairName(pair) + ": wild -> wild passes");
            assertTrue(CrossBoundaryDecider.crossDenied(denyEngine, fx.worldId(),
                    wild, lane, inA, lane, in, out), pairName(pair) + ": wild -> land denied");
            assertTrue(CrossBoundaryDecider.crossDenied(denyEngine, fx.worldId(),
                    inA, lane, wild, lane, in, out), pairName(pair) + ": land -> wild denied");
            assertTrue(CrossBoundaryDecider.crossDenied(denyEngine, fx.worldId(),
                    inA, lane, inB, lane, in, out), pairName(pair) + ": land A -> land B denied");
            assertTrue(CrossBoundaryDecider.crossDenied(denyEngine, fx.worldId(),
                    inA, lane, inA, lane, in, out), pairName(pair) + ": same land denied");

            var allowEngine = ruleEngine(fx.store(),
                    (id, rule, snapshot) -> Optional.of(PermissionState.ALLOW));
            assertFalse(CrossBoundaryDecider.crossDenied(allowEngine, fx.worldId(),
                    wild, lane, inA, lane, in, out), pairName(pair) + ": wild -> land passes");
            assertFalse(CrossBoundaryDecider.crossDenied(allowEngine, fx.worldId(),
                    inA, lane, wild, lane, in, out), pairName(pair) + ": land -> wild passes");
            assertFalse(CrossBoundaryDecider.crossDenied(allowEngine, fx.worldId(),
                    inA, lane, inB, lane, in, out), pairName(pair) + ": land A -> land B passes");
            assertFalse(CrossBoundaryDecider.crossDenied(allowEngine, fx.worldId(),
                    inA, lane, inA, lane, in, out), pairName(pair) + ": same land passes");

            // Split rules: A allows, B denies. Either-end deny must block A -> B,
            // while same-land A still passes (no false kill).
            var splitEngine = ruleEngine(fx.store(),
                    (id, rule, snapshot) -> Optional.of(
                            id.equals(fx.landA()) ? PermissionState.ALLOW : PermissionState.DENY));
            assertTrue(CrossBoundaryDecider.crossDenied(splitEngine, fx.worldId(),
                    inA, lane, inB, lane, in, out), pairName(pair) + ": A -> B blocked by B");
            assertTrue(CrossBoundaryDecider.crossDenied(splitEngine, fx.worldId(),
                    inB, lane, inA, lane, in, out), pairName(pair) + ": B -> A blocked by B");
            assertFalse(CrossBoundaryDecider.crossDenied(splitEngine, fx.worldId(),
                    inA, lane, inA, lane, in, out), pairName(pair) + ": same land A passes");
            assertFalse(CrossBoundaryDecider.crossDenied(splitEngine, fx.worldId(),
                    inA, lane, wild, lane, in, out), pairName(pair) + ": land A -> wild passes");
            assertTrue(CrossBoundaryDecider.crossDenied(splitEngine, fx.worldId(),
                    wild, lane, inB, lane, in, out), pairName(pair) + ": wild -> land B blocked");
        }
    }

    private static String pairName(ProtectionActionType[] pair) {
        return pair[0] + "/" + pair[1];
    }

    @Test
    void wildernessCrossingConsultsNoRuleSource() {
        TwoLands fx = twoLands();
        AtomicInteger lookups = new AtomicInteger();
        var engine = ruleEngine(fx.store(), (id, rule, snapshot) -> {
            lookups.incrementAndGet();
            return Optional.of(PermissionState.DENY);
        });
        int wild = blockX(9);
        assertFalse(CrossBoundaryDecider.crossDenied(engine, fx.worldId(),
                wild, 5, wild, 5,
                ProtectionActionType.FLUID_ENTER, ProtectionActionType.FLUID_EXIT));
        assertEquals(0, lookups.get(), "wild -> wild must stay index-only");
    }

    @Test
    void sameLandConsultsDestinationRuleOnlyOnce() {
        TwoLands fx = twoLands();
        AtomicInteger lookups = new AtomicInteger();
        var engine = ruleEngine(fx.store(), (id, rule, snapshot) -> {
            lookups.incrementAndGet();
            return Optional.of(PermissionState.DENY);
        });
        int inA = blockX(0);
        assertTrue(CrossBoundaryDecider.crossDenied(engine, fx.worldId(),
                inA, 5, inA, 5,
                ProtectionActionType.FLUID_ENTER, ProtectionActionType.FLUID_EXIT));
        assertEquals(1, lookups.get(),
                "same land must decide once at the destination, never re-read the source");
    }

    /**
     * Dispenser policy, stated as behaviour rather than by mirroring the
     * mapping constant.
     *
     * <p>Product decision: a dispenser is an ownerless mechanic, so its
     * cross-boundary effect is grief-like and reads the closest existing
     * rule, MOB_GRIEFING, which defaults to DENY (fail-closed). No new rule
     * type is introduced here; a dedicated dispenser switch, if ever wanted,
     * is left to a future milestone as a new rule type.
     */
    @Test
    void dispenserCrossingFollowsMobGriefingPolicy() {
        TwoLands fx = twoLands();
        int inA = blockX(0);
        int inB = blockX(1);
        var in = ProtectionActionType.DISPENSER_CROSS_BOUNDARY;

        // Default wiring has no rule source: every rule layer stays INHERIT,
        // so a crossing between lands must deny (fail-closed default).
        var defaultEngine = ruleEngine(fx.store(), null);
        assertTrue(CrossBoundaryDecider.crossDenied(defaultEngine, fx.worldId(),
                inA, 5, inB, 5, in, in),
                "dispenser A -> B must deny when no rule source is wired");

        // Only the grief-like rule allows, everything else denies: the
        // crossing must pass, so the dispenser does not read some other rule.
        var griefAllows = ruleEngine(fx.store(), (id, rule, snapshot) -> Optional.of(
                rule == LandRuleType.MOB_GRIEFING ? PermissionState.ALLOW : PermissionState.DENY));
        assertFalse(CrossBoundaryDecider.crossDenied(griefAllows, fx.worldId(),
                inA, 5, inB, 5, in, in),
                "dispenser A -> B must pass when the grief-like rule allows");

        // Only the grief-like rule denies, everything else allows: the
        // crossing must block, so the dispenser is not governed by another rule.
        var griefDenies = ruleEngine(fx.store(), (id, rule, snapshot) -> Optional.of(
                rule == LandRuleType.MOB_GRIEFING ? PermissionState.DENY : PermissionState.ALLOW));
        assertTrue(CrossBoundaryDecider.crossDenied(griefDenies, fx.worldId(),
                inA, 5, inB, 5, in, in),
                "dispenser A -> B must deny when the grief-like rule denies");
    }

    // --- Dispenser: dispense source plus landing direction ---------------------

    @Test
    void dispenseTargetStepsOneBlockAlongVelocitySign() {
        assertArrayEquals(new int[]{6, 5}, ProtectionListener.dispenseTarget(5, 5, 1.0, 0.0));
        assertArrayEquals(new int[]{4, 5}, ProtectionListener.dispenseTarget(5, 5, -2.5, 0.0));
        assertArrayEquals(new int[]{5, 5}, ProtectionListener.dispenseTarget(5, 5, 0.0, 0.0));
        assertArrayEquals(new int[]{15, 17}, ProtectionListener.dispenseTarget(16, 16, -3.0, 4.0));
    }

    @Test
    void dispenserDenyCancelsAndAllowPasses() throws Exception {
        TwoLands fx = twoLands();
        int inA = blockX(0);
        Block source = blockProxy(fx.world(), inA, 64, 5, Material.DISPENSER);
        BlockDispenseEvent denied = dispenseEvent(source, new Vector(1, 0, 0));
        new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.DENY, null))
                .onDispenserDispense(denied);
        assertTrue(denied.isCancelled(), "deny at the dispenser land must cancel");

        BlockDispenseEvent allowed = dispenseEvent(
                blockProxy(fx.world(), inA, 64, 5, Material.DISPENSER), new Vector(1, 0, 0));
        new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.ALLOW, null))
                .onDispenserDispense(allowed);
        assertFalse(allowed.isCancelled(), "allow at both ends must pass");
    }

    @Test
    void dispenserWildToWildPassesWithoutConsulting() throws Exception {
        TwoLands fx = twoLands();
        int wild = blockX(9);
        AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
        BlockDispenseEvent event = dispenseEvent(
                blockProxy(fx.world(), wild, 64, 5, Material.DISPENSER), new Vector(1, 0, 0));
        new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.DISPENSER_CROSS_BOUNDARY, PermissionState.DENY, seen))
                .onDispenserDispense(event);
        assertFalse(event.isCancelled(), "wild -> wild follows vanilla");
    }

    @Test
    void dispenserAcrossLandsCancelsWhenEitherEndDenies() throws Exception {
        TwoLands fx = twoLands();
        // Dispenser at the last block of chunk (0,0) firing +x: the landing
        // block sits in chunk (1,0), land B.
        Block source = blockProxy(fx.world(), 15, 64, 5, Material.DISPENSER);
        var split = ruleEngine(fx.store(), (id, rule, snapshot) -> Optional.of(
                id.equals(fx.landA()) ? PermissionState.ALLOW : PermissionState.DENY));
        BlockDispenseEvent crossing = dispenseEvent(source, new Vector(1, 0, 0));
        new ProtectionListener(split).onDispenserDispense(crossing);
        assertTrue(crossing.isCancelled(), "A -> B must cancel when B denies");
    }

    // --- Explosion: per-block terrain, per-entity damage -------------------------

    @Test
    void explosionEntityJudgesEachVictimAtItsOwnPosition() throws Exception {
        TwoLands fx = twoLands();
        int inA = blockX(0);
        int wild = blockX(9);
        var listener = new ProtectionListener(actionEngine(fx.store(),
                ProtectionActionType.EXPLOSION_ENTITY, PermissionState.DENY, null));

        Entity victimInLand = cowProxy(fx.world(), inA, 64, 5);
        EntityDamageEvent inLand = damageEvent(victimInLand,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        listener.onExplosionEntityDamage(inLand);
        assertTrue(inLand.isCancelled(), "victim inside a denying land is protected");

        Entity victimWild = cowProxy(fx.world(), wild, 64, wild);
        EntityDamageEvent inWild = damageEvent(victimWild,
                EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        listener.onExplosionEntityDamage(inWild);
        assertFalse(inWild.isCancelled(), "victim in the wild follows vanilla");

        EntityDamageEvent blockBoom = damageEvent(
                cowProxy(fx.world(), inA, 64, 5),
                EntityDamageEvent.DamageCause.BLOCK_EXPLOSION);
        listener.onExplosionEntityDamage(blockBoom);
        assertTrue(blockBoom.isCancelled(), "block explosions judge per entity too");
    }

    // --- Test doubles (same proxy style as the listener tests) --------------------

    private static World worldProxy(UUID worldId) {
        return (World) java.lang.reflect.Proxy.newProxyInstance(World.class.getClassLoader(),
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
        return (Block) java.lang.reflect.Proxy.newProxyInstance(Block.class.getClassLoader(),
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

    private static Cow cowProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Cow) java.lang.reflect.Proxy.newProxyInstance(Cow.class.getClassLoader(),
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

    private static BlockDispenseEvent dispenseEvent(Block source, Vector velocity) throws Exception {
        // A real ItemStack needs a running server registry, and the listener
        // only reads the source block and the velocity, so allocate directly.
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        BlockDispenseEvent event =
                (BlockDispenseEvent) unsafe.allocateInstance(BlockDispenseEvent.class);
        java.lang.reflect.Field blockField =
                org.bukkit.event.block.BlockEvent.class.getDeclaredField("block");
        blockField.setAccessible(true);
        blockField.set(event, source);
        java.lang.reflect.Field velocityField = BlockDispenseEvent.class.getDeclaredField("velocity");
        velocityField.setAccessible(true);
        velocityField.set(event, velocity);
        return event;
    }

    private static EntityDamageEvent damageEvent(Entity victim,
                                                 EntityDamageEvent.DamageCause cause) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        EntityDamageEvent event =
                (EntityDamageEvent) unsafe.allocateInstance(EntityDamageEvent.class);
        java.lang.reflect.Field entityField =
                org.bukkit.event.entity.EntityEvent.class.getDeclaredField("entity");
        entityField.setAccessible(true);
        entityField.set(event, victim);
        java.lang.reflect.Field causeField = EntityDamageEvent.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(event, cause);
        return event;
    }

    // --- Single-snapshot consistency: classification and decision share one
    // --- read, so a publish landing mid-flight cannot mix versions -----------

    @Test
    void crossingUsesSingleSnapshotWhenDestinationPublishedMidFlight() {
        UUID worldId = UUID.randomUUID();
        LandId landA = new LandId(UUID.randomUUID());
        LandId landB = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistry v1 = LandRegistry.from(List.of(landAt(worldId, landA, owner, 0, 0)));
        LandRegistry v2 = LandRegistry.from(List.of(
                landAt(worldId, landA, owner, 0, 0),
                landAt(worldId, landB, owner, 9, 0)));
        AtomicInteger calls = new AtomicInteger();
        Supplier<LandRegistry> phased = () -> {
            int call = calls.getAndIncrement();
            return call == 0 ? v1 : v2;
        };
        AtomicReference<LandRegistry> seen = new AtomicReference<>();
        var engine = new ProtectionEngine(phased,
                new SnapshotPermissionContextProvider((id, rule, snapshot) -> {
                    seen.set(snapshot);
                    return Optional.of(PermissionState.ALLOW);
                }, null));
        int srcX = blockX(0);
        int dstX = blockX(9);
        assertFalse(CrossBoundaryDecider.crossDenied(engine, worldId,
                srcX, 5, dstX, 5,
                ProtectionActionType.FLUID_ENTER, ProtectionActionType.FLUID_EXIT),
                "v1 classifies land -> wild, so the decision must stay on v1 and pass");
        assertEquals(1, calls.get(),
                "classification and decision must share a single snapshot read");
        assertSame(v1, seen.get(),
                "the source decision must resolve through v1, not the mid-flight v2");
    }

    @Test
    void crossingStaysDeniedWhenDestinationDeletedMidFlight() {
        UUID worldId = UUID.randomUUID();
        LandId landB = new LandId(UUID.randomUUID());
        LandRegistry v1 = LandRegistry.from(List.of(landAt(worldId, landB, UUID.randomUUID(), 1, 0)));
        LandRegistry v2 = LandRegistry.from(List.of());
        AtomicInteger calls = new AtomicInteger();
        Supplier<LandRegistry> phased = () -> {
            int call = calls.getAndIncrement();
            return call == 0 ? v1 : v2;
        };
        AtomicInteger lookups = new AtomicInteger();
        AtomicReference<LandRegistry> seen = new AtomicReference<>();
        var engine = new ProtectionEngine(phased,
                new SnapshotPermissionContextProvider((id, rule, snapshot) -> {
                    lookups.incrementAndGet();
                    seen.set(snapshot);
                    return Optional.of(PermissionState.DENY);
                }, null));
        int wild = blockX(9);
        int inB = blockX(1);
        assertTrue(CrossBoundaryDecider.crossDenied(engine, worldId,
                wild, 5, inB, 5,
                ProtectionActionType.FLUID_ENTER, ProtectionActionType.FLUID_EXIT),
                "v1 classifies wild -> land with a denying rule, so the decision must stay denied");
        assertEquals(1, calls.get(),
                "classification and decision must share a single snapshot read");
        assertEquals(1, lookups.get(),
                "the destination rule must be read once from v1");
        assertSame(v1, seen.get(),
                "the destination decision must resolve through v1, not the mid-flight v2");
    }

    private static ProtectionEngine actionEngine(LandRegistryStore store,
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
}
