package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import com.smile.chunkland.runtime.vertical.VerticalDepths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Production wiring for per-world {@code vertical-mode}.
 *
 * <p>The formal lookup is built from the live config snapshot plus explicit
 * startup-injected seams (UUID to world-name table, world-minimum table), so
 * the hot path never touches Bukkit or SQL: only a volatile snapshot read
 * and map lookups. {@code FULL_HEIGHT} changes effective reads only; stored
 * depths are never rewritten. Missing worlds or minima fall back explicitly
 * instead of throwing on the read path.
 */
class WorldVerticalProductionWiringTest {

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");

    private static final UUID WORLD_A = UUID.randomUUID();
    private static final UUID WORLD_B = UUID.randomUUID();
    private static final int MIN_A = -64;
    private static final int MIN_B = -32;

    private static final String MIXED_YAML =
            "worlds:\n"
                    + "  world_a:\n    claim-enabled: true\n    vertical-mode: PER_CHUNK_DEPTH\n"
                    + "  world_b:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n";

    private static Map<UUID, String> names() {
        return Map.of(WORLD_A, "world_a", WORLD_B, "world_b");
    }

    private static Map<UUID, Integer> mins() {
        return Map.of(WORLD_A, MIN_A, WORLD_B, MIN_B);
    }

    private static final class Fixture {
        final LandId landA = new LandId(UUID.randomUUID());
        final LandId landB = new LandId(UUID.randomUUID());
        final ChunkKey deepA = new ChunkKey(WORLD_A, 3, 4);
        final ChunkKey shallowA = new ChunkKey(WORLD_A, 4, 4);
        final ChunkKey chunkB = new ChunkKey(WORLD_B, 1, 1);
        final LandRegistry snapshot;

        Fixture() {
            LandSnapshot a = land(landA, "PerLand", WORLD_A, Set.of(deepA, shallowA));
            LandSnapshot b = land(landB, "FullLand", WORLD_B, Set.of(chunkB));
            snapshot = LandRegistry.fromWithDepths(List.of(a, b),
                    Map.of(deepA, 25, shallowA, -20, chunkB, 40));
            LandRegistryStore store = new LandRegistryStore();
            store.publish(snapshot);
        }

        private static LandSnapshot land(LandId id, String name, UUID world, Set<ChunkKey> chunks) {
            LandName normalized = LandName.of(name);
            return new LandSnapshot(id, normalized.displayName(), normalized.nameKey(),
                    OwnerRef.player(UUID.randomUUID()), world, chunks,
                    List.of(), 0L, 0L, TIME, TIME);
        }
    }

    private static SnapshotProtectionDepthLookup lookup(AtomicReference<ChunkLandConfig> configs,
            Map<UUID, String> nameTable, Map<UUID, Integer> minTable) {
        Supplier<ChunkLandConfig> source = configs::get;
        return ChunkLandPlugin.buildProtectionDepthLookup(source, nameTable, minTable);
    }

    @Test
    void productionLookupReadsPerWorldModes() {
        Fixture f = new Fixture();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup = lookup(configs, names(), mins());

        assertEquals(25, lookup.getEffectiveDepth(f.deepA, f.snapshot).orElseThrow());
        assertEquals(-20, lookup.getEffectiveDepth(f.shallowA, f.snapshot).orElseThrow());
        assertEquals(MIN_B, lookup.getEffectiveDepth(f.chunkB, f.snapshot).orElseThrow());
    }

    @Test
    void fullHeightNeverRewritesStored() {
        Fixture f = new Fixture();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup = lookup(configs, names(), mins());

        assertEquals(MIN_B, lookup.getEffectiveDepth(f.chunkB, f.snapshot).orElseThrow());
        assertEquals(Optional.of(40), lookup.getStoredDepth(f.chunkB, f.snapshot));
        assertEquals(Optional.of(40), f.snapshot.storedDepth(f.chunkB));
        assertEquals(Optional.of(MIN_B), lookup.getProtectionDepth(f.landB, f.snapshot));
    }

    @Test
    void perLandDepthIsMinimumEffectiveUnderPer() {
        Fixture f = new Fixture();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup = lookup(configs, names(), mins());

        assertEquals(Optional.of(-20), lookup.getProtectionDepth(f.landA, f.snapshot));
    }

