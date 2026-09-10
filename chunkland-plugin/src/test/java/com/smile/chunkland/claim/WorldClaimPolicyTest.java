package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.config.WorldSettings;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Per-world {@code claim-enabled} gate at the validator seam.
 *
 * <p>Player claims in a {@code claim-enabled:false} world are rejected before
 * any quota, reservation, ledger or economy side effect (the saga runs the
 * validator first). Unknown or unresolvable worlds fail closed, unlisted
 * worlds follow the enabled default, and server-owned land keeps its existing
 * bypass semantics.
 */
class WorldClaimPolicyTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private static Supplier<ChunkLandConfig> configs(String yaml) {
        ChunkLandConfig parsed = ConfigSchema.parseYamlText(yaml);
        return () -> parsed;
    }

    private static Function<UUID, Optional<String>> names(String name) {
        return ignored -> Optional.ofNullable(name);
    }

    private static SnapshotClaimValidator validator(WorldClaimPolicy policy) {
        return new SnapshotClaimValidator(
                new LandRegistryStore(),
                ClaimValidator.RevisionSource.none(),
                ClaimValidator.SessionGenerationSource.none(),
                chunk -> 64,
                owner -> 0L,
                ClaimValidator.StructureRevisionSource.none(),
                policy);
    }

    private static ClaimRequest playerClaim(UUID world) {
        return new ClaimRequest(OwnerRef.player(ACTOR), ACTOR, world,
                Set.of(new ChunkKey(world, 1, 2)), "Home");
    }

    @Test
    void disabledWorldRejectsPlayerClaim() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world_nether:\n    claim-enabled: false\n"),
                names("world_nether"));

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.claim_disabled", rejected.diagnosticKey());
    }

    @Test
    void enabledWorldPassesPolicy() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world:\n    claim-enabled: true\n"),
                names("world"));

        assertNotNull(validator(policy).validate(playerClaim(WORLD)));
    }

    @Test
    void unlistedWorldFollowsEnabledDefault() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  other:\n    claim-enabled: false\n"),
                names("world"));

        assertNotNull(validator(policy).validate(playerClaim(WORLD)));
    }

    @Test
    void verticalModeChangeDoesNotAffectClaimGate() {
        WorldClaimPolicy per = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world:\n    claim-enabled: true\n    vertical-mode: PER_CHUNK_DEPTH\n"),
                names("world"));
        WorldClaimPolicy full = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world:\n    claim-enabled: true\n    vertical-mode: FULL_HEIGHT\n"),
                names("world"));

        assertNotNull(validator(per).validate(playerClaim(WORLD)));
        assertNotNull(validator(full).validate(playerClaim(WORLD)));
    }

    @Test
    void unresolvableWorldFailsClosed() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world:\n    claim-enabled: true\n"),
                ignored -> Optional.empty());

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.unknown", rejected.diagnosticKey());
    }

    @Test
    void throwingResolverFailsClosed() {
        Function<UUID, Optional<String>> throwing = ignored -> {
            throw new RuntimeException("name service boom");
        };
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world:\n    claim-enabled: true\n"),
                throwing);

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.unknown", rejected.diagnosticKey());
    }

    @Test
    void nullResolverResultFailsClosed() {
        Function<UUID, Optional<String>> nulling = ignored -> null;
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world:\n    claim-enabled: true\n"),
                nulling);

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.unknown", rejected.diagnosticKey());
    }

    @Test
    void throwingConfigSourceFailsClosed() {
        Supplier<ChunkLandConfig> throwing = () -> {
            throw new RuntimeException("config read boom");
        };
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(throwing, names("world"));

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.unknown", rejected.diagnosticKey());
    }

    @Test
    void serverOwnedLandBypassesDisabledWorld() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world_nether:\n    claim-enabled: false\n"),
                names("world_nether"));
        ClaimRequest serverClaim = new ClaimRequest(OwnerRef.server(), ACTOR, WORLD,
                Set.of(new ChunkKey(WORLD, 1, 2)), "Spawn");

        assertNotNull(validator(policy).validate(serverClaim));
    }

    @Test
    void allowAllPreservesLegacyValidatorBehaviour() {
        assertNotNull(validator(WorldClaimPolicy.allowAll()).validate(playerClaim(WORLD)));
    }

    @Test
    void denyAllRejectsWithGivenKey() {
        WorldClaimPolicy policy = WorldClaimPolicy.denyAll("world.unknown");

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.unknown", rejected.diagnosticKey());
    }

    @Test
    void factoryRejectsNullInputs() {
        assertThrows(NullPointerException.class,
                () -> WorldClaimPolicy.fromConfig(null, names("world")));
        assertThrows(NullPointerException.class,
                () -> WorldClaimPolicy.fromConfig(configs("worlds: {}\n"), null));
    }

    @Test
    void policySeesBothWorldSettingsFields() {
        ChunkLandConfig parsed = ConfigSchema.parseYamlText(
                "worlds:\n  world:\n    claim-enabled: false\n    vertical-mode: FULL_HEIGHT\n");
        assertEquals(new WorldSettings(false, VerticalMode.FULL_HEIGHT),
                parsed.worlds().get("world"));
    }

    @Test
    void disabledWorldGateHoldsAcrossConfigReloadSnapshot() {
        ChunkLandConfig before = ConfigSchema.parseYamlText(
                "worlds:\n  world_nether:\n    claim-enabled: true\n");
        ChunkLandConfig after = ConfigSchema.parseYamlText(
                "worlds:\n  world_nether:\n    claim-enabled: false\n");
        java.util.concurrent.atomic.AtomicReference<ChunkLandConfig> live =
                new java.util.concurrent.atomic.AtomicReference<>(before);
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(live::get, names("world_nether"));

        assertNotNull(validator(policy).validate(playerClaim(WORLD)),
                "policy must read the live snapshot, not a stale capture");
        live.set(after);
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.claim_disabled", rejected.diagnosticKey());
    }
}
