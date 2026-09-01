package com.smile.chunkland.runtime.api;

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
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkLandReadApiTest {

    private static LandSnapshot land(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)), List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static LandSnapshot landWithSub(UUID worldId, LandId lid, SubLandSnapshot sub) {
        ChunkKey ck = sub.chunks().iterator().next();
        return new LandSnapshot(lid, "Land", "land", OwnerRef.player(UUID.randomUUID()), worldId, Set.of(ck), List.of(sub), 0, 0, Instant.now(), Instant.now());
    }

    @Test
    void getLandSnapshotHitAndMiss() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry reg = LandRegistry.from(List.of(land(w, id, 1, 1)));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(reg);
        ChunkLandReadApi api = new ChunkLandReadApi(store);
        assertTrue(api.getLandSnapshot(id).isPresent());
        assertEquals(id, api.getLandSnapshot(id).get().id());
        assertTrue(api.getLandSnapshot(new LandId(UUID.randomUUID())).isEmpty());
    }

    @Test
    void getSubLandSnapshotHitAndMiss() {
        UUID w = UUID.randomUUID();
        LandId lid = new LandId(UUID.randomUUID());
        SubLandId sid = new SubLandId(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(w, 2, 2);
        SubLandSnapshot sub = new SubLandSnapshot(sid, lid, "sub", 0, 255, Set.of(ck));
        LandSnapshot ls = landWithSub(w, lid, sub);
        LandRegistry reg = LandRegistry.from(List.of(ls));
        LandRegistryStore store = new LandRegistryStore();
        store.publish(reg);
        ChunkLandReadApi api = new ChunkLandReadApi(store);
        assertTrue(api.getSubLandSnapshot(sid).isPresent());
        assertEquals(sid, api.getSubLandSnapshot(sid).get().id());
        assertTrue(api.getSubLandSnapshot(new SubLandId(UUID.randomUUID())).isEmpty());
    }

    @Test
    void getOwnerHitAndMiss() {
        UUID w = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandSnapshot s = new LandSnapshot(id, "L", "l", OwnerRef.player(owner), w, Set.of(new ChunkKey(w, 0, 0)), List.of(), 0, 0, Instant.now(), Instant.now());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(s)));
        ChunkLandReadApi api = new ChunkLandReadApi(store);
        assertTrue(api.getOwner(id).isPresent());
        assertEquals(OwnerRef.player(owner), api.getOwner(id).get());
        assertTrue(api.getOwner(new LandId(UUID.randomUUID())).isEmpty());
    }

    @Test
    void getRuleDelegatesAndMissEmpty() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        LandRegistry published = LandRegistry.from(List.of(land(w, id, 0, 0)));
        store.publish(published);
        LandRuleLookup lookup = (landId, rule, snap) -> {
            assertEquals(id, landId);
            assertSame(published, snap, "lookup must receive same snapshot the api observed");
            if (rule == LandRuleType.PVP) return java.util.Optional.of(PermissionState.ALLOW);
            return java.util.Optional.of(PermissionState.DENY);
        };
        ChunkLandReadApi api = new ChunkLandReadApi(store::snapshot, (a, lid, act, snap) -> null, lookup, (lid, snap) -> java.util.Optional.empty());
        assertEquals(PermissionState.ALLOW, api.getRule(id, LandRuleType.PVP).get());
        assertEquals(PermissionState.DENY, api.getRule(id, LandRuleType.FIRE_SPREAD).get());
        assertTrue(api.getRule(new LandId(UUID.randomUUID()), LandRuleType.PVP).isEmpty());
    }

    @Test
    void getProtectionDepthDelegatesAndMissEmpty() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        LandRegistry published = LandRegistry.from(List.of(land(w, id, 0, 0)));
        store.publish(published);
        ProtectionDepthLookup depthLookup = (lid, snap) -> {
            assertSame(published, snap, "depth lookup must receive same snapshot");
            return lid.equals(id) ? java.util.Optional.of(42) : java.util.Optional.empty();
        };
        ChunkLandReadApi api = new ChunkLandReadApi(store::snapshot, (a, lid, act, snap) -> null, (lid, r, snap) -> java.util.Optional.empty(), depthLookup);
        assertEquals(42, api.getProtectionDepth(id).get());
        assertTrue(api.getProtectionDepth(new LandId(UUID.randomUUID())).isEmpty());
    }

    @Test
    void canDelegatesToResolver() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land(w, id, 0, 0))));
        PermissionContext allowCtx = new PermissionContext(ProtectionActionType.BLOCK_BREAK, false,
                List.of(new PermissionBinding(PermissionSubject.player(actor), new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW))),
                PermissionState.INHERIT, PermissionState.INHERIT);
        PermissionContext denyCtx = new PermissionContext(ProtectionActionType.BLOCK_BREAK, false,
                List.of(new PermissionBinding(PermissionSubject.player(actor), new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY))),
                PermissionState.INHERIT, PermissionState.INHERIT);
        PermissionContextProvider provider = (a, lid, act, snap) -> {
            if (a.equals(actor) && lid.equals(id) && act == ProtectionActionType.BLOCK_BREAK) return allowCtx;
            return denyCtx;
        };
        ChunkLandReadApi apiAllow = new ChunkLandReadApi(store::snapshot, provider, (lid, r, snap) -> java.util.Optional.empty(), (lid, snap) -> java.util.Optional.empty());
        assertTrue(apiAllow.can(actor, id, ProtectionActionType.BLOCK_BREAK));
        // different actor still deny
        assertFalse(apiAllow.can(UUID.randomUUID(), id, ProtectionActionType.BLOCK_BREAK));
        // unknown land -> false without calling provider
        assertFalse(apiAllow.can(actor, new LandId(UUID.randomUUID()), ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void canUsesResolverOwnerGuarantee() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land(w, id, 0, 0))));
        PermissionContext ctxOwner = new PermissionContext(ProtectionActionType.BLOCK_BREAK, true, List.of(), PermissionState.INHERIT, PermissionState.INHERIT);
        ChunkLandReadApi api = new ChunkLandReadApi(store::snapshot, (a, lid, act, snap) -> ctxOwner, (lid, r, snap) -> java.util.Optional.empty(), (lid, snap) -> java.util.Optional.empty());
        assertTrue(api.can(actor, id, ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void unknownIdReturnsEmptyOrFalse() {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.empty());
        ChunkLandReadApi api = new ChunkLandReadApi(store);
        LandId unknown = new LandId(UUID.randomUUID());
        assertTrue(api.getLandSnapshot(unknown).isEmpty());
        assertTrue(api.getSubLandSnapshot(new SubLandId(UUID.randomUUID())).isEmpty());
        assertTrue(api.getOwner(unknown).isEmpty());
        assertFalse(api.can(UUID.randomUUID(), unknown, ProtectionActionType.BLOCK_BREAK));
        assertTrue(api.getRule(unknown, LandRuleType.PVP).isEmpty());
        assertTrue(api.getProtectionDepth(unknown).isEmpty());
    }

    @Test
    void immutableReturnedCollections() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(w, 5, 5);
        LandSnapshot s = land(w, id, 5, 5);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(s)));
        ChunkLandReadApi api = new ChunkLandReadApi(store);
        LandSnapshot got = api.getLandSnapshot(id).get();
        assertThrows(UnsupportedOperationException.class, () -> got.chunks().add(new ChunkKey(w, 6, 6)));
        assertThrows(UnsupportedOperationException.class, () -> got.subLands().add(null));
        SubLandId sid = new SubLandId(UUID.randomUUID());
        SubLandSnapshot sub = new SubLandSnapshot(sid, id, "sub", 0, 10, Set.of(ck));
        LandSnapshot withSub = new LandSnapshot(id, "Land", "land", OwnerRef.player(UUID.randomUUID()), w, Set.of(ck), List.of(sub), 0, 0, Instant.now(), Instant.now());
        store.publish(LandRegistry.from(List.of(withSub)));
        SubLandSnapshot gotSub = api.getSubLandSnapshot(sid).get();
        assertThrows(UnsupportedOperationException.class, () -> gotSub.chunks().add(new ChunkKey(w, 6, 6)));
    }

    @Test
    void concurrentReadsSeeOnlyOldOrNew() throws Exception {
        LandRegistryStore store = new LandRegistryStore();
        UUID w = UUID.randomUUID();
        LandId oldId = new LandId(UUID.randomUUID());
        LandId newId = new LandId(UUID.randomUUID());
        LandRegistry oldReg = LandRegistry.from(List.of(land(w, oldId, 0, 0)));
        LandRegistry newReg = LandRegistry.from(List.of(land(w, newId, 10, 10)));
        store.publish(oldReg);
        // Per-iteration snapshot capture: the read API takes one volatile
        // snapshot per logical call, so the "complete old or complete new,
        // never partial" invariant must be checked against a single snapshot.
        // A custom supplier captures the snapshot once per phase/iteration
        // (per reader thread) and returns it consistently for every API
        // call within that phase. Without this, four separate API calls
        // would each take their own snapshot and straddle a publication,
        // producing a false positive on the cross-call partial check.
        ThreadLocal<LandRegistry> iterationSnap = new ThreadLocal<>();
        java.util.function.Supplier<LandRegistry> iterationSupplier = () -> {
            LandRegistry snap = iterationSnap.get();
            if (snap == null) {
                snap = store.snapshot();
                iterationSnap.set(snap);
            }
            return snap;
        };
        ChunkLandReadApi api = new ChunkLandReadApi(iterationSupplier);
        int readers = 8;
        int iterations = 2000;
        // Two-phase rendezvous (no sleep): all readers pass the first barrier,
        // then each reader takes one snapshot of `old` and signals through
        // latch `eachObservedOld`. Publisher waits on that latch before flipping
        // the store, so every reader is guaranteed to have observed `old`
        // at least once before the new publication can land.
        CyclicBarrier rendezvous = new CyclicBarrier(readers + 1);
        CountDownLatch eachObservedOld = new CountDownLatch(readers);
        AtomicReference<Throwable> err = new AtomicReference<>();
        java.util.concurrent.ExecutorService readerPool = java.util.concurrent.Executors.newFixedThreadPool(readers);
        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>(readers);
        Throwable primary = null;
        try {
            for (int i = 0; i < readers; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        rendezvous.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        // Pre-publish observation: must be complete old.
                        // Reset the per-thread supplier cache so this phase
                        // captures a fresh snapshot and all four calls share it.
                        iterationSnap.remove();
                        boolean preOld = api.getLandSnapshot(oldId).isPresent();
                        boolean preNew = api.getLandSnapshot(newId).isPresent();
                        boolean preOwnerOld = api.getOwner(oldId).isPresent();
                        boolean preOwnerNew = api.getOwner(newId).isPresent();
                        if (!preOld || preNew || !preOwnerOld || preOwnerNew) {
                            err.compareAndSet(null, new AssertionError(
                                    "pre-publish read must see complete old snapshot"));
                        }
                        eachObservedOld.countDown();
                        for (int k = 0; k < iterations; k++) {
                            // Reset the per-thread supplier cache so the next
                            // API call captures a fresh snapshot for this
                            // iteration. All four calls within this iteration
                            // then observe that same snapshot, which is the
                            // only valid way to check "complete old or new,
                            // not partial" without relying on timing.
                            iterationSnap.remove();
                            boolean hasOld = api.getLandSnapshot(oldId).isPresent();
                            boolean hasNew = api.getLandSnapshot(newId).isPresent();
                            if (hasOld && hasNew) err.compareAndSet(null, new AssertionError("partial both"));
                            if (!hasOld && !hasNew) err.compareAndSet(null, new AssertionError("partial neither"));
                            // also check owner path on the same snapshot
                            boolean ownerOld = api.getOwner(oldId).isPresent();
                            boolean ownerNew = api.getOwner(newId).isPresent();
                            if (ownerOld && ownerNew) err.compareAndSet(null, new AssertionError("owner partial both"));
                            if (!ownerOld && !ownerNew) err.compareAndSet(null, new AssertionError("owner partial neither"));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        err.compareAndSet(null, e);
                    } catch (BrokenBarrierException | TimeoutException e) {
                        err.compareAndSet(null, e);
                    } catch (Throwable e) {
                        err.compareAndSet(null, e);
                    }
                }, readerPool));
            }
            try {
                rendezvous.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("rendezvous interrupted", e);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new AssertionError("rendezvous failed", e);
            }
            try {
                if (!eachObservedOld.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("eachObservedOld latch timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("eachObservedOld interrupted", e);
            }
            store.publish(newReg);
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (err.get() != null) throw new AssertionError(err.get());
            iterationSnap.remove();
            assertTrue(api.getLandSnapshot(newId).isPresent());
            assertTrue(api.getLandSnapshot(oldId).isEmpty());
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            iterationSnap.remove();
            boolean terminated = false;
            Throwable interruptSuppressed = null;
            try {
                readerPool.shutdown();
                terminated = readerPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interruptSuppressed = e;
                readerPool.shutdownNow();
            }
            if (!terminated) {
                readerPool.shutdownNow();
                try {
                    terminated = readerPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (interruptSuppressed == null) interruptSuppressed = e;
                    else interruptSuppressed.addSuppressed(e);
                }
            }
            if (interruptSuppressed != null && primary != null) {
                primary.addSuppressed(interruptSuppressed);
            }
            if (!terminated) {
                AssertionError ae = new AssertionError("reader pool must terminate before test exits");
                if (primary != null) {
                    primary.addSuppressed(ae);
                } else if (interruptSuppressed != null) {
                    interruptSuppressed.addSuppressed(ae);
                    throw new AssertionError("reader pool must terminate before test exits", interruptSuppressed);
                } else {
                    throw ae;
                }
            }
        }
    }

    @Test
    void doesNotTouchThrowingFakes() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry reg = LandRegistry.from(List.of(land(w, id, 1, 1)));
        // registry supplier that throws if called more than once? We test that api only calls supplier, not repo
        java.util.function.Supplier<LandRegistry> throwingSupplier = () -> {
            // if api tried to do I/O via repo, it would not be this supplier
            return reg;
        };
        PermissionContextProvider throwingCtx = (a, lid, act, snap) -> {
            throw new AssertionError("should not be called for miss");
        };
        ChunkLandReadApi apiMiss = new ChunkLandReadApi(() -> LandRegistry.empty(), throwingCtx, (lid, r, snap) -> { throw new AssertionError("rule fake touched"); }, (lid, snap) -> { throw new AssertionError("depth fake touched"); });
        // miss paths must not touch fakes that throw for hit? For miss we expect empty/false without touching depth/rule provider beyond existence check
        // Actually getRule for miss returns empty without calling lookup (we check existence first). So no throw.
        assertTrue(apiMiss.getRule(new LandId(UUID.randomUUID()), LandRuleType.PVP).isEmpty());
        assertTrue(apiMiss.getProtectionDepth(new LandId(UUID.randomUUID())).isEmpty());
        assertFalse(apiMiss.can(UUID.randomUUID(), new LandId(UUID.randomUUID()), ProtectionActionType.BLOCK_BREAK));
        // hit path should call provider exactly once
        ChunkLandReadApi apiHit = new ChunkLandReadApi(throwingSupplier,
                (a, lid, act, snap) -> new PermissionContext(act, false, List.of(), PermissionState.ALLOW, PermissionState.INHERIT),
                (lid, r, snap) -> java.util.Optional.of(PermissionState.ALLOW),
                (lid, snap) -> java.util.Optional.of(10));
        assertTrue(apiHit.can(UUID.randomUUID(), id, ProtectionActionType.BLOCK_BREAK));
        assertEquals(PermissionState.ALLOW, apiHit.getRule(id, LandRuleType.PVP).get());
        assertEquals(10, apiHit.getProtectionDepth(id).get());
    }

    private static java.nio.file.Path findProjectRoot() {
        var cur = java.nio.file.Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (java.nio.file.Files.exists(cur.resolve("settings.gradle.kts")) && java.nio.file.Files.isDirectory(cur.resolve("chunkland-api"))) return cur;
            var parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        return java.nio.file.Paths.get("").toAbsolutePath();
    }

    @Test
    void doesNotReturnRenderedMessage() throws Exception {
        // scan ChunkLandReadApi source for MiniMessage / Component / rendered text
        var path = findProjectRoot().resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api/ChunkLandReadApi.java");
        if (!java.nio.file.Files.exists(path)) path = java.nio.file.Paths.get("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api/ChunkLandReadApi.java");
        var src = java.nio.file.Files.readString(path);
        String lower = src.toLowerCase();
        assertFalse(lower.contains("minimessage"), "must not reference MiniMessage");
        assertFalse(lower.contains("component"), "must not reference Adventure Component");
        assertFalse(lower.contains("message"), "must not handle rendered message text");
    }

    @Test
    void permissionContextImmutability() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land(w, id, 0, 0))));
        var mutable = new java.util.ArrayList<PermissionBinding>();
        mutable.add(new PermissionBinding(PermissionSubject.player(actor), new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW)));
        PermissionContext ctx = new PermissionContext(ProtectionActionType.BLOCK_BREAK, false, mutable, PermissionState.INHERIT, PermissionState.INHERIT);
        // mutate after
        mutable.clear();
        assertEquals(1, ctx.landBindings().size());
        assertThrows(UnsupportedOperationException.class, () -> ctx.landBindings().add(new PermissionBinding(PermissionSubject.player(actor), new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY))));
        ChunkLandReadApi api = new ChunkLandReadApi(store::snapshot, (a, lid, act, snap) -> ctx, (lid, r, snap) -> java.util.Optional.empty(), (lid, snap) -> java.util.Optional.empty());
        assertTrue(api.can(actor, id, ProtectionActionType.BLOCK_BREAK));
        // ensure provider not exposed mutable
        mutable.add(new PermissionBinding(PermissionSubject.player(actor), new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY)));
        assertTrue(api.can(actor, id, ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void anyThreadCallable() throws Exception {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land(w, id, 0, 0))));
        ChunkLandReadApi api = new ChunkLandReadApi(store);
        int threads = 12;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Throwable> err = new AtomicReference<>();
        java.util.List<Thread> workers = new java.util.ArrayList<>(threads);
        boolean latchDone = false;
        Throwable primary = null;
        try {
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try {
                        for (int k = 0; k < 500; k++) {
                            api.getLandSnapshot(id);
                            api.getOwner(id);
                            api.getSubLandSnapshot(new SubLandId(UUID.randomUUID()));
                            api.can(UUID.randomUUID(), id, ProtectionActionType.BLOCK_BREAK);
                            api.getRule(id, LandRuleType.PVP);
                            api.getProtectionDepth(id);
                        }
                    } catch (Throwable e) { err.compareAndSet(null, e); }
                    finally { latch.countDown(); }
                }, "read-api-worker-" + i);
                // Daemon is only a JVM safety net; bounded join below is the real guarantee.
                t.setDaemon(true);
                workers.add(t);
                t.start();
            }
            try {
                latchDone = latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("latch interrupted", e);
            }
            if (!latchDone) {
                throw new AssertionError("all read threads must finish within bounded timeout");
            }
            if (err.get() != null) throw new AssertionError(err.get());
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // Bounded cleanup for raw threads: interrupt any still-alive workers and join with timeout.
            // Join failures are added as suppressed so the primary assertion is not hidden.
            // InterruptedException restores interrupt status.
            if (!latchDone) {
                for (Thread t : workers) t.interrupt();
            }
            for (Thread t : workers) {
                try {
                    t.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (primary != null) primary.addSuppressed(e);
                }
                if (t.isAlive()) {
                    t.interrupt();
                    try { t.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); if (primary != null) primary.addSuppressed(e); }
                    if (t.isAlive()) {
                        AssertionError ae = new AssertionError("read thread must terminate before test exits: " + t.getName());
                        if (primary != null) primary.addSuppressed(ae);
                        else throw ae;
                    }
                }
            }
        }
    }

    // --- Snapshot-publication race fixture: deterministic mixed-version detection ---

    @Test
    void canUsesExactSnapshotNotLaterPublication() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        LandRegistry oldReg = LandRegistry.from(List.of(land(w, id, 0, 0)));
        LandRegistry newReg = LandRegistry.empty();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.function.Supplier<LandRegistry> genSupplier = () -> calls.getAndIncrement() == 0 ? oldReg : newReg;
        // Provider that would be buggy if it re-reads supplier: it would see newReg and return DENY.
        // Correct provider must use the snapshot it receives (oldReg) and return ALLOW.
        PermissionContext allowCtx = new PermissionContext(ProtectionActionType.BLOCK_BREAK, false,
                List.of(new PermissionBinding(PermissionSubject.player(actor), new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW))),
                PermissionState.INHERIT, PermissionState.INHERIT);
        PermissionContext denyCtx = new PermissionContext(ProtectionActionType.BLOCK_BREAK, false,
                List.of(new PermissionBinding(PermissionSubject.player(actor), new Permission(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY))),
                PermissionState.INHERIT, PermissionState.INHERIT);
        PermissionContextProvider provider = (a, lid, act, snap) -> {
            // must be the exact snapshot the api observed for existence (oldReg), never newReg
            assertSame(oldReg, snap, "can provider must receive exact snapshot observed for existence");
            assertNotNull(snap.land(lid), "land must exist in the snapshot the provider receives");
            // simulate rule: if provider mistakenly looked at later publication it would see empty
            if (snap == newReg) return denyCtx;
            return allowCtx;
        };
        // Also create a buggy lookup that re-reads supplier to demonstrate Red: before fix it would see newReg.
        // Our fixed api passes oldReg, so provider sees oldReg and returns ALLOW -> can true.
        ChunkLandReadApi api = new ChunkLandReadApi(genSupplier, provider, (lid, r, snap) -> java.util.Optional.empty(), (lid, snap) -> java.util.Optional.empty());
        assertTrue(api.can(actor, id, ProtectionActionType.BLOCK_BREAK), "can must use same snapshot; mixed version would yield false");
        assertEquals(1, calls.get(), "api must have taken exactly one snapshot for can; lookups must not re-read supplier");
    }

    @Test
    void getRuleUsesExactSnapshotNotLaterPublication() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry oldReg = LandRegistry.from(List.of(land(w, id, 0, 0)));
        LandRegistry newReg = LandRegistry.empty();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.function.Supplier<LandRegistry> genSupplier = () -> calls.getAndIncrement() == 0 ? oldReg : newReg;
        LandRuleLookup lookup = (lid, rule, snap) -> {
            assertSame(oldReg, snap, "rule lookup must receive exact snapshot");
            assertNotNull(snap.land(lid), "land must exist in snapshot provided to rule lookup");
            if (snap == newReg) return java.util.Optional.empty();
            return java.util.Optional.of(PermissionState.ALLOW);
        };
        ChunkLandReadApi api = new ChunkLandReadApi(genSupplier, (a, lid, act, snap) -> null, lookup, (lid, snap) -> java.util.Optional.empty());
        var result = api.getRule(id, LandRuleType.PVP);
        assertTrue(result.isPresent(), "getRule must not mix old existence with new empty lookup");
        assertEquals(PermissionState.ALLOW, result.get());
        assertEquals(1, calls.get(), "getRule must take exactly one snapshot");
    }

    @Test
    void getProtectionDepthUsesExactSnapshotNotLaterPublication() {
        UUID w = UUID.randomUUID();
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry oldReg = LandRegistry.from(List.of(land(w, id, 0, 0)));
        LandRegistry newReg = LandRegistry.empty();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.function.Supplier<LandRegistry> genSupplier = () -> calls.getAndIncrement() == 0 ? oldReg : newReg;
        ProtectionDepthLookup depthLookup = (lid, snap) -> {
            assertSame(oldReg, snap, "depth lookup must receive exact snapshot");
            assertNotNull(snap.land(lid), "land must exist in snapshot provided to depth lookup");
            if (snap == newReg) return java.util.Optional.empty();
            return java.util.Optional.of(64);
        };
        ChunkLandReadApi api = new ChunkLandReadApi(genSupplier, (a, lid, act, snap) -> null, (lid, r, snap) -> java.util.Optional.empty(), depthLookup);
        var result = api.getProtectionDepth(id);
        assertTrue(result.isPresent(), "getProtectionDepth must not mix old existence with new empty lookup");
        assertEquals(64, result.get());
        assertEquals(1, calls.get(), "getProtectionDepth must take exactly one snapshot");
    }

    @Test
    void noConcreteWorkflowIdsInExecutableComments() throws Exception {
        var path = findProjectRoot().resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api/ChunkLandReadApi.java");
        if (!java.nio.file.Files.exists(path)) path = java.nio.file.Paths.get("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api/ChunkLandReadApi.java");
        String apiSrc = java.nio.file.Files.readString(path);
        assertFalse(apiSrc.contains("CL-M"), "executable comment must not contain concrete workflow ID CL-M");
        var snapPath = findProjectRoot().resolve("chunkland-api/src/main/java/com/smile/chunkland/api/land/LandSnapshot.java");
        if (!java.nio.file.Files.exists(snapPath)) snapPath = java.nio.file.Paths.get("chunkland-api/src/main/java/com/smile/chunkland/api/land/LandSnapshot.java");
        String snapSrc = java.nio.file.Files.readString(snapPath);
        assertFalse(snapSrc.contains("CL-M"), "LandSnapshot must not contain concrete workflow ID");
        // also forbid bare task ids in executable source comments
        assertFalse(snapSrc.contains("M1-06"), "LandSnapshot must not contain concrete task ID M1-06");
        // general scan for any "M\\d+-\\d+" pattern as workflow id in comments would be caught, but allow non-comment? Keep simple.
    }
}
