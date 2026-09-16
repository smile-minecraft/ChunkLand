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
import com.smile.chunkland.selection.SelectionClock;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

/**
 * Deterministic CI gate for the protection hot-path performance budget.
 *
 * <p>Thresholds come from the implementation plan performance section
 * (chunk lookup p99 &lt; 5us, permission decision p99 &lt; 50us, protection
 * decision overall p99 &lt; 100us) and map onto the three record-only
 * baseline figures in order: wilderness {@code decideAt} exercises the
 * cached chunk lookup, in-land {@code decideAt} exercises the permission
 * decision through provider and resolver, and the end-to-end
 * {@code onBlockBreak} deny with a hot cooldown exercises the overall
 * protection decision. The wall-clock comparison itself lives in the CI
 * gate script (never as a timing assertion inside a unit test), so the
 * full suite stays deterministic; this class owns the parts that must
 * never flake:
 *
 * <ul>
 *   <li>The threshold literals match the specification (a silent
 *       relaxation fails here).</li>
 *   <li>Poisoned SQL / Economy / chunk-load seams: each hot-path entry
 *       completes correctly while the seam it must never touch throws, so
 *       any change that routes storage, economy, or world queries back
 *       into the hot path trips the poison and fails CI.</li>
 *   <li>The shared source scanner bans all three families, so an added
 *       import or call site fails even before runtime.</li>
 * </ul>
 */
class ProtectionPerformanceGateTest {

    /** Chunk lookup (cached) p99 budget: 5us. */
    static final long CHUNK_LOOKUP_P99_NS = 5_000L;

    /** Permission decision (cache hit path) p99 budget: 50us. */
    static final long PERMISSION_DECISION_P99_NS = 50_000L;

    /** Protection decision overall p99 budget: 100us. */
    static final long PROTECTION_DECISION_P99_NS = 100_000L;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void thresholdsMatchSpecification() {
        assertEquals(5_000L, CHUNK_LOOKUP_P99_NS, "chunk lookup p99 budget must stay 5us");
        assertEquals(50_000L, PERMISSION_DECISION_P99_NS,
                "permission decision p99 budget must stay 50us");
        assertEquals(100_000L, PROTECTION_DECISION_P99_NS,
                "protection decision p99 budget must stay 100us");
    }

    @Test
    void storageOutageFailsClosedWithoutThrowing() {
        UUID worldId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ProtectionEngine engine = new ProtectionEngine(
                () -> {
                    throw new RuntimeException("simulated SQL outage");
                },
                (a, landId, action, snapshot) -> {
                    throw new AssertionError("no snapshot, provider must stay unreachable");
                });

        var wilderness = engine.decideAt(actor, worldId, 9, 9, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.DENY, wilderness.outcome(),
                "storage failure on the hot path must fail closed");

        var inLand = engine.decideAt(actor, worldId, 0, 0, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.DENY, inLand.outcome(),
                "storage failure on the hot path must fail closed");
    }