    @Test
    void unknownWorldFallsBackToPerChunkDepth() {
        UUID unknownWorld = UUID.randomUUID();
        ChunkKey chunk = new ChunkKey(unknownWorld, 2, 2);
        LandId landId = new LandId(UUID.randomUUID());
        LandName name = LandName.of("FarLand");
        LandSnapshot land = new LandSnapshot(landId, name.displayName(), name.nameKey(),
                OwnerRef.player(UUID.randomUUID()), unknownWorld, Set.of(chunk),
                List.of(), 0L, 0L, TIME, TIME);
        LandRegistry snapshot = LandRegistry.fromWithDepths(List.of(land), Map.of(chunk, 10));

        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup = lookup(configs, names(), mins());

        assertEquals(Optional.of(10), lookup.getEffectiveDepth(chunk, snapshot));
        assertEquals(Optional.of(10), lookup.getProtectionDepth(landId, snapshot));
    }

    @Test
    void missingWorldMinFallsBackExplicitly() {
        Fixture f = new Fixture();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        // world_b is FULL_HEIGHT but has no minimum registered: the explicit
        // fallback applies instead of throwing on the read path.
        SnapshotProtectionDepthLookup lookup =
                lookup(configs, names(), Map.of(WORLD_A, MIN_A));

        assertEquals(ChunkLandPlugin.WORLD_MIN_HEIGHT_FALLBACK,
                lookup.getEffectiveDepth(f.chunkB, f.snapshot).orElseThrow());
    }

    @Test
    void throwingConfigSourceFallsBackToPerChunkDepth() {
        Fixture f = new Fixture();
        Supplier<ChunkLandConfig> throwing = () -> {
            throw new RuntimeException("config read boom");
        };
        SnapshotProtectionDepthLookup lookup =
                ChunkLandPlugin.buildProtectionDepthLookup(throwing, names(), mins());

        assertEquals(25, lookup.getEffectiveDepth(f.deepA, f.snapshot).orElseThrow());
        assertEquals(40, lookup.getEffectiveDepth(f.chunkB, f.snapshot).orElseThrow(),
                "without a readable snapshot every world stays on stored-depth semantics");
    }

    @Test
    void nullSnapshotsFailClosed() {
        Fixture f = new Fixture();
        SnapshotProtectionDepthLookup lookup =
                ChunkLandPlugin.buildProtectionDepthLookup(null, null, null);

        assertNotNull(lookup);
        assertEquals(25, lookup.getEffectiveDepth(f.deepA, f.snapshot).orElseThrow());
        assertEquals(40, lookup.getEffectiveDepth(f.chunkB, f.snapshot).orElseThrow());
    }

    @Test
    void modeSwitchAppliesWithoutStoredMigration() {
        Fixture f = new Fixture();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup = lookup(configs, names(), mins());

        assertEquals(25, lookup.getEffectiveDepth(f.deepA, f.snapshot).orElseThrow());
        configs.set(ConfigSchema.parseYamlText(
                "worlds:\n"
                        + "  world_a:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n"
                        + "  world_b:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n"));
        assertEquals(MIN_A, lookup.getEffectiveDepth(f.deepA, f.snapshot).orElseThrow());
        assertEquals(Optional.of(25), f.snapshot.storedDepth(f.deepA),
                "a mode switch must never migrate stored depths");
        configs.set(ConfigSchema.parseYamlText(MIXED_YAML));
        assertEquals(25, lookup.getEffectiveDepth(f.deepA, f.snapshot).orElseThrow());
    }

    @Test
    void wildernessReadsStayEmpty() {
        Fixture f = new Fixture();
        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup = lookup(configs, names(), mins());

        ChunkKey wild = new ChunkKey(WORLD_A, 99, 99);
        assertTrue(lookup.getEffectiveDepth(wild, f.snapshot).isEmpty());
        assertTrue(lookup.getStoredDepth(wild, f.snapshot).isEmpty());
        assertTrue(lookup.getProtectionDepth(new LandId(UUID.randomUUID()), f.snapshot).isEmpty());
    }

    @Test
    void legacyFallbackAppliesWithoutStoredRow() {
        ChunkKey chunk = new ChunkKey(WORLD_A, 5, 5);
        LandId landId = new LandId(UUID.randomUUID());
        LandName name = LandName.of("LegacyLand");
        LandSnapshot land = new LandSnapshot(landId, name.displayName(), name.nameKey(),
                OwnerRef.player(UUID.randomUUID()), WORLD_A, Set.of(chunk),
                List.of(), 0L, 0L, TIME, TIME);
        LandRegistry snapshot = LandRegistry.from(List.of(land));

        AtomicReference<ChunkLandConfig> configs =
                new AtomicReference<>(ConfigSchema.parseYamlText(MIXED_YAML));
        SnapshotProtectionDepthLookup lookup = lookup(configs, names(), mins());

        assertEquals(VerticalDepths.LEGACY_STORED_FALLBACK_Y,
                lookup.getEffectiveDepth(chunk, snapshot).orElseThrow());
    }
}
