package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.limit.ExternalLimitProvider;
import com.smile.chunkland.api.limit.LimitSource;
import com.smile.chunkland.api.limit.LimitType;
import com.smile.chunkland.command.InspectCommandHandler;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.limit.LuckPermsLimitProvider;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Red contract for inspect limit provenance: {@code /land inspect} reports
 * each limit value together with its source ({@code CONFIG}/{@code PROVIDER}).
 * Legacy keys stay backward-compatible; the old single-arg seam keeps working.
 */
class InspectLimitSourceTest {

    private static ChunkLandConfig defaults() {
        return new ChunkLandConfig(Map.of(), LimitSettings.defaults(), 0L, Map.of());
    }

    private static OwnerRef player() {
        return OwnerRef.player(UUID.randomUUID());
    }

    @Test
    void inspectDescribeShowsValueAndConfigSource() {
        OwnerRef owner = player();
        Supplier<ChunkLandConfig> configs = InspectLimitSourceTest::defaults;
        InspectCommandHandler.Limits limits =
                ChunkLandPlugin.buildInspectLimits(configs, Optional::empty);
        Map<String, Object> described = limits.describe(owner);
        assertEquals((long) LimitType.MAX_CHUNKS_PER_LAND.defaultValue(),
                described.get("limitMaxChunksPerLand"));
        assertEquals((long) LimitType.MAX_SUBLANDS_PER_LAND.defaultValue(),
                described.get("limitMaxSublandsPerLand"));
        assertEquals(LimitSource.CONFIG.name(),
                described.get("limitMaxChunksPerLandSource"));
        assertEquals(LimitSource.CONFIG.name(),
                described.get("limitMaxSublandsPerLandSource"));
    }

    @Test
    void inspectDescribeShowsProviderSource() {
        OwnerRef owner = player();
        Supplier<ChunkLandConfig> configs = InspectLimitSourceTest::defaults;
        ExternalLimitProvider provider =
                new LuckPermsLimitProvider((uuid, key) -> Optional.of("64"));
        InspectCommandHandler.Limits limits =
                ChunkLandPlugin.buildInspectLimits(configs, () -> Optional.of(provider));
        Map<String, Object> described = limits.describe(owner);
        assertEquals(64L, described.get("limitMaxChunksPerLand"));
        assertEquals(64L, described.get("limitMaxSublandsPerLand"));
        assertEquals(LimitSource.PROVIDER.name(),
                described.get("limitMaxChunksPerLandSource"));
        assertEquals(LimitSource.PROVIDER.name(),
                described.get("limitMaxSublandsPerLandSource"));
    }

    @Test
    void inspectDescribeWithFailingProviderStaysReadable() {
        OwnerRef owner = player();
        Supplier<ChunkLandConfig> configs = InspectLimitSourceTest::defaults;
        ExternalLimitProvider provider = new LuckPermsLimitProvider((uuid, key) -> {
            throw new IllegalStateException("luckperms down");
        });
        InspectCommandHandler.Limits limits =
                ChunkLandPlugin.buildInspectLimits(configs, () -> Optional.of(provider));
        Map<String, Object> described = limits.describe(owner);
        assertEquals(LimitSource.CONFIG.name(),
                described.get("limitMaxChunksPerLandSource"));
        assertEquals((long) LimitType.MAX_CHUNKS_PER_LAND.defaultValue(),
                described.get("limitMaxChunksPerLand"));
    }

    @Test
    void legacyBuildInspectLimitsSeamKeepsWorking() {
        OwnerRef owner = player();
        Supplier<ChunkLandConfig> configs = InspectLimitSourceTest::defaults;
        InspectCommandHandler.Limits limits = ChunkLandPlugin.buildInspectLimits(configs);
        Map<String, Object> described = limits.describe(owner);
        assertEquals((long) LimitType.MAX_CHUNKS_PER_LAND.defaultValue(),
                described.get("limitMaxChunksPerLand"));
        assertEquals((long) LimitType.MAX_SUBLANDS_PER_LAND.defaultValue(),
                described.get("limitMaxSublandsPerLand"));
    }
}
