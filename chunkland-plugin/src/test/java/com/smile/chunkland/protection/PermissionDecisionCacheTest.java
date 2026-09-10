package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.runtime.api.ChunkLandReadApi;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Decision cache behaviour: epoch-keyed reuse on the protection path.
 *
 * <p>Every test here names the behaviour it pins, not the implementation:
 * the same actor, land, subland, action and four epochs must reuse one
 * decision without re-running the provider or resolver; any epoch move,
 * any identity move, and any unloaded, unknown or failed source must miss
 * and never serve a stale ALLOW or DENY. The bounded map must shed the
 * oldest entries first and stay within its budget after deterministic
 * concurrent hammering, without creating threads of its own.
 */
class PermissionDecisionCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    private record Fixture(UUID worldId, LandId landId, UUID owner, UUID stranger,
                           LandRegistry registry, LandRegistryStore store) {
    }

    private static Fixture fixture() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        LandSnapshot land = new LandSnapshot(landId, "TestLand", "testland",
                OwnerRef.player(owner), worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(), 3, 7, NOW, NOW);
        LandRegistryStore store = new LandRegistryStore();
        LandRegistry registry = LandRegistry.from(List.of(land));
        store.publish(registry);
        return new Fixture(worldId, landId, owner, stranger, registry, store);
    }

    private static Fixture fixtureWithSubland() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        SubLandId subId = new SubLandId(UUID.randomUUID());
        SubLandSnapshot sub = new SubLandSnapshot(subId, landId, "den",
                60, 70, Set.of(new ChunkKey(worldId, 0, 0)));
        LandSnapshot land = new LandSnapshot(landId, "TestLand", "testland",
                OwnerRef.player(owner), worldId,
                Set.of(new ChunkKey(worldId, 0, 0)), List.of(sub), 3, 7, NOW, NOW);
        LandRegistryStore store = new LandRegistryStore();
        LandRegistry registry = LandRegistry.from(List.of(land));
        store.publish(registry);
        return new Fixture(worldId, landId, owner, stranger, registry, store);
    }

    /** Mutable epoch holder so tests can move one epoch at a time. */
    private static final class Epochs {
        long global = 11;
        long world = 22;
        long policy = 7;
        long structure = 3;
        long ownerAcl = 33;
        boolean loaded = true;
    }

    private static PermissionDecisionEpochSource sourceOf(Epochs epochs) {
        return (actor, landId, sublandId, action, snapshot) -> {
            LandSnapshot land = snapshot.land(landId);
            if (land == null || !epochs.loaded) {
                return null;
            }
            boolean owner = land.ownerRef() instanceof OwnerRef.PlayerOwnerRef player
                    && player.uuid().equals(actor);
            return new PermissionDecisionCache.Key(actor, landId, sublandId,
                    land.worldId(), action, epochs.global, epochs.world,
                    epochs.policy, epochs.structure, epochs.ownerAcl, true, owner, false);
        };
    }

    private static PermissionContext allowCtx(ProtectionActionType action, UUID actor) {
        return new PermissionContext(action, false,
                List.of(new PermissionBinding(PermissionSubject.player(actor),
                        new Permission(action, PermissionState.ALLOW))),
                PermissionState.INHERIT, PermissionState.INHERIT);
    }

    private static ProtectionEngine cachedEngine(LandRegistryStore store,
                                                 AtomicInteger providerCalls,
                                                 Epochs epochs,
                                                 PermissionDecisionCache cache) {
        PermissionContextProvider provider = (actor, landId, action, snapshot) -> {
            providerCalls.incrementAndGet();
            return allowCtx(action, actor);
        };
        return new ProtectionEngine(store::snapshot, provider, cache, sourceOf(epochs));
    }

    @Test
    void repeatedDecideReusesOneDecisionWithoutRerunningProvider() {
        Fixture fix = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        ProtectionEngine engine = cachedEngine(fix.store(), providerCalls, epochs, cache);

        var first = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        var second = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);

        assertEquals(PermissionState.ALLOW, first.outcome());
        assertEquals(first, second, "same epochs must reuse the same decision");
        assertEquals(1, providerCalls.get(), "second decide must hit the cache");
        assertEquals(1, cache.size());
    }

    @Test
    void keySeparatesActorActionAndOwnerContext() {
        Fixture fix = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        PermissionContextProvider provider = (actor, landId, action, snapshot) -> {
            providerCalls.incrementAndGet();
            LandSnapshot land = snapshot.land(landId);
            boolean owner = land.ownerRef() instanceof OwnerRef.PlayerOwnerRef player
                    && player.uuid().equals(actor);
            if (owner) {
                return PermissionContext.builder(action).isOwner(true).build();
            }
            return allowCtx(action, actor);
        };
        ProtectionEngine engine =
                new ProtectionEngine(fix.store()::snapshot, provider, cache, sourceOf(epochs));

        var stranger = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        var owner = engine.decide(fix.owner(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        var strangerAgain =
                engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        var otherAction =
                engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.ENTRY);

        assertEquals(PermissionState.ALLOW, stranger.outcome());
        assertEquals(PermissionState.ALLOW, owner.outcome());
        assertEquals(stranger, strangerAgain);
        assertEquals(3, providerCalls.get(),
                "owner, stranger and other-action decisions must never share one entry");
        assertEquals(3, cache.size());
        // A repeated stranger decision after the owner decision must still hit.
        assertEquals(stranger, engine.decide(
                fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK));
        assertEquals(3, providerCalls.get());
        assertNotEquals(owner.explanation(), otherAction.explanation());
    }

    @Test
    void eachEpochMoveInvalidatesOldEntries() {
        Fixture fix = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        ProtectionEngine engine = cachedEngine(fix.store(), providerCalls, epochs, cache);

        engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        assertEquals(1, providerCalls.get());

        long[] moves = new long[5];
        epochs.global++;
        engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        moves[0] = providerCalls.get();
        epochs.world++;
        engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        moves[1] = providerCalls.get();
        epochs.policy++;
        engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        moves[2] = providerCalls.get();
        epochs.structure++;
        engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        moves[3] = providerCalls.get();
        epochs.ownerAcl++;
        engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        moves[4] = providerCalls.get();

        assertArrayEquals(new long[]{2, 3, 4, 5, 6}, moves,
                "every single epoch move must miss exactly once");
    }

    @Test
    void sublandPositionsDoNotShareLandEntries() {
        Fixture fix = fixtureWithSubland();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        PermissionDecisionEpochSource source = (actor, landId, sublandId, action, snapshot) -> {
            LandSnapshot land = snapshot.land(landId);
            if (land == null) {
                return null;
            }
            return new PermissionDecisionCache.Key(actor, landId, sublandId,
                    land.worldId(), action, epochs.global, epochs.world,
                    epochs.policy, epochs.structure, epochs.ownerAcl, true, false, false);
        };
        PermissionContextProvider provider = (actor, landId, action, snapshot) -> {
            providerCalls.incrementAndGet();
            return allowCtx(action, actor);
        };
        ProtectionEngine engine =
                new ProtectionEngine(fix.store()::snapshot, provider, cache, source);

        // Block (5, 65, 5) sits inside chunk (0, 0) and Y 60-70: covered by the subland.
        var inSub = engine.decideAtBlock(
                fix.stranger(), fix.worldId(), 5, 65, 5, ProtectionActionType.BLOCK_BREAK);
        var inSubAgain = engine.decideAtBlock(
                fix.stranger(), fix.worldId(), 5, 65, 5, ProtectionActionType.BLOCK_BREAK);
        // Block (5, 10, 5) is the same chunk but below the subland: land chain.
        var outsideSub = engine.decideAtBlock(
                fix.stranger(), fix.worldId(), 5, 10, 5, ProtectionActionType.BLOCK_BREAK);

        assertEquals(PermissionState.ALLOW, inSub.outcome());
        assertEquals(inSub, inSubAgain);
        assertEquals(PermissionState.ALLOW, outsideSub.outcome());
        assertEquals(2, providerCalls.get(),
                "subland and land positions must hold separate entries");
    }

    @Test
    void unloadedSourceIsFailClosedAndNeverCached() {
        Fixture fix = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        epochs.loaded = false;
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        ProtectionEngine engine = cachedEngine(fix.store(), providerCalls, epochs, cache);

        var first = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        var second = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);

        assertEquals(PermissionState.ALLOW, first.outcome());
        assertEquals(2, providerCalls.get(), "unloaded epochs must bypass the cache");
        assertEquals(0, cache.size());
        assertEquals(first, second);
    }

    @Test
    void providerFailureIsFailClosedAndNeverStored() {
        Fixture fix = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        PermissionContextProvider exploding = (actor, landId, action, snapshot) -> {
            providerCalls.incrementAndGet();
            throw new RuntimeException("backend boom");
        };
        ProtectionEngine engine =
                new ProtectionEngine(fix.store()::snapshot, exploding, cache, sourceOf(epochs));

        var first = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        var second = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);

        assertEquals(PermissionState.DENY, first.outcome());
        assertEquals(PermissionState.DENY, second.outcome());
        assertEquals(2, providerCalls.get(), "failed computations must never populate the cache");
        assertEquals(0, cache.size());
    }

    @Test
    void nullContextIsFailClosedAndNeverStored() {
        Fixture fix = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        PermissionContextProvider nulling = (actor, landId, action, snapshot) -> {
            providerCalls.incrementAndGet();
            return null;
        };
        ProtectionEngine engine =
                new ProtectionEngine(fix.store()::snapshot, nulling, cache, sourceOf(epochs));

        assertEquals(PermissionState.DENY,
                engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(PermissionState.DENY,
                engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK).outcome());
        assertEquals(2, providerCalls.get());
        assertEquals(0, cache.size());
    }

    @Test
    void wildernessAllowBypassesTheCache() {
        Fixture fix = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        ProtectionEngine engine = cachedEngine(fix.store(), providerCalls, epochs, cache);

        var first = engine.decideAt(
                fix.stranger(), fix.worldId(), 40, 40, ProtectionActionType.BLOCK_BREAK);
        var second = engine.decideAt(
                fix.stranger(), fix.worldId(), 40, 40, ProtectionActionType.BLOCK_BREAK);

        assertEquals(PermissionState.ALLOW, first.outcome());
        assertEquals(PermissionState.ALLOW, second.outcome());
        assertEquals(0, providerCalls.get(), "wilderness must never consult the provider");
        assertEquals(0, cache.size(), "wilderness ALLOW must never be cached long-term");
    }

    @Test
    void unknownLandBypassesTheCache() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        AtomicInteger providerCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        ProtectionEngine engine = cachedEngine(store, providerCalls, epochs, cache);

        var decision = engine.decide(
                UUID.randomUUID(), new LandId(UUID.randomUUID()), ProtectionActionType.BLOCK_BREAK);
        assertEquals(PermissionState.ALLOW, decision.outcome());
        assertEquals(0, providerCalls.get());
        assertEquals(0, cache.size());
    }

    @Test
    void boundedMapShedsOldestFirst() {
        PermissionDecisionCache cache = new PermissionDecisionCache(4);
        List<PermissionDecisionCache.Key> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PermissionDecisionCache.Key key = new PermissionDecisionCache.Key(
                    UUID.randomUUID(), new LandId(UUID.randomUUID()), null, UUID.randomUUID(),
                    ProtectionActionType.BLOCK_BREAK, 1, 1, 1, 1, 1, true, false, false);
            keys.add(key);
            cache.put(key, new PermissionDecision(PermissionState.ALLOW,
                    ProtectionActionType.BLOCK_BREAK.decisionSource(), "allow " + i));
        }
        assertTrue(cache.size() <= 4, "quiescent map must respect the bound, size=" + cache.size());
        assertNull(cache.get(keys.get(0)), "oldest entries must be shed first");
        assertNotNull(cache.get(keys.get(9)), "newest entries must survive");
    }

    @Test
    void maxEntriesClampRejectsIllegalValues() {
        assertEquals(0, PermissionDecisionCache.clampMaxEntries(0), "zero disables the cache");
        assertEquals(0, PermissionDecisionCache.clampMaxEntries(-5), "negative disables the cache");
        assertEquals(4096, PermissionDecisionCache.DEFAULT_MAX_ENTRIES);
        assertEquals(4096, PermissionDecisionCache.clampMaxEntries(4096));
        assertTrue(PermissionDecisionCache.clampMaxEntries(Integer.MAX_VALUE)
                <= PermissionDecisionCache.MAX_MAX_ENTRIES);

        PermissionDecisionCache disabled = new PermissionDecisionCache(0);
        PermissionDecisionCache.Key key = new PermissionDecisionCache.Key(
                UUID.randomUUID(), new LandId(UUID.randomUUID()), null, UUID.randomUUID(),
                ProtectionActionType.BLOCK_BREAK, 1, 1, 1, 1, 1, true, false, false);
        disabled.put(key, new PermissionDecision(PermissionState.ALLOW,
                ProtectionActionType.BLOCK_BREAK.decisionSource(), "allow"));
        assertNull(disabled.get(key), "disabled cache stores nothing");
        assertEquals(0, disabled.size());
    }

    @Test
    void loweringBudgetTrimsAndDisablingClears() {
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        for (int i = 0; i < 10; i++) {
            PermissionDecisionCache.Key key = new PermissionDecisionCache.Key(
                    UUID.randomUUID(), new LandId(UUID.randomUUID()), null, UUID.randomUUID(),
                    ProtectionActionType.BLOCK_BREAK, 1, 1, 1, 1, 1, true, false, false);
            cache.put(key, new PermissionDecision(PermissionState.ALLOW,
                    ProtectionActionType.BLOCK_BREAK.decisionSource(), "allow " + i));
        }
        assertEquals(10, cache.size());
        cache.setMaxEntries(4);
        assertTrue(cache.size() <= 4);
        cache.setMaxEntries(0);
        assertEquals(0, cache.size());
    }

    @Test
    void concurrentHammeringStaysBoundedAndCorrect() throws Exception {
        Fixture fix = fixture();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(32);
        AtomicInteger providerCalls = new AtomicInteger();
        PermissionContextProvider provider = (actor, landId, action, snapshot) -> {
            providerCalls.incrementAndGet();
            return allowCtx(action, actor);
        };
        ProtectionEngine engine =
                new ProtectionEngine(fix.store()::snapshot, provider, cache, sourceOf(epochs));

        List<UUID> actors = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            actors.add(UUID.randomUUID());
        }
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (int round = 0; round < 100; round++) {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int slot = t;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("start gate never opened");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted at start gate", interrupted);
                    }
                    for (int i = 0; i < 32; i++) {
                        UUID actor = actors.get((slot + i) % actors.size());
                        var decision = engine.decide(
                                actor, fix.landId(), ProtectionActionType.BLOCK_BREAK);
                        if (decision.outcome() != PermissionState.ALLOW) {
                            throw new IllegalStateException("hammered decision flipped to "
                                    + decision.outcome());
                        }
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers must all reach the gate");
            go.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    failures.add(failure);
                }
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker pool must drain");
            assertTrue(pool.isTerminated());
        }
        assertTrue(failures.isEmpty(), "no hammered decision may fail: " + failures);
        assertTrue(cache.size() <= 32,
                "quiescent map must respect the bound, size=" + cache.size());
        assertTrue(providerCalls.get() >= 1);
    }

    @Test
    void epochSwitchUnderConcurrencyNeverServesTheOldDecision() throws Exception {
        Fixture fix = fixture();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        AtomicInteger providerCalls = new AtomicInteger();
        PermissionContextProvider provider = (actor, landId, action, snapshot) -> {
            providerCalls.incrementAndGet();
            if (epochs.ownerAcl == 33L) {
                return allowCtx(action, actor);
            }
            // New generation answers from a different layer, so a stale hit
            // is distinguishable from a recompute by its explanation.
            return new PermissionContext(action, false, List.of(),
                    PermissionState.ALLOW, PermissionState.INHERIT);
        };
        ProtectionEngine engine =
                new ProtectionEngine(fix.store()::snapshot, provider, cache, sourceOf(epochs));

        var before = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        assertEquals(1, providerCalls.get());
        epochs.ownerAcl++;

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PermissionDecision>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate never opened");
                    }
                    return engine.decide(
                            fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<PermissionDecision> future : futures) {
                var decision = future.get(30, TimeUnit.SECONDS);
                assertEquals(PermissionState.ALLOW, decision.outcome());
                assertNotEquals(before.explanation(), decision.explanation(),
                        "post-switch decisions must be recomputed, never the old entry");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertTrue(providerCalls.get() > 1, "the switch must force a recompute");
        // The old-epoch entry lingers unserved (no active sweep by design)
        // while the recomputed decisions collapse into one new-epoch entry.
        assertEquals(2, cache.size());
    }

    @Test
    void readApiWithoutCacheAgreesWithCachedEngine() {
        Fixture fix = fixture();
        AtomicInteger engineCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        Epochs epochs = new Epochs();
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        PermissionContextProvider engineProvider = (actor, landId, action, snapshot) -> {
            engineCalls.incrementAndGet();
            return allowCtx(action, actor);
        };
        ProtectionEngine engine =
                new ProtectionEngine(fix.store()::snapshot, engineProvider, cache, sourceOf(epochs));
        PermissionContextProvider readProvider = (actor, landId, action, snapshot) -> {
            readCalls.incrementAndGet();
            return allowCtx(action, actor);
        };
        ChunkLandReadApi readApi = new ChunkLandReadApi(fix.store()::snapshot,
                readProvider, (landId, rule, snapshot) -> Optional.empty(),
                (landId, snapshot) -> Optional.empty());

        var decision = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        var cached = engine.decide(fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        boolean readable = readApi.can(
                fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);
        boolean readableAgain = readApi.can(
                fix.stranger(), fix.landId(), ProtectionActionType.BLOCK_BREAK);

        assertEquals(PermissionState.ALLOW, decision.outcome());
        assertEquals(decision, cached);
        assertTrue(readable, "read path must agree with the cached engine");
        assertTrue(readableAgain);
        assertEquals(1, engineCalls.get(), "engine must serve the repeat from cache");
        assertEquals(2, readCalls.get(),
                "read path holds no second cache: every can() recomputes, so the "
                        + "engine cache can never fork its semantics");
    }

    @Test
    void snapshotOwnerAclEpochBumpsOnEveryPublish() {
        LandAuthorisationSnapshot empty = LandAuthorisationSnapshot.empty();
        assertEquals(0, empty.ownerAclEpoch());
        assertEquals(-1, LandAuthorisationSnapshot.unloaded().ownerAclEpoch(),
                "unloaded marker carries a distinct epoch so it can never hit");

        LandAuthorisationSnapshot bumped = empty.withDirect(null, null, null);
        assertTrue(bumped.ownerAclEpoch() > empty.ownerAclEpoch());
        LandAuthorisationSnapshot bumpedAgain = bumped.withGeneric(null, null, null, null);
        assertTrue(bumpedAgain.ownerAclEpoch() > bumped.ownerAclEpoch());

        LandAuthorisationSnapshot pinned = empty.withOwnerAclEpoch(41);
        assertEquals(41, pinned.ownerAclEpoch());
        assertThrows(IllegalArgumentException.class, () -> empty.withOwnerAclEpoch(-2));
    }

    @Test
    void resolverStaysPureFunctionOfItsInput() {
        ProtectionActionType action = ProtectionActionType.BLOCK_BREAK;
        UUID actor = UUID.randomUUID();
        PermissionContext ctx = allowCtx(action, actor);
        var first = com.smile.chunkland.api.permission.PermissionResolver.resolve(ctx);
        var second = com.smile.chunkland.api.permission.PermissionResolver.resolve(ctx);
        assertEquals(first, second, "resolver must stay stateless across cache use");
    }

    @Test
    void configBudgetDefaultsExplicitZeroAndInvalid() {
        var absent = com.smile.chunkland.config.ConfigSchema.parseYamlText("limits:\n"
                + "  max-lands-per-player: 5\n");
        assertEquals(4096, absent.decisionCacheMaxEntries());

        var emptyDoc = com.smile.chunkland.config.ConfigSchema.parseYamlText("worlds: {}");
        assertEquals(4096, emptyDoc.decisionCacheMaxEntries());

        var explicit = com.smile.chunkland.config.ConfigSchema.parseYamlText("limits:\n"
                + "  max-decision-cache-entries: 128\n");
        assertEquals(128, explicit.decisionCacheMaxEntries());

        var zero = com.smile.chunkland.config.ConfigSchema.parseYamlText("limits:\n"
                + "  max-decision-cache-entries: 0\n");
        assertEquals(0, zero.decisionCacheMaxEntries());

        assertThrows(com.smile.chunkland.config.ConfigValidationException.class,
                () -> com.smile.chunkland.config.ConfigSchema.parseYamlText("limits:\n"
                        + "  max-decision-cache-entries: -1\n"));
        assertThrows(com.smile.chunkland.config.ConfigValidationException.class,
                () -> com.smile.chunkland.config.ConfigSchema.parseYamlText("limits:\n"
                        + "  max-decision-cache-entries: lots\n"));
    }

    @Test
    void reloadReplacesBudgetAndBumpsGlobalEpoch() throws Exception {
        var first = com.smile.chunkland.config.ConfigSchema.parseYamlText("limits:\n"
                + "  max-decision-cache-entries: 64\n");
        var second = com.smile.chunkland.config.ConfigSchema.parseYamlText("limits:\n"
                + "  max-decision-cache-entries: 0\n");
        java.util.Queue<com.smile.chunkland.config.ChunkLandConfig> pending =
                new java.util.ArrayDeque<>(List.of(first, second));
        com.smile.chunkland.config.ConfigLoader loader =
                new com.smile.chunkland.config.ConfigLoader() {
                    @Override
                    public com.smile.chunkland.config.ChunkLandConfig load() {
                        var next = pending.poll();
                        if (next == null) {
                            throw new IllegalStateException("no more configs");
                        }
                        return next;
                    }

                    @Override
                    public String describe() {
                        return "queue-loader";
                    }
                };
        var service = new com.smile.chunkland.config.ConfigService(loader);
        assertEquals(64, service.current().decisionCacheMaxEntries());
        long epochBefore = service.current().globalPolicyEpoch();

        var after = service.reload();
        assertEquals(0, after.decisionCacheMaxEntries(), "reload must adopt the file budget");
        assertEquals(epochBefore + 1, after.globalPolicyEpoch());
        assertEquals(0, service.current().decisionCacheMaxEntries());
    }

    @Test
    void configViewCarriesReloadEpochsPerWorld() {
        UUID worldA = UUID.randomUUID();
        String yaml = "worlds:\n"
                + "  world_a:\n"
                + "    claim-enabled: true\n";
        var initial = com.smile.chunkland.config.ConfigSchema.parseYamlText(yaml);
        var service = new com.smile.chunkland.config.ConfigService(new YamlLoader(yaml));
        assertEquals(initial.worlds().keySet(), service.current().worlds().keySet());
        var cache = new PermissionDefaultsCache(service::current,
                name -> "world_a".equals(name) ? Optional.of(worldA) : Optional.empty(),
                ignored -> {
                });
        service.addListener(cache);

        var before = cache.view();
        assertEquals(0L, before.globalPolicyEpoch());
        assertEquals(0L, before.worldPolicyEpoch(worldA));

        service.reload();
        var after = cache.view();
        assertEquals(1L, after.globalPolicyEpoch(), "reload must move the cached view epoch");
        assertEquals(1L, after.worldPolicyEpoch(worldA), "known worlds bump together");
        assertEquals(after.globalPolicyEpoch(),
                after.worldPolicyEpoch(UUID.randomUUID()), "unknown worlds fall back to global");
    }

    @Test
    void atomicProviderKeyTracksViewEpochsAndRefusesUnusableSources() {
        Fixture fix = fixture();
        var empty = PermissionDefaultsSnapshot.empty();
        var rules = com.smile.chunkland.runtime.rule.LandRuleService.fromRuleSnapshot(empty);
        var view0 = new PermissionDefaultsCache.ConfigView(empty, rules, 5L, Map.of());
        var view1 = new PermissionDefaultsCache.ConfigView(
                empty, rules, 6L, Map.of(fix.worldId(), 9L));
        var views = new java.util.concurrent.atomic.AtomicReference<>(view0);
        var auth = new java.util.concurrent.atomic.AtomicReference<LandAuthorisationSnapshot>(
                LandAuthorisationSnapshot.empty());
        var provider = SnapshotPermissionContextProvider.atomic(views::get, auth::get);

        var key0 = provider.keyFor(
                fix.stranger(), fix.landId(), null, ProtectionActionType.BLOCK_BREAK, fix.registry());
        assertNotNull(key0);
        assertEquals(5L, key0.globalPolicyEpoch());
        assertEquals(5L, key0.worldPolicyEpoch(), "unmapped world falls back to global");

        views.set(view1);
        var key1 = provider.keyFor(
                fix.stranger(), fix.landId(), null, ProtectionActionType.BLOCK_BREAK, fix.registry());
        assertNotEquals(key0, key1, "global epoch move must change the key");
        assertEquals(9L, key1.worldPolicyEpoch(), "mapped world carries its own epoch");

        auth.set(LandAuthorisationSnapshot.unloaded());
        assertNull(provider.keyFor(fix.stranger(), fix.landId(), null,
                ProtectionActionType.BLOCK_BREAK, fix.registry()),
                "unloaded durable source must bypass the cache");

        auth.set(LandAuthorisationSnapshot.empty());
        assertNull(provider.keyFor(fix.stranger(), new LandId(UUID.randomUUID()), null,
                ProtectionActionType.BLOCK_BREAK, fix.registry()),
                "unknown land must bypass the cache");

        var legacy = new SnapshotPermissionContextProvider(null, null);
        assertNull(legacy.keyFor(fix.stranger(), fix.landId(), null,
                ProtectionActionType.BLOCK_BREAK, fix.registry()),
                "legacy provider without epochs must bypass the cache");
    }

    private static ConcurrentLinkedQueue<PermissionDecisionCache.Key> liveQueue(
            PermissionDecisionCache cache) throws Exception {
        var stateField = PermissionDecisionCache.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object generation = ((java.util.concurrent.atomic.AtomicReference<?>) stateField.get(cache)).get();
        var queueField = generation.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<PermissionDecisionCache.Key> queue =
                (ConcurrentLinkedQueue<PermissionDecisionCache.Key>) queueField.get(generation);
        return queue;
    }

    private static int queueDepth(PermissionDecisionCache cache) throws Exception {
        return liveQueue(cache).size();
    }

    private static List<PermissionDecisionCache.Key> queueSnapshot(PermissionDecisionCache cache)
            throws Exception {
        return new ArrayList<>(liveQueue(cache));
    }

    private static PermissionDecisionCache.Key freshKey() {
        return new PermissionDecisionCache.Key(
                UUID.randomUUID(), new LandId(UUID.randomUUID()), null, UUID.randomUUID(),
                ProtectionActionType.BLOCK_BREAK, 1, 1, 1, 1, 1, true, false, false);
    }

    private static PermissionDecision allowDecision(String detail) {
        return new PermissionDecision(PermissionState.ALLOW,
                ProtectionActionType.BLOCK_BREAK.decisionSource(), detail);
    }

    @Test
    void clearAndDisableDrainFifoQueue() throws Exception {
        PermissionDecisionCache cache = new PermissionDecisionCache(64);
        List<PermissionDecisionCache.Key> firstGen = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            PermissionDecisionCache.Key key = freshKey();
            firstGen.add(key);
            cache.put(key, allowDecision("gen1 " + i));
        }
        assertEquals(50, cache.size());
        assertEquals(50, queueDepth(cache), "queue must track every live entry before clear");

        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, queueDepth(cache), "clear must drain the FIFO queue, not just the map");

        for (int i = 0; i < 10; i++) {
            cache.put(freshKey(), allowDecision("gen2 " + i));
        }
        assertEquals(10, cache.size());
        cache.setMaxEntries(0);
        assertEquals(0, cache.size());
        assertEquals(0, queueDepth(cache), "disabling must drain the FIFO queue, not just the map");
        assertEquals(0, cache.maxEntries());

        cache.setMaxEntries(64);
        List<PermissionDecisionCache.Key> fresh = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PermissionDecisionCache.Key key = freshKey();
            fresh.add(key);
            cache.put(key, allowDecision("gen3 " + i));
        }
        assertEquals(10, cache.size());
        assertTrue(queueDepth(cache) <= 64);
        List<PermissionDecisionCache.Key> queued = queueSnapshot(cache);
        for (PermissionDecisionCache.Key stale : firstGen) {
            assertFalse(queued.contains(stale), "re-enabled queue must not carry pre-disable keys");
        }
        for (PermissionDecisionCache.Key key : fresh) {
            assertNotNull(cache.get(key), "re-enabled cache must serve fresh entries");
        }
    }

    @Test
    void disableReenableLifecycleStaysBounded() throws Exception {
        PermissionDecisionCache cache = new PermissionDecisionCache(32);
        List<PermissionDecisionCache.Key> firstRound = new ArrayList<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (int round = 0; round < 100; round++) {
            cache.setMaxEntries(32);
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            List<PermissionDecisionCache.Key> roundKeys =
                    java.util.Collections.synchronizedList(new ArrayList<>());
            for (int t = 0; t < threads; t++) {
                int slot = t;
                int currentRound = round;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("start gate never opened");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted at start gate", interrupted);
                    }
                    for (int i = 0; i < 8; i++) {
                        PermissionDecisionCache.Key key = freshKey();
                        roundKeys.add(key);
                        cache.put(key, allowDecision("round " + currentRound + " slot " + slot + " i " + i));
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers must all reach the gate");
            go.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    failures.add(failure);
                }
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker pool must drain");
            assertTrue(pool.isTerminated(), "no thread may leak past its round");
            if (round == 0) {
                firstRound.addAll(roundKeys);
            }
            assertTrue(cache.size() <= 32,
                    "quiescent map must respect the bound, size=" + cache.size());
            assertTrue(queueDepth(cache) <= 32,
                    "quiescent queue must respect the bound, depth=" + queueDepth(cache));

            cache.setMaxEntries(0);
            assertEquals(0, cache.size(), "disable must empty the map on round " + round);
            assertEquals(0, queueDepth(cache), "disable must empty the queue on round " + round);
        }
        assertTrue(failures.isEmpty(), "no lifecycle put may fail: " + failures);

        cache.setMaxEntries(32);
        for (int i = 0; i < 32; i++) {
            cache.put(freshKey(), allowDecision("final " + i));
        }
        assertTrue(cache.size() <= 32);
        assertTrue(queueDepth(cache) <= 32);
        List<PermissionDecisionCache.Key> queued = queueSnapshot(cache);
        for (PermissionDecisionCache.Key stale : firstRound) {
            assertFalse(queued.contains(stale), "final queue must not carry round-0 keys");
        }
    }

    @Test
    void lifecycleSwapNeverResurrectsOldPuts() throws Exception {
        for (int round = 0; round < 100; round++) {
            PermissionDecisionCache cache = new PermissionDecisionCache(64);
            PermissionDecisionCache.Key oldKey = freshKey();
            cache.put(oldKey, allowDecision("old " + round));
            assertNotNull(cache.get(oldKey), "precondition: old entry visible on round " + round);

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean(false);
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("start gate never opened");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted at start gate", interrupted);
                    }
                    try {
                        while (!stop.get()) {
                            cache.put(freshKey(), allowDecision("hammer"));
                            cache.get(oldKey);
                        }
                    } catch (RuntimeException failure) {
                        failures.add(failure);
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers must all reach the gate");
            go.countDown();

            // While puts are still in flight, disable and require the new
            // generation to look empty: an in-flight put from the previous
            // generation must never resurface through the new state.
            for (int sweep = 0; sweep < 20; sweep++) {
                cache.clear();
                cache.setMaxEntries(0);
                for (int probe = 0; probe < 50; probe++) {
                    assertEquals(0, cache.size(),
                            "disabled generation must hide in-flight puts on round " + round);
                }
                assertEquals(0, queueDepth(cache),
                        "disabled generation must hide in-flight queue nodes on round " + round);
                cache.setMaxEntries(64);
                assertNull(cache.get(oldKey),
                        "re-enabled cache must never serve the pre-disable entry on round " + round);
                cache.setMaxEntries(0);
            }

            stop.set(true);
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker pool must drain");
            assertTrue(failures.isEmpty(), "no hammered put may fail: " + failures);

            // Quiescent re-enable serves only fresh entries and stays bounded.
            cache.setMaxEntries(64);
            assertNull(cache.get(oldKey), "old entry must stay gone after round " + round);
            List<PermissionDecisionCache.Key> fresh = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                PermissionDecisionCache.Key key = freshKey();
                fresh.add(key);
                cache.put(key, allowDecision("fresh " + round + " " + i));
            }
            for (PermissionDecisionCache.Key key : fresh) {
                assertNotNull(cache.get(key), "re-enabled cache must serve fresh entries");
            }
            assertTrue(cache.size() <= 64, "quiescent map must respect the bound");
            assertTrue(queueDepth(cache) <= 64, "quiescent queue must respect the bound");
            assertEquals(cache.size(), queueDepth(cache),
                    "quiescent map and queue must agree with no orphans on round " + round);
        }
    }

    @Test
    void clearRacingDisableMustStayDisabled() throws Exception {
        // Either linearization ends disabled: clear-then-disable publishes
        // the disabled generation, and disable-then-clear must re-read the
        // disabled budget instead of republishing the stale enabled one.
        // Spinning clearers keep the swap instant crowded so a get-then-set
        // clear cannot slip its stale budget past the disable unnoticed.
        for (int round = 0; round < 100; round++) {
            PermissionDecisionCache cache = new PermissionDecisionCache(64);
            PermissionDecisionCache.Key oldKey = freshKey();
            cache.put(oldKey, allowDecision("old " + round));
            assertNotNull(cache.get(oldKey), "precondition on round " + round);

            int clearThreads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(clearThreads + 1);
            CountDownLatch ready = new CountDownLatch(clearThreads + 1);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < clearThreads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate never opened");
                    }
                    for (int i = 0; i < 2000; i++) {
                        cache.clear();
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate never opened");
                }
                cache.setMaxEntries(0);
                return null;
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers must reach the gate");
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker pool must drain");
            assertTrue(pool.isTerminated(), "no thread may leak past round " + round);

            assertEquals(0, cache.maxEntries(),
                    "clear racing disable must stay disabled on round " + round);
            assertEquals(0, cache.size(), "disabled generation must be empty on round " + round);
            assertEquals(0, queueDepth(cache),
                    "disabled generation must hide queue nodes on round " + round);
            assertNull(cache.get(oldKey),
                    "disabled cache must never hit the pre-race entry on round " + round);
        }
    }

    @Test
    void clearRacingReenableMustStayEnabled() throws Exception {
        // Either linearization ends enabled: re-enable publishes budget 64
        // and a clear around it must preserve whichever budget is live when
        // it lands, never republishing the stale disabled one.
        for (int round = 0; round < 100; round++) {
            PermissionDecisionCache cache = new PermissionDecisionCache(0);
            assertEquals(0, cache.maxEntries(), "precondition on round " + round);

            int clearThreads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(clearThreads + 1);
            CountDownLatch ready = new CountDownLatch(clearThreads + 1);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < clearThreads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate never opened");
                    }
                    for (int i = 0; i < 2000; i++) {
                        cache.clear();
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate never opened");
                }
                cache.setMaxEntries(64);
                return null;
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers must reach the gate");
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker pool must drain");
            assertTrue(pool.isTerminated(), "no thread may leak past round " + round);

            assertEquals(64, cache.maxEntries(),
                    "clear racing re-enable must stay enabled on round " + round);
            assertEquals(0, cache.size(), "fresh generation must be empty on round " + round);
            assertEquals(0, queueDepth(cache),
                    "fresh generation must start with an empty queue on round " + round);
            PermissionDecisionCache.Key fresh = freshKey();
            cache.put(fresh, allowDecision("fresh " + round));
            assertNotNull(cache.get(fresh),
                    "re-enabled cache must serve fresh entries on round " + round);
        }
    }

    @Test
    void concurrentLifecycleRacesStayLinearizableAndBounded() throws Exception {
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (int round = 0; round < 100; round++) {
            PermissionDecisionCache cache = new PermissionDecisionCache(64);
            PermissionDecisionCache.Key oldKey = freshKey();
            cache.put(oldKey, allowDecision("old " + round));
            assertNotNull(cache.get(oldKey), "precondition on round " + round);

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate never opened");
                }
                for (int i = 0; i < 20; i++) {
                    cache.clear();
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate never opened");
                }
                for (int i = 0; i < 10; i++) {
                    cache.setMaxEntries(0);
                    cache.setMaxEntries(64);
                }
                return null;
            }));
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("start gate never opened");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted at start gate", interrupted);
                    }
                    try {
                        for (int i = 0; i < 50; i++) {
                            PermissionDecisionCache.Key key = freshKey();
                            cache.put(key, allowDecision("hammer"));
                            cache.get(key);
                            cache.get(oldKey);
                        }
                    } catch (RuntimeException failure) {
                        failures.add(failure);
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers must reach the gate");
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker pool must drain");
            assertTrue(pool.isTerminated(), "no thread may leak past round " + round);

            // Quiesce to a known budget: disabled hides everything, and a
            // fresh enabled generation serves only fresh entries within bound.
            cache.setMaxEntries(0);
            assertEquals(0, cache.maxEntries(), "quiesced budget on round " + round);
            assertEquals(0, cache.size(), "disabled must hide raced puts on round " + round);
            assertEquals(0, queueDepth(cache), "disabled must hide raced nodes on round " + round);
            assertNull(cache.get(oldKey),
                    "disabled must never hit the pre-race entry on round " + round);

            cache.setMaxEntries(64);
            assertEquals(64, cache.maxEntries(), "re-enabled budget on round " + round);
            assertNull(cache.get(oldKey), "old entry must stay gone on round " + round);
            List<PermissionDecisionCache.Key> fresh = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                PermissionDecisionCache.Key key = freshKey();
                fresh.add(key);
                cache.put(key, allowDecision("fresh " + round + " " + i));
            }
            for (PermissionDecisionCache.Key key : fresh) {
                assertNotNull(cache.get(key), "re-enabled cache must serve fresh entries");
            }
            assertTrue(cache.size() <= 64, "quiescent map must respect the bound");
            assertTrue(queueDepth(cache) <= 64, "quiescent queue must respect the bound");
            assertEquals(cache.size(), queueDepth(cache),
                    "quiescent map and queue must agree with no orphans on round " + round);
        }
        assertTrue(failures.isEmpty(), "no hammered put may fail: " + failures);
    }

    private static final class YamlLoader implements com.smile.chunkland.config.ConfigLoader {
        private final String yaml;

        YamlLoader(String yaml) {
            this.yaml = yaml;
        }

        @Override
        public com.smile.chunkland.config.ChunkLandConfig load() {
            return com.smile.chunkland.config.ConfigSchema.parseYamlText(yaml);
        }

        @Override
        public String describe() {
            return "yaml-text";
        }
    }
}
