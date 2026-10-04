package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Strict claim gate for conservative config mode: worlds absent from the
 * config are denied instead of following the enabled default.
 *
 * <p>The lenient {@link WorldClaimPolicy#fromConfig(Supplier, Function)}
 * behaviour is pinned by {@code WorldClaimPolicyTest} and stays untouched;
 * only the strict overload used by the conservative startup path denies the
 * unlisted.
 */
class WorldClaimPolicyConservativeTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private static Supplier<ChunkLandConfig> configs(String yaml) {
        ChunkLandConfig parsed = ConfigSchema.parseYamlText(yaml);
        return () -> parsed;
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
    void strictDeniesUnlistedWorld() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  other:\n    claim-enabled: false\n"),
                ignored -> Optional.of("world"),
                true);

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.claim_disabled", rejected.diagnosticKey());
    }

    @Test
    void strictStillAllowsListedEnabledWorld() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world:\n    claim-enabled: true\n"),
                ignored -> Optional.of("world"),
                true);

        assertNotNull(validator(policy).validate(playerClaim(WORLD)));
    }

    @Test
    void strictStillRejectsListedDisabledWorld() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  world_nether:\n    claim-enabled: false\n"),
                ignored -> Optional.of("world_nether"),
                true);

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(policy).validate(playerClaim(WORLD)));
        assertEquals("world.claim_disabled", rejected.diagnosticKey());
    }

    @Test
    void lenientOverloadKeepsEnabledDefaultForUnlistedWorld() {
        WorldClaimPolicy policy = WorldClaimPolicy.fromConfig(
                configs("worlds:\n  other:\n    claim-enabled: false\n"),
                ignored -> Optional.of("world"));

        assertNotNull(validator(policy).validate(playerClaim(WORLD)),
                "the normal path must keep allowing unlisted worlds");
    }
}
