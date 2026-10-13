package com.smile.chunkland.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.limit.LimitResult;
import com.smile.chunkland.api.limit.LimitSource;
import com.smile.chunkland.api.limit.LimitType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Quota limits follow the live snapshot: one resolver observes every
 * reload without being rebuilt, so a claim that starts after a reload is
 * always decided under the new generation.
 *
 * <p>This is the commit-time re-decision seam: the saga validates at
 * execution, not at prompt time, and the limits it reads come from the
 * current snapshot through this resolver.</p>
 */
class LimitResolverReloadTest {

    private static ChunkLandConfig configWithMaxLands(int maxLands) {
        return new ChunkLandConfig(Map.of(),
                new LimitSettings(maxLands, 256, 128, 16), 0L, Map.of());
    }

    @Test
    void liveSourceSeesNewGenerationWithoutRebuild() {
        AtomicReference<ChunkLandConfig> live =
                new AtomicReference<>(configWithMaxLands(5));
        LimitResolver resolver = new LimitResolver(live::get, Optional.empty());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());

        LimitResult before = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(5L, before.limit());
        assertEquals(LimitSource.CONFIG, before.source());

        live.set(configWithMaxLands(2));
        LimitResult after = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(2L, after.limit(),
                "the same resolver must decide under the reloaded generation");
    }

    @Test
    void tightenedLimitsRejectWhatPassedBefore() {
        AtomicReference<ChunkLandConfig> live =
                new AtomicReference<>(configWithMaxLands(5));
        OwnerQuotaService quotas =
                new OwnerQuotaService(new LimitResolver(live::get, Optional.empty()));
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        quotas.setCommitted(owner, 4);

        assertTrue(quotas.tryReserveLand(owner).isPresent(), "4/5 allows one more");

        live.set(configWithMaxLands(4));
        assertTrue(quotas.tryReserveLand(owner).isEmpty(),
                "after the reload the same owner is at the new limit");
    }

    @Test
    void nullSourceFailsClosedToZero() {
        LimitResolver resolver = new LimitResolver(() -> null, Optional.empty());
        LimitResult result = resolver.resolve(
                OwnerRef.player(UUID.randomUUID()), LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(0L, result.limit());
        assertEquals(LimitSource.CONFIG, result.source());
    }

    @Test
    void throwingSourceFailsClosedToZero() {
        LimitResolver resolver = new LimitResolver(() -> {
            throw new RuntimeException("snapshot unreadable");
        }, Optional.empty());
        LimitResult result = resolver.resolve(
                OwnerRef.player(UUID.randomUUID()), LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(0L, result.limit());
    }

    @Test
    void snapshotConstructorsKeepWorking() {
        LimitResolver resolver = new LimitResolver(configWithMaxLands(5));
        LimitResult result = resolver.resolve(
                OwnerRef.player(UUID.randomUUID()), LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(5L, result.limit());
        assertEquals(LimitSource.CONFIG, result.source());
    }
}
