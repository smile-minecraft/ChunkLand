package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Step-one validator contract for whole-land delete: the structure token is
 * required and compared against the live source, and the target must resolve
 * with matching owner and world.
 */
class SnapshotDeleteValidatorTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class Fixture {
        final UUID world = UUID.randomUUID();
        final UUID actor = UUID.randomUUID();
        final LandId land = new LandId(UUID.randomUUID());
        final OwnerRef owner = OwnerRef.player(actor);
        final LandRegistryStore store = new LandRegistryStore();
        final Map<LandId, Long> liveRevisions = new ConcurrentHashMap<>();

        Fixture() {
            String displayName = "Home " + land.value().toString().substring(0, 8);
            LandSnapshot snapshot = new LandSnapshot(land, displayName,
                    LandName.normalize(displayName), owner, world,
                    Set.of(new ChunkKey(world, 0, 0), new ChunkKey(world, 1, 0)), List.of(),
                    5, 0, NOW, NOW);
            store.publish(LandRegistry.from(List.of(snapshot)));
            liveRevisions.put(land, 5L);
        }

        SnapshotDeleteValidator validator() {
            return new SnapshotDeleteValidator(store,
                    target -> {
                        Long revision = liveRevisions.get(target);
                        return revision == null ? OptionalLong.empty() : OptionalLong.of(revision);
                    });
        }

        DeleteRequest request(OwnerRef requestOwner, UUID requestActor, UUID requestWorld,
                LandId target, Long revision) {
            return new DeleteRequest(requestOwner, requestActor, requestWorld, target, revision);
        }
    }

    @Test
    void validRequestReturnsFullChunkSet() {
        Fixture f = new Fixture();
        ValidatedDelete validated = f.validator()
                .validate(f.request(f.owner, f.actor, f.world, f.land, 5L));
        assertEquals(f.land, validated.targetLandId());
        assertEquals(f.owner, validated.owner());
        assertEquals(5L, validated.expectedStructureRevision());
        assertEquals(
                Set.of(new ChunkKey(f.world, 0, 0), new ChunkKey(f.world, 1, 0)),
                Set.copyOf(validated.chunks()));
    }

    @Test
    void missingTokenIsRejected() {
        Fixture f = new Fixture();
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> f.validator().validate(f.request(f.owner, f.actor, f.world, f.land, null)));
        assertEquals("delete.stale", rejected.diagnosticKey());
    }

    @Test
    void staleTokenIsRejected() {
        Fixture f = new Fixture();
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> f.validator().validate(f.request(f.owner, f.actor, f.world, f.land, 4L)));
        assertEquals("structure.stale", rejected.diagnosticKey());
    }

    @Test
    void unresolvableRevisionIsUnavailable() {
        Fixture f = new Fixture();
        f.liveRevisions.remove(f.land);
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class,
                () -> f.validator().validate(f.request(f.owner, f.actor, f.world, f.land, 5L)));
        assertEquals("structure.unavailable", rejected.diagnosticKey());
    }

    @Test
    void unknownLandIsRejected() {
        Fixture f = new Fixture();
        LandId ghost = new LandId(UUID.randomUUID());
        f.liveRevisions.put(ghost, 0L);
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class, () -> f.validator()
                .validate(f.request(f.owner, f.actor, f.world, ghost, 0L)));
        assertEquals("delete.unknown_land", rejected.diagnosticKey());
    }

    @Test
    void ownerMismatchIsRejected() {
        Fixture f = new Fixture();
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class, () -> f.validator()
                .validate(f.request(OwnerRef.player(UUID.randomUUID()), UUID.randomUUID(), f.world,
                        f.land, 5L)));
        assertEquals("delete.owner_mismatch", rejected.diagnosticKey());
    }

    @Test
    void serverOwnerMatchesServerLand() {
        UUID world = UUID.randomUUID();
        UUID steward = UUID.randomUUID();
        LandId serverLand = new LandId(UUID.randomUUID());
        String displayName = "Spawn";
        LandSnapshot snapshot = new LandSnapshot(serverLand, displayName,
                LandName.normalize(displayName), OwnerRef.server(), world,
                Set.of(new ChunkKey(world, 3, 3)), List.of(), 2, 0, NOW, NOW);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(snapshot)));
        SnapshotDeleteValidator validator = new SnapshotDeleteValidator(store,
                target -> serverLand.equals(target) ? OptionalLong.of(2L) : OptionalLong.empty());
        ValidatedDelete validated = validator.validate(
                new DeleteRequest(OwnerRef.server(), steward, world, serverLand, 2L));
        assertEquals(serverLand, validated.targetLandId());
        assertTrue(validated.owner() instanceof OwnerRef.ServerOwnerRef);
        assertEquals(2L, validated.expectedStructureRevision());
    }

    @Test
    void worldMismatchIsRejected() {
        Fixture f = new Fixture();
        ClaimRejectedException rejected = assertThrows(ClaimRejectedException.class, () -> f.validator()
                .validate(f.request(f.owner, f.actor, UUID.randomUUID(), f.land, 5L)));
        assertEquals("delete.world_mismatch", rejected.diagnosticKey());
    }

    @Test
    void nullRequestFailsFast() {
        Fixture f = new Fixture();
        assertThrows(NullPointerException.class, () -> f.validator().validate(null));
    }
}
