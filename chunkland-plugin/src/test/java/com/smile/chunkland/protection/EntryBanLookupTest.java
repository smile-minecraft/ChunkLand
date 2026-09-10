package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The snapshot-backed ban seam answers known lands from memory, clears
 * wilderness, and fails closed (empty) on anything unverifiable — never SQL,
 * never Bukkit, never the network.
 */
class EntryBanLookupTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID BANNED = UUID.randomUUID();
    private static final UUID CLEAR = UUID.randomUUID();

    private LandRegistryStore registryWith(LandId land) {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                new LandSnapshot(land, "Home", "home", OwnerRef.player(OWNER), WORLD,
                        Set.of(new ChunkKey(WORLD, 0, 0)), List.of(),
                        0, 0, Instant.EPOCH, Instant.EPOCH))));
        return store;
    }

    private Supplier<LandAuthorisationSnapshot> bansWith(LandId land) {
        LandAuthorisationSnapshot snapshot = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(land, Set.of(BANNED)));
        return () -> snapshot;
    }

    @Test
    void knownBannedAnswersTrueAndKnownClearAnswersFalse() {
        LandId land = new LandId(UUID.randomUUID());
        LandRegistryStore store = registryWith(land);
        EntryBanLookup lookup = new EntryBanLookup(store::snapshot, bansWith(land));

        assertEquals(Optional.of(true), lookup.bannedAt(BANNED, WORLD, 0, 0),
                "banned player on a known land must read banned");
        assertEquals(Optional.of(false), lookup.bannedAt(CLEAR, WORLD, 0, 0),
                "unbanned player on a known land must read clear");
    }

    @Test
    void wildernessReadsClear() {
        LandId land = new LandId(UUID.randomUUID());
        LandRegistryStore store = registryWith(land);
        EntryBanLookup lookup = new EntryBanLookup(store::snapshot, bansWith(land));

        assertEquals(Optional.of(false), lookup.bannedAt(BANNED, WORLD, 9, 9),
                "wilderness has no owning land, so nobody is banned there");
    }

    @Test
    void missingOrFailingSourcesFailClosed() {
        LandId land = new LandId(UUID.randomUUID());
        LandRegistryStore store = registryWith(land);

        assertTrue(new EntryBanLookup(null, bansWith(land))
                .bannedAt(BANNED, WORLD, 0, 0).isEmpty(),
                "missing registry source must fail closed");
        assertTrue(new EntryBanLookup(store::snapshot, null)
                .bannedAt(BANNED, WORLD, 0, 0).isEmpty(),
                "missing ban source must fail closed");
        assertTrue(new EntryBanLookup(store::snapshot, () -> null)
                .bannedAt(BANNED, WORLD, 0, 0).isEmpty(),
                "null snapshot must fail closed");
        assertTrue(new EntryBanLookup(
                        (Supplier<LandRegistry>) () -> {
                            throw new RuntimeException("index boom");
                        }, bansWith(land))
                .bannedAt(BANNED, WORLD, 0, 0).isEmpty(),
                "throwing registry must fail closed");
        assertTrue(new EntryBanLookup(store::snapshot, () -> {
                            throw new RuntimeException("ban boom");
                        })
                .bannedAt(BANNED, WORLD, 0, 0).isEmpty(),
                "throwing ban source must fail closed");
        assertTrue(new EntryBanLookup(store::snapshot, bansWith(land))
                .bannedAt(null, WORLD, 0, 0).isEmpty(),
                "null player must fail closed");
    }
}
