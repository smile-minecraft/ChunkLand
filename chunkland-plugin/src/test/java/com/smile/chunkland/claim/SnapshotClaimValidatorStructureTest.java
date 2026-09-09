package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Saga-time structure revalidation for confirmations that act on an existing
 * land.
 *
 * <p>A targeted request carries the {@code baseStructureRevision} observed at
 * confirmation time; the validator compares it against the live structure
 * source. Requests without a target skip the check, and an unresolvable
 * target fails closed instead of passing by default.
 */
class SnapshotClaimValidatorStructureTest {

    private static SnapshotClaimValidator validator(ClaimValidator.StructureRevisionSource structures) {
        return new SnapshotClaimValidator(
                new LandRegistryStore(),
                ClaimValidator.RevisionSource.none(),
                chunk -> 64,
                owner -> 0L,
                structures);
    }

    private static ClaimRequest targeted(UUID actor, UUID world, LandId target, Long structureToken) {
        return new ClaimRequest(OwnerRef.player(actor), actor, world,
                Set.of(new ChunkKey(world, 9, 9)), "Annex", null, null, structureToken, target);
    }

    @Test
    void matchingStructurePasses() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        ValidatedClaim plan = validator(landId -> OptionalLong.of(7L))
                .validate(targeted(actor, world, target, 7L));

        assertNotNull(plan);
    }

    @Test
    void mismatchedStructureIsRejected() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(landId -> OptionalLong.of(8L))
                        .validate(targeted(actor, world, target, 7L)));

        assertEquals("structure.stale", rejected.diagnosticKey());
    }

    @Test
    void unresolvableStructureIsRejectedFailClosed() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> validator(ClaimValidator.StructureRevisionSource.none())
                        .validate(targeted(actor, world, target, 7L)));

        assertEquals("structure.unavailable", rejected.diagnosticKey());
    }

    @Test
    void untargetedRequestSkipsStructureCheck() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        ClaimRequest request = new ClaimRequest(OwnerRef.player(actor), actor, world,
                Set.of(new ChunkKey(world, 9, 9)), "Home");

        ValidatedClaim plan = validator(ClaimValidator.StructureRevisionSource.none()).validate(request);

        assertNotNull(plan);
    }

    @Test
    void missingTokenSkipsStructureCheck() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        ValidatedClaim plan = validator(landId -> OptionalLong.of(99L))
                .validate(targeted(actor, world, target, null));

        assertNotNull(plan);
    }

    @Test
    void failingSourceFailsValidation() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        ClaimValidator.StructureRevisionSource failing = landId -> {
            throw new RuntimeException("lookup boom");
        };

        assertThrows(IllegalStateException.class,
                () -> validator(failing).validate(targeted(actor, world, target, 7L)));
    }

    @Test
    void negativeStructureTokenIsRejectedAtConstruction() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new ClaimRequest(OwnerRef.player(actor), actor, world,
                Set.of(new ChunkKey(world, 9, 9)), "Annex", null, null, -1L, new LandId(UUID.randomUUID())));
    }
}
