package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.ConservativeConfigFactory;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Corrupt-config decisions at the adjudication layer.
 *
 * <p>The snapshot-level pins (full-height mode, non-ALLOW entry, DENY rules)
 * only matter if they survive to the actual decisions. The forward test runs
 * the production wirings — the permission provider plus
 * {@link PermissionResolver}, the rule service from the same config view, and
 * the conservative depth lookup — over the conservative fallback snapshot and
 * asserts the strict outcomes there.
 *
 * <p>The reverse test runs the same chain over the legacy loose fallback
 * content ({@code PER_CHUNK_DEPTH} plus {@code ENTRY: ALLOW}, exactly what the
 * old startup fallback served from the shipped resource) and asserts the lax
 * outcomes. It documents the pre-fix direction: the forward assertions fail
 * against that content (the captured failure is kept in the task record),
 * which is why the corrupt path must never serve it.
 */
class ConservativeAdjudicationTest {

    private static final int WORLD_MIN = -64;
    private static final int STORED_DEPTH = 60;

    /** What the old startup fallback served: the shipped loose resource. */
    private static final String LOOSE_FALLBACK_YAML = ""
            + "worlds:\n"
            + "  world:\n"
            + "    claim-enabled: true\n"
            + "    vertical-mode: PER_CHUNK_DEPTH\n"
            + "subject-defaults:\n"
            + "  global:\n"
            + "    ENTRY: ALLOW\n";

    private static final class Fixture {
        final UUID worldId = UUID.randomUUID();
        final LandId landId = new LandId(UUID.randomUUID());
        final UUID stranger = UUID.randomUUID();
        final LandRegistry registry;
        final AtomicReference<ChunkLandConfig> configs;
        final PermissionDefaultsCache defaults;

        Fixture(ChunkLandConfig snapshot) {
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            ChunkKey chunk = new ChunkKey(worldId, 0, 0);
            Instant now = Instant.parse("2026-03-01T00:00:00Z");
            LandSnapshot land = new LandSnapshot(landId, "Home", LandName.normalize("Home"),
                    owner, worldId, Set.of(chunk), List.of(), 0, 0, now, now);
            registry = LandRegistry.fromWithDepths(List.of(land), Map.of(chunk, STORED_DEPTH));
            configs = new AtomicReference<>(snapshot);
            ConfigLoader loader = new ConfigLoader() {
                @Override
                public ChunkLandConfig load() {
                    return snapshot;
                }

                @Override
                public String describe() {
                    return "test-fixed";
                }
            };
            ConfigService service = new ConfigService(loader, snapshot);
            defaults = ChunkLandPlugin.buildPermissionDefaults(
                    service, Map.of("world", worldId), warning -> {
                    });
            // Loaded-but-empty durable authorisations: no bindings, defaults or
            // bans. An unloaded snapshot would deny at the land layer before
            // the world/global config defaults are even consulted (bans are
            // unverifiable while unloaded), which would hide the config layers
            // this test isolates. Production attaches the real durable source
            // after bootstrap; empty is its no-rows state.
            defaults.attachLandAuthorisation(LandAuthorisationSnapshot::empty);
        }

        SnapshotProtectionDepthLookup lookup(boolean conservative) {
            return ChunkLandPlugin.buildProtectionDepthLookup(
                    configs::get, Map.of(worldId, "world"), Map.of(worldId, WORLD_MIN),
                    conservative);
        }
    }

    @Test
    void corruptConservativeAdjudicatesDenyAtDecisionLayer() {
        Fixture f = new Fixture(ConservativeConfigFactory.forWorlds(List.of("world")));

        var entryContext = f.defaults.provider().provide(
                f.stranger, f.landId, ProtectionActionType.ENTRY, f.registry);
        assertEquals(PermissionState.DENY, PermissionResolver.resolve(entryContext).outcome(),
                "a stranger's ENTRY must stay denied at the decision layer after a corrupt start");

        assertEquals(PermissionState.DENY, f.defaults.view().rules()
                        .getRule(f.landId, LandRuleType.PASSIVE_MOB_SPAWN, f.registry).orElseThrow(),
                "the built-in ALLOW rule must resolve DENY at the decision layer in conservative mode");

        assertEquals(WORLD_MIN, f.lookup(true)
                        .getProtectionDepth(f.landId, f.registry).orElseThrow(),
                "effective depth must be the world minimum at the decision layer in conservative mode");
    }

    @Test
    void legacyLooseFallbackAdjudicatesAllowAtDecisionLayer() {
        Fixture f = new Fixture(ConfigSchema.parseYamlText(LOOSE_FALLBACK_YAML));

        var entryContext = f.defaults.provider().provide(
                f.stranger, f.landId, ProtectionActionType.ENTRY, f.registry);
        assertEquals(PermissionState.ALLOW, PermissionResolver.resolve(entryContext).outcome(),
                "documents the pre-fix fallback: the shipped ENTRY ALLOW reached the decision layer");

        assertEquals(PermissionState.ALLOW, f.defaults.view().rules()
                        .getRule(f.landId, LandRuleType.PASSIVE_MOB_SPAWN, f.registry).orElseThrow(),
                "documents the pre-fix fallback: with no DENY layer the built-in ALLOW survived");

        assertEquals(STORED_DEPTH, f.lookup(false)
                        .getProtectionDepth(f.landId, f.registry).orElseThrow(),
                "documents the pre-fix fallback: stored depth instead of the world minimum");
    }
}
