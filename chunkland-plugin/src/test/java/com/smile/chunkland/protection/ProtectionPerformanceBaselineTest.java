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
import java.util.Arrays;
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
 * Performance baseline for the protection hot path (record-only).
 *
 * <p>Three figures, each measured with {@code System.nanoTime} over a fixed
 * iteration count after a warmup prefix:
 *
 * <ol>
 *   <li>Engine {@code decideAt} on wilderness (vanilla {@code ALLOW}, the
 *       provider and resolver are never consulted).</li>
 *   <li>Engine {@code decideAt} inside a land ({@code DENY} through the
 *       provider and the resolver).</li>
 *   <li>Listener {@code onBlockBreak} end to end on {@code DENY} with the
 *       rejection cooldown already hot (first deny sends, every measured
 *       deny is suppressed before any component is built).</li>
 * </ol>
 *
 * <p>No timing assertion: absolute numbers depend on hardware, JDK, and
 * neighbors, so they are only valid for relative comparison on one machine.
 * The tests assert the decisions stay correct while recording; the recorded
 * numbers live alongside the environment in the verification notes, and any
 * CI gate on them belongs to a later milestone, not to this baseline.
 *
 * <p>No sleeps anywhere: the cooldown runs on a fixed clock that never
 * advances, so every measured deny deterministically hits the cooldown.
 *
 * <p>Initial measurement (2026-09-03, Apple M4, OpenJDK 25.0.4, full-suite
 * context): wilderness-allow p99 ~125-292ns (N=20000), in-land-deny p99
 * ~1041-1458ns (N=20000), block-break-deny-cooldown-hit p99 ~1958-4208ns
 * (N=5000); warmup 2000 everywhere. Relative comparison only; the full
 * record lives in the verification notes.
 */
class ProtectionPerformanceBaselineTest {

    /** Fixed counts so numbers stay comparable across runs. */
    static final int WARMUP_ITERATIONS = 2_000;
    static final int ENGINE_ITERATIONS = 20_000;
    static final int HANDLER_ITERATIONS = 5_000;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-03T00:00:00Z");

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

    private static long percentile99(long[] samples) {
        Arrays.sort(samples);
        int index = (int) Math.ceil(0.99 * samples.length) - 1;
        return samples[Math.max(0, Math.min(index, samples.length - 1))];
    }

    @Test
    void engineDecideWildernessAllowBaseline() {
        UUID worldId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        ProtectionEngine engine = new ProtectionEngine(store::snapshot, (a, id, action, snapshot) -> {
            throw new AssertionError("wilderness must not consult the provider");
        });

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            engine.decideAt(actor, worldId, 9, 9, ProtectionActionType.BLOCK_BREAK);
        }
        long[] samples = new long[ENGINE_ITERATIONS];
        int allows = 0;
        for (int i = 0; i < ENGINE_ITERATIONS; i++) {
            long start = System.nanoTime();
            var decision = engine.decideAt(actor, worldId, 9, 9, ProtectionActionType.BLOCK_BREAK);
            samples[i] = System.nanoTime() - start;
            if (decision.outcome() == PermissionState.ALLOW) {
                allows++;
            }
        }
        assertEquals(ENGINE_ITERATIONS, allows, "wilderness must stay vanilla ALLOW");
        System.out.println("[baseline] engine-decide-wilderness-allow p99Ns=" + percentile99(samples)
                + " N=" + ENGINE_ITERATIONS + " warmup=" + WARMUP_ITERATIONS);
    }

    @Test
    void engineDecideInLandDenyBaseline() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landAt(worldId, landId, 0, 0))));
        ProtectionEngine engine = engineWithSubjectDefault(store, PermissionState.DENY);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            engine.decideAt(actor, worldId, 0, 0, ProtectionActionType.BLOCK_BREAK);
        }
        long[] samples = new long[ENGINE_ITERATIONS];
        int denies = 0;
        for (int i = 0; i < ENGINE_ITERATIONS; i++) {
            long start = System.nanoTime();
            var decision = engine.decideAt(actor, worldId, 0, 0, ProtectionActionType.BLOCK_BREAK);
            samples[i] = System.nanoTime() - start;
            if (decision.outcome() == PermissionState.DENY) {
                denies++;
            }
        }
        assertEquals(ENGINE_ITERATIONS, denies, "in-land subject path must stay DENY");
        System.out.println("[baseline] engine-decide-in-land-deny p99Ns=" + percentile99(samples)
                + " N=" + ENGINE_ITERATIONS + " warmup=" + WARMUP_ITERATIONS);
    }

    @Test
    void blockBreakDenyCooldownHitBaseline() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(
                List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        World world = worldProxy(worldId);
        Block block = blockProxy(world, 5, 64, 5);
        Player player = playerProxy(UUID.randomUUID(), world, 5, 64, 5);

        AtomicInteger renders = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        SelectionClock fixedClock = () -> FIXED_NOW;
        RejectionNotifier notifier = new RejectionNotifier(
                (target, message) -> sends.incrementAndGet(),
                (target, action, decision) -> {
                    renders.incrementAndGet();
                    return Component.text("denied: " + action.name());
                },
                new RejectionCooldown(fixedClock, Duration.ofSeconds(3)), Set.of());
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(store, PermissionState.DENY), notifier);

        BlockBreakEvent prime = new BlockBreakEvent(block, player);
        listener.onBlockBreak(prime);
        assertTrue(prime.isCancelled(), "DENY must cancel");
        assertEquals(1, sends.get(), "first deny sends once");
        assertEquals(1, renders.get(), "first deny builds one component");

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            listener.onBlockBreak(new BlockBreakEvent(block, player));
        }
        long[] samples = new long[HANDLER_ITERATIONS];
        int cancelled = 0;
        for (int i = 0; i < HANDLER_ITERATIONS; i++) {
            BlockBreakEvent event = new BlockBreakEvent(block, player);
            long start = System.nanoTime();
            listener.onBlockBreak(event);
            samples[i] = System.nanoTime() - start;
            if (event.isCancelled()) {
                cancelled++;
            }
        }
        assertEquals(HANDLER_ITERATIONS, cancelled, "every DENY must cancel");
        assertEquals(1, sends.get(), "cooldown-hit denies must send nothing extra");
        assertEquals(1, renders.get(), "cooldown-hit denies must build zero extra components");
        System.out.println("[baseline] listener-block-break-deny-cooldown-hit p99Ns="
                + percentile99(samples) + " N=" + HANDLER_ITERATIONS + " warmup=" + WARMUP_ITERATIONS);
    }
}
