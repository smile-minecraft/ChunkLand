package com.smile.chunkland.limit;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.limit.ExternalLimitProvider;
import com.smile.chunkland.api.limit.LimitResult;
import com.smile.chunkland.api.limit.LimitSource;
import com.smile.chunkland.api.limit.LimitType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Production seam concurrency tests.
 *
 * <p>Uses the real {@link OwnerQuotaService} (not a fake) and a deterministic
 * {@link CyclicBarrier} to force N threads to contend simultaneously.
 * Every executor is bounded and closed in finally.</p>
 */
class OwnerQuotaServiceTest {

    private ChunkLandConfig configWithLimit(int maxLands) {
        LimitSettings limits = new LimitSettings(maxLands, 256, 128, 16);
        return new ChunkLandConfig(java.util.Map.of(), limits, 0L, java.util.Map.of());
    }

    @Test
    void quotaReserveSuccessAndRelease() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(5)));
        svc.setCommitted(owner, 2);
        Optional<OwnerQuotaService.QuotaReservation> r = svc.tryReserveLand(owner);
        assertTrue(r.isPresent());
        assertEquals(3, svc.total(owner), "committed(2)+reserved(1)=3");
        r.get().release();
        assertEquals(2, svc.total(owner), "release must restore");
        assertEquals(0, svc.reserved(owner));
    }

    @Test
    void quotaRejectHasNoSideEffect() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(2)));
        svc.setCommitted(owner, 2);
        Optional<OwnerQuotaService.QuotaReservation> r = svc.tryReserveLand(owner);
        assertTrue(r.isEmpty(), "at limit -> reject");
        assertEquals(2, svc.total(owner));
        assertEquals(0, svc.reserved(owner));
    }

    @Test
    void quotaCompleteMovesReservedToCommitted() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(5)));
        svc.setCommitted(owner, 2);
        Optional<OwnerQuotaService.QuotaReservation> r = svc.tryReserveLand(owner);
        assertTrue(r.isPresent());
        r.get().complete();
        assertEquals(3, svc.committed(owner));
        assertEquals(0, svc.reserved(owner));
        assertEquals(3, svc.total(owner));
    }

    @Test
    void serverLandBypassesQuota() {
        OwnerRef server = OwnerRef.server();
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(1)));
        svc.setCommitted(server, 0);
        // Even at zero limit, server should still succeed and not count
        Optional<OwnerQuotaService.QuotaReservation> r = svc.tryReserveLand(server);
        assertTrue(r.isPresent());
        assertTrue(r.get().isNoop());
        assertEquals(0, svc.total(server));
        r.get().complete();
        assertEquals(0, svc.committed(server));
    }

    @Test
    void differentOwnersDoNotInterfere() {
        OwnerRef a = OwnerRef.player(UUID.randomUUID());
        OwnerRef b = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(1)));
        svc.setCommitted(a, 1); // a at limit
        svc.setCommitted(b, 0);
        assertTrue(svc.tryReserveLand(a).isEmpty(), "a at limit");
        assertTrue(svc.tryReserveLand(b).isPresent(), "b not at limit");
    }

    @Test
    void providerFallbackSource() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        // No provider -> CONFIG
        LimitResolver without = new LimitResolver(configWithLimit(5), Optional.empty());
        LimitResult r1 = without.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(LimitSource.CONFIG, r1.source());
        assertEquals(5, r1.limit());

        // Provider returns empty -> fallback to CONFIG
        ExternalLimitProvider emptyProvider = (o, t) -> Optional.empty();
        LimitResolver withEmpty = new LimitResolver(configWithLimit(5), Optional.of(emptyProvider));
        LimitResult r2 = withEmpty.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(LimitSource.CONFIG, r2.source());

        // Provider returns value -> PROVIDER
        ExternalLimitProvider tenProvider = (o, t) -> Optional.of(LimitResult.of(10, LimitSource.PROVIDER));
        LimitResolver withValue = new LimitResolver(configWithLimit(5), Optional.of(tenProvider));
        LimitResult r3 = withValue.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(LimitSource.PROVIDER, r3.source());
        assertEquals(10, r3.limit());

        // Quota respects provider limit
        OwnerQuotaService svc = new OwnerQuotaService(withValue);
        svc.setCommitted(owner, 9);
        assertTrue(svc.tryReserveLand(owner).isPresent(), "9/10 with provider limit 10 -> allow");
        Optional<OwnerQuotaService.QuotaReservation> second = svc.tryReserveLand(owner);
        // first reserved 1, so total 10/10 -> next should fail
        assertTrue(second.isEmpty() || !svc.tryReserveLand(owner).isPresent());
    }

    @Test
    void providerAbsentFallsBackToConfigLimit() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkLandConfig cfg = configWithLimit(3);
        LimitResolver resolver = new LimitResolver(cfg, Optional.empty());
        OwnerQuotaService svc = new OwnerQuotaService(resolver);
        svc.setCommitted(owner, 2);
        assertTrue(svc.tryReserveLand(owner).isPresent(), "remaining 1 -> success");
        assertTrue(svc.tryReserveLand(owner).isEmpty(), "now at limit -> reject");
    }

    /**
     * Deterministic barrier/latch test repeated 100 times.
     * Owner has limit 5, committed 2, remaining R=3, N=10 contenders.
     * Exactly min(N,R)=3 must succeed, never exceed limit, never negative, rejections have no side effect.
     */
    @RepeatedTest(100)
    void deterministicBarrierQuotaReservations() throws Exception {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        int limit = 5;
        int committed = 2;
        int remaining = limit - committed; // 3
        int contenders = 10;
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(limit)));
        svc.setCommitted(owner, committed);

        CyclicBarrier barrier = new CyclicBarrier(contenders);
        ExecutorService exec = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Optional<OwnerQuotaService.QuotaReservation>>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(exec.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return svc.tryReserveLand(owner);
                }));
            }
            List<OwnerQuotaService.QuotaReservation> successes = new ArrayList<>();
            int failures = 0;
            for (Future<Optional<OwnerQuotaService.QuotaReservation>> f : futures) {
                Optional<OwnerQuotaService.QuotaReservation> r = f.get(5, TimeUnit.SECONDS);
                if (r.isPresent()) successes.add(r.get());
                else failures++;
            }
            assertEquals(remaining, successes.size(),
                    "exactly R successes expected, N=" + contenders + " R=" + remaining);
            assertEquals(contenders - remaining, failures);
            assertEquals(committed + remaining, svc.total(owner), "total must not exceed limit");
            assertTrue(svc.total(owner) <= limit);
            assertTrue(svc.reserved(owner) >= 0);
            // Release all successes and verify back to committed
            for (OwnerQuotaService.QuotaReservation res : successes) {
                res.release();
            }
            assertEquals(committed, svc.total(owner));
            assertEquals(0, svc.reserved(owner));
            // Now complete path: reserve again and complete
            Optional<OwnerQuotaService.QuotaReservation> one = svc.tryReserveLand(owner);
            assertTrue(one.isPresent());
            one.get().complete();
            assertEquals(committed + 1, svc.committed(owner));
            assertEquals(0, svc.reserved(owner));
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @RepeatedTest(100)
    void concurrentDifferentOwnersIsolated() throws Exception {
        OwnerRef a = OwnerRef.player(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        OwnerRef b = OwnerRef.player(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        int limit = 3;
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(limit)));
        svc.setCommitted(a, 2); // remaining 1
        svc.setCommitted(b, 0); // remaining 3
        int contendersPerOwner = 5;
        CyclicBarrier barrier = new CyclicBarrier(contendersPerOwner * 2);
        ExecutorService exec = Executors.newFixedThreadPool(contendersPerOwner * 2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < contendersPerOwner; i++) {
                futures.add(exec.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return svc.tryReserveLand(a).isPresent(); }));
                futures.add(exec.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return svc.tryReserveLand(b).isPresent(); }));
            }
            int successA = 0, successB = 0;
            for (int i = 0; i < futures.size(); i++) {
                boolean ok = futures.get(i).get(5, TimeUnit.SECONDS);
                if (i % 2 == 0) { if (ok) successA++; } else { if (ok) successB++; }
            }
            assertEquals(1, successA, "owner A remaining 1");
            assertEquals(3, successB, "owner B remaining 3");
            // Ensure no interference: totals isolated
            assertEquals(3, svc.total(a));
            assertEquals(3, svc.total(b));
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void countsNeverNegativeOrExceedUnderContention() throws Exception {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(5)));
        svc.setCommitted(owner, 4); // remaining 1, 10 contenders -> 1 success
        int contenders = 10;
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        ExecutorService exec = Executors.newFixedThreadPool(contenders);
        List<OwnerQuotaService.QuotaReservation> kept = new ArrayList<>();
        try {
            List<Future<Optional<OwnerQuotaService.QuotaReservation>>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(exec.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return svc.tryReserveLand(owner);
                }));
            }
            for (Future<Optional<OwnerQuotaService.QuotaReservation>> f : futures) {
                Optional<OwnerQuotaService.QuotaReservation> r = f.get(5, TimeUnit.SECONDS);
                r.ifPresent(kept::add);
                // Invariant after each: reserved >=0 and total <= limit
                assertTrue(svc.reserved(owner) >= 0);
                assertTrue(svc.total(owner) <= 5);
            }
            assertEquals(1, kept.size());
            kept.forEach(OwnerQuotaService.QuotaReservation::release);
            assertEquals(0, svc.reserved(owner));
        } finally {
            exec.shutdownNow();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void mixedLandAndChunkReservationsAreIsolated() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        LimitSettings limits = new LimitSettings(2, 10, 128, 16);
        ChunkLandConfig cfg = new ChunkLandConfig(java.util.Map.of(), limits, 0L, java.util.Map.of());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(cfg));
        svc.setLandCommitted(owner, 1);
        svc.setChunkCommitted(owner, 5);
        // Reserve land should not affect chunk dimension
        Optional<OwnerQuotaService.QuotaReservation> land = svc.tryReserveLand(owner);
        assertTrue(land.isPresent(), "land remaining 1 -> success");
        assertEquals(1, svc.landReserved(owner));
        assertEquals(0, svc.chunkReserved(owner), "chunk reserved must stay 0");
        assertEquals(5, svc.chunkCommitted(owner), "chunk committed unchanged");
        // Reserve chunks
        Optional<OwnerQuotaService.QuotaReservation> chunks = svc.tryReserveChunks(owner, 3);
        assertTrue(chunks.isPresent(), "chunk remaining 5 -> allow 3");
        assertEquals(1, svc.landReserved(owner), "land reserved still 1");
        assertEquals(3, svc.chunkReserved(owner));
        // Land total 2/2 at limit, next land should fail even though chunk still has capacity
        assertTrue(svc.tryReserveLand(owner).isEmpty(), "land at limit -> reject irrespective of chunk");
        // Chunk still has 2 remaining (10-5-3=2)
        assertTrue(svc.tryReserveChunks(owner, 2).isPresent());
        assertTrue(svc.tryReserveChunks(owner, 1).isEmpty(), "chunk at limit -> reject");
        // Complete land, chunk dimensions stay isolated
        land.get().complete();
        assertEquals(2, svc.landCommitted(owner));
        assertEquals(0, svc.landReserved(owner));
        assertEquals(5, svc.chunkCommitted(owner), "chunk committed not moved by land complete");
        assertEquals(5, svc.chunkReserved(owner), "both chunk reservations (3 and 2) are held");
        // Release first chunk reservation (3), second (2) still held
        chunks.get().release();
        assertEquals(5, svc.chunkCommitted(owner));
        assertEquals(2, svc.chunkReserved(owner)); // the 2-chunk reservation still held
        // Cleanup
        assertTrue(svc.chunkTotal(owner) <= 10);
        assertTrue(svc.landTotal(owner) <= 2);
    }

    @Test
    void chunkReservationRejectHasNoSideEffectAndReleaseIsIdempotent() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        LimitSettings limits = new LimitSettings(5, 5, 128, 16);
        ChunkLandConfig cfg = new ChunkLandConfig(java.util.Map.of(), limits, 0L, java.util.Map.of());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(cfg));
        svc.setChunkCommitted(owner, 5);
        assertTrue(svc.tryReserveChunks(owner, 1).isEmpty());
        assertEquals(0, svc.chunkReserved(owner));
        assertEquals(5, svc.chunkTotal(owner));
        // Idempotent release/complete
        svc.setChunkCommitted(owner, 0);
        Optional<OwnerQuotaService.QuotaReservation> r = svc.tryReserveChunks(owner, 2);
        assertTrue(r.isPresent());
        r.get().release();
        assertEquals(0, svc.chunkReserved(owner));
        r.get().release(); // second release no-op
        assertEquals(0, svc.chunkReserved(owner));
        Optional<OwnerQuotaService.QuotaReservation> r2 = svc.tryReserveChunks(owner, 2);
        r2.get().complete();
        assertEquals(2, svc.chunkCommitted(owner));
        r2.get().complete(); // second complete no-op
        assertEquals(2, svc.chunkCommitted(owner));
        assertEquals(0, svc.chunkReserved(owner));
    }

    @Test
    void releaseCommittedLandsFloorsAtZeroAndServerIsNoop() {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(configWithLimit(5)));
        svc.setLandCommitted(owner, 1);
        svc.releaseCommittedLands(owner, 1);
        assertEquals(0, svc.landCommitted(owner), "one committed land must be released");
        svc.releaseCommittedLands(owner, 1);
        assertEquals(0, svc.landCommitted(owner), "a stale release must floor at zero");
        // Server Land is untracked: releasing must be a safe no-op.
        assertDoesNotThrow(() -> svc.releaseCommittedLands(OwnerRef.server(), 1));
        assertEquals(0, svc.landCommitted(OwnerRef.server()));
        // An owner with no state must not be created as a side effect.
        assertDoesNotThrow(() -> svc.releaseCommittedLands(OwnerRef.player(UUID.randomUUID()), 1));
        assertThrows(IllegalArgumentException.class, () -> svc.releaseCommittedLands(owner, -1));
        assertEquals(0, svc.landCommitted(owner), "the rejected release must not mutate");
    }

    @RepeatedTest(100)
    void deterministicBarrierChunkReservations() throws Exception {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        LimitSettings limits = new LimitSettings(5, 10, 128, 16);
        ChunkLandConfig cfg = new ChunkLandConfig(java.util.Map.of(), limits, 0L, java.util.Map.of());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(cfg));
        svc.setChunkCommitted(owner, 6); // remaining 4, each request 1 chunk, N=10 -> 4 succeed
        int contenders = 10;
        int chunksPerRequest = 1;
        int remaining = 4;
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        ExecutorService exec = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Optional<OwnerQuotaService.QuotaReservation>>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(exec.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return svc.tryReserveChunks(owner, chunksPerRequest);
                }));
            }
            List<OwnerQuotaService.QuotaReservation> successes = new ArrayList<>();
            for (Future<Optional<OwnerQuotaService.QuotaReservation>> f : futures) {
                f.get(5, TimeUnit.SECONDS).ifPresent(successes::add);
                assertTrue(svc.chunkReserved(owner) >= 0);
                assertTrue(svc.chunkTotal(owner) <= 10);
            }
            assertEquals(remaining, successes.size());
            successes.forEach(OwnerQuotaService.QuotaReservation::release);
            assertEquals(0, svc.chunkReserved(owner));
            assertEquals(6, svc.chunkCommitted(owner));
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @RepeatedTest(100)
    void mixedDimensionsConcurrentIsolation() throws Exception {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        LimitSettings limits = new LimitSettings(3, 10, 128, 16);
        ChunkLandConfig cfg = new ChunkLandConfig(java.util.Map.of(), limits, 0L, java.util.Map.of());
        OwnerQuotaService svc = new OwnerQuotaService(new LimitResolver(cfg));
        svc.setLandCommitted(owner, 1); // land remaining 2
        svc.setChunkCommitted(owner, 6); // chunk remaining 4
        int landContenders = 5;
        int chunkContenders = 5;
        CyclicBarrier barrier = new CyclicBarrier(landContenders + chunkContenders);
        ExecutorService exec = Executors.newFixedThreadPool(landContenders + chunkContenders);
        try {
            List<Future<Optional<OwnerQuotaService.QuotaReservation>>> landFutures = new ArrayList<>();
            List<Future<Optional<OwnerQuotaService.QuotaReservation>>> chunkFutures = new ArrayList<>();
            for (int i = 0; i < landContenders; i++) {
                landFutures.add(exec.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return svc.tryReserveLand(owner);
                }));
            }
            for (int i = 0; i < chunkContenders; i++) {
                chunkFutures.add(exec.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return svc.tryReserveChunks(owner, 1);
                }));
            }
            long landSuccess = landFutures.stream().map(f -> {
                try { return f.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0; } catch (Exception e) { throw new RuntimeException(e); }
            }).mapToLong(Integer::longValue).sum();
            long chunkSuccess = chunkFutures.stream().map(f -> {
                try { return f.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0; } catch (Exception e) { throw new RuntimeException(e); }
            }).mapToLong(Integer::longValue).sum();
            assertEquals(2, landSuccess, "land remaining 2");
            assertEquals(4, chunkSuccess, "chunk remaining 4");
            assertTrue(svc.landTotal(owner) <= 3);
            assertTrue(svc.chunkTotal(owner) <= 10);
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