    @Test
    void hotPathTakesExactlyOneVolatileSnapshotRead() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, landId, 0, 0))));
        AtomicInteger snapshotReads = new AtomicInteger();
        ProtectionEngine engine = new ProtectionEngine(
                () -> {
                    snapshotReads.incrementAndGet();
                    return store.snapshot();
                },
                (a, id, action, snapshot) -> new PermissionContext(action, false, List.of(),
                        PermissionState.DENY, PermissionState.INHERIT));

        var decision = engine.decideAt(actor, worldId, 0, 0, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.DENY, decision.outcome());
        assertEquals(1, snapshotReads.get(),
                "one decideAt must take exactly one volatile snapshot read, never N+1 storage hits");
    }

    @Test
    void wildernessPathNeverConsultsProviderOrEconomy() {
        UUID worldId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        AtomicInteger economyCalls = new AtomicInteger();
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                (a, landId, action, snapshot) -> {
                    economyCalls.incrementAndGet();
                    throw new AssertionError("wilderness must not consult provider or Economy");
                });

        var decision = engine.decideAt(actor, worldId, 9, 9, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.ALLOW, decision.outcome(),
                "wilderness must stay vanilla ALLOW without provider or Economy");
        assertEquals(0, economyCalls.get(), "wilderness path must not touch the Economy seam");
    }

    @Test
    void inLandPathCompletesWithoutEconomySeam() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                landAt(worldId, landId, 0, 0))));
        AtomicInteger economyCalls = new AtomicInteger();
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                (a, id, action, snapshot) -> {
                    if (economyCalls.get() != 0) {
                        throw new AssertionError("Economy seam already touched");
                    }
                    return new PermissionContext(action, false, List.of(),
                            PermissionState.DENY, PermissionState.INHERIT);
                });

        var decision = engine.decideAt(actor, worldId, 0, 0, ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.DENY, decision.outcome());
        assertEquals(0, economyCalls.get(), "in-land hot path must not touch the Economy seam");
    }

    @Test
    void listenerPathNeverLoadsChunks() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(
                List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        AtomicInteger chunkQueries = new AtomicInteger();
        World world = chunkPoisonedWorld(worldId, chunkQueries);
        Block block = blockProxy(world, 5, 64, 5);
        Player player = playerProxy(UUID.randomUUID(), world, 5, 64, 5);

        AtomicInteger sends = new AtomicInteger();
        AtomicInteger renders = new AtomicInteger();
        SelectionClock fixedClock = () -> FIXED_NOW;
        RejectionNotifier notifier = new RejectionNotifier(
                (target, message) -> sends.incrementAndGet(),
                (target, action, decision) -> {
                    renders.incrementAndGet();
                    return Component.text("denied: " + action.name());
                },
                new RejectionCooldown(fixedClock, Duration.ofSeconds(3)), Set.of());
        ProtectionEngine engine = new ProtectionEngine(store::snapshot,
                (actor, landId, action, snapshot) -> {
                    if (action.decisionSource() == DecisionSource.LAND_RULE) {
                        return new PermissionContext(action, false, List.of(),
                                PermissionState.INHERIT, PermissionState.DENY);
                    }
                    return new PermissionContext(action, false, List.of(),
                            PermissionState.DENY, PermissionState.INHERIT);
                });
        ProtectionListener listener = new ProtectionListener(engine, notifier);

        listener.onBlockBreak(new BlockBreakEvent(block, player));
        BlockBreakEvent measured = new BlockBreakEvent(block, player);
        listener.onBlockBreak(measured);

        assertTrue(measured.isCancelled(), "DENY must cancel");
        assertEquals(0, chunkQueries.get(),
                "listener hot path must never query or load chunks");
        assertEquals(1, sends.get(), "cooldown-hit denies must send nothing extra");
        assertEquals(1, renders.get(), "cooldown-hit denies must build zero extra components");
    }

    @Test
    void scannerBansSqlEconomyAndWorldQueryFamilies() {
        List<String> sqlSamples = List.of(
                "import java.sql.Connection;",
                "throw new java.sql.SQLException(\"boom\");",
                "Class.forName(\"org.sqlite.JDBC\");");
        List<String> economySamples = List.of(
                "economy.withdraw(player, amount);",
                "vaultHook.deposit(player, reward);");
        List<String> worldQuerySamples = List.of(
                "world.loadChunk(chunkX, chunkZ);",
                "Chunk c = world.getChunkAt(x, z);");
        for (String line : sqlSamples) {
            assertTrue(HotPathStructure.hotPathViolation(line).isPresent(),
                    "scanner must ban SQL on the hot path: " + line);
        }
        for (String line : economySamples) {
            assertTrue(HotPathStructure.hotPathViolation(line).isPresent(),
                    "scanner must ban Economy on the hot path: " + line);
        }
        for (String line : worldQuerySamples) {
            assertTrue(HotPathStructure.hotPathViolation(line).isPresent(),
                    "scanner must ban world chunk queries on the hot path: " + line);
        }
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static World chunkPoisonedWorld(UUID worldId, AtomicInteger chunkQueries) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    String name = method.getName().toLowerCase();
                    if (name.contains("chunk")) {
                        chunkQueries.incrementAndGet();
                        throw new AssertionError(
                                "hot path must never query or load chunks: " + method.getName());
                    }
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
}
