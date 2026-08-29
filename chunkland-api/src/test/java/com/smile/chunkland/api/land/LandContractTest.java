package com.smile.chunkland.api.land;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LandContractTest {

    private final UUID worldA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID worldB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void chunkKeyEqualsAndHashByValue() {
        var a = new ChunkKey(worldA, 3, -4);
        var b = new ChunkKey(worldA, 3, -4);
        var c = new ChunkKey(worldA, 3, -5);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, new ChunkKey(worldB, 3, -4));
    }

    @Test
    void chunkKeyPackRoundTripsIncludingNegative() {
        for (int x : new int[] {0, 1, -1, 12345, -6789, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            for (int z : new int[] {0, 1, -1, 9876, -5432}) {
                var key = new ChunkKey(worldA, x, z);
                var back = ChunkKey.unpack(worldA, key.pack());
                assertEquals(key, back, () -> "roundtrip failed for " + x + "," + z);
                assertEquals(x, back.chunkX());
                assertEquals(z, back.chunkZ());
            }
        }
    }

    @Test
    void chunkKeyRejectsNullWorld() {
        assertThrows(NullPointerException.class, () -> new ChunkKey(null, 0, 0));
        assertThrows(NullPointerException.class, () -> ChunkKey.unpack(null, 0L));
    }

    @Test
    void ownerRefPlayerAndServer() {
        var p = OwnerRef.player(UUID.randomUUID());
        assertTrue(p instanceof OwnerRef.PlayerOwnerRef);
        assertTrue(p.key().startsWith("PLAYER:"));
        var s = OwnerRef.server();
        assertEquals("SERVER", s.key());
        assertEquals(s, OwnerRef.server());
        assertNotEquals(p, s);
    }

    @Test
    void ownerRefRejectsNullUuid() {
        assertThrows(NullPointerException.class, () -> OwnerRef.player(null));
    }

    @Test
    void landIdAndSubLandIdValueEquality() {
        var id = UUID.randomUUID();
        assertEquals(new LandId(id), new LandId(id));
        assertNotEquals(new LandId(id), new LandId(UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> new LandId(null));
        var sid = UUID.randomUUID();
        assertEquals(new SubLandId(sid), new SubLandId(sid));
        assertThrows(NullPointerException.class, () -> new SubLandId(null));
    }

    @Test
    void landSnapshotDeepCopiesCollections() {
        var chunks = new HashSet<ChunkKey>();
        chunks.add(new ChunkKey(worldA, 1, 1));
        var landId = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()),
                landId,
                null, 0, 10,
                Set.of(new ChunkKey(worldA, 2, 2)));
        var subs = new ArrayList<SubLandSnapshot>();
        subs.add(sub);
        var snap = new LandSnapshot(
                landId, "Home", "home",
                OwnerRef.server(), worldA, chunks, subs, 1L, 2L,
                Instant.EPOCH, Instant.EPOCH);
        chunks.add(new ChunkKey(worldA, 9, 9));
        subs.add(sub);
        assertEquals(1, snap.chunks().size());
        assertEquals(1, snap.subLands().size());
        assertThrows(UnsupportedOperationException.class, () -> snap.chunks().add(new ChunkKey(worldA, 5, 5)));
        assertThrows(UnsupportedOperationException.class, () -> snap.subLands().add(sub));
    }

    @Test
    void landSnapshotRejectsBlankNameAndNulls() {
        var id = new LandId(UUID.randomUUID());
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var subs = List.<SubLandSnapshot>of();
        assertThrows(NullPointerException.class,
                () -> new LandSnapshot(null, "Home", "home", OwnerRef.server(), worldA, chunks, subs, 1, 2, Instant.EPOCH, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(id, "", "home", OwnerRef.server(), worldA, chunks, subs, 1, 2, Instant.EPOCH, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(id, "Home", "  ", OwnerRef.server(), worldA, chunks, subs, 1, 2, Instant.EPOCH, Instant.EPOCH));
        assertThrows(NullPointerException.class,
                () -> new LandSnapshot(id, "Home", "home", null, worldA, chunks, subs, 1, 2, Instant.EPOCH, Instant.EPOCH));
        assertThrows(NullPointerException.class,
                () -> new LandSnapshot(id, "Home", "home", OwnerRef.server(), null, chunks, subs, 1, 2, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void subLandSnapshotRejectsInvertedBoundsAndNulls() {
        var parent = new LandId(UUID.randomUUID());
        var sid = new SubLandId(UUID.randomUUID());
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SubLandSnapshot(sid, parent, null, 10, 0, chunks));
        assertThrows(NullPointerException.class, () -> new SubLandSnapshot(null, parent, null, 0, 10, chunks));
        assertThrows(NullPointerException.class, () -> new SubLandSnapshot(sid, null, null, 0, 10, chunks));
        assertDoesNotThrow(() -> new SubLandSnapshot(sid, parent, null, 0, 10, chunks));
    }

    @Test
    void subLandSnapshotDeepCopiesChunks() {
        var input = new HashSet<ChunkKey>();
        input.add(new ChunkKey(worldA, 1, 1));
        var sub = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()),
                new LandId(UUID.randomUUID()),
                "storage", 0, 10, input);
        input.add(new ChunkKey(worldA, 2, 2));
        assertEquals(1, sub.chunks().size());
        assertThrows(UnsupportedOperationException.class, () -> sub.chunks().add(new ChunkKey(worldA, 3, 3)));
    }
}
