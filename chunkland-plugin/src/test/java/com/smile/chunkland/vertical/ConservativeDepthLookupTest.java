package com.smile.chunkland.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unknown-world depth in conservative config mode.
 *
 * <p>The normal lookup keeps resolving unknown worlds to stored depths
 * ({@code PER_CHUNK_DEPTH}, pinned by the existing flow tests); the
 * conservative lookup resolves them to the world minimum instead, so a world
 * the damaged config never listed cannot shrink protection.
 */
class ConservativeDepthLookupTest {

    private static final int WORLD_MIN = -64;

    private record RegistryWithLand(LandRegistry registry, LandId landId) {
    }

    private static RegistryWithLand registryWithLand(UUID world) {
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 0, 0);
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        LandSnapshot land = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                owner, world, Set.of(chunk), List.of(), 0, 0, now, now);
        return new RegistryWithLand(
                LandRegistry.fromWithDepths(List.of(land), Map.of(chunk, 60)), landId);
    }

    @Test
    void conservativeLookupResolvesUnknownWorldToWorldMin() {
        UUID unknownWorld = UUID.randomUUID();
        RegistryWithLand fixture = registryWithLand(unknownWorld);

        SnapshotProtectionDepthLookup lookup = new SnapshotProtectionDepthLookup(
                worldId -> {
                    throw new IllegalStateException("no config entry");
                },
                worldId -> WORLD_MIN,
                VerticalMode.FULL_HEIGHT);

        assertEquals(WORLD_MIN,
                lookup.getProtectionDepth(fixture.landId(), fixture.registry()).orElseThrow());
    }

    @Test
    void normalLookupKeepsStoredDepthsForUnknownWorld() {
        UUID unknownWorld = UUID.randomUUID();
        RegistryWithLand fixture = registryWithLand(unknownWorld);

        SnapshotProtectionDepthLookup lookup = new SnapshotProtectionDepthLookup(
                worldId -> {
                    throw new IllegalStateException("no config entry");
                },
                worldId -> WORLD_MIN);

        assertEquals(60, lookup.getProtectionDepth(fixture.landId(), fixture.registry()).orElseThrow(),
                "the normal path must keep its existing stored-depth behaviour");
    }
}
