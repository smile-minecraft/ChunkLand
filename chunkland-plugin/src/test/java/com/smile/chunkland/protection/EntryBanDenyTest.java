package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ENTRY bans deny through the existing DENY-first subject chain: a banned
 * player loses ENTRY even with a direct trust ALLOW, unbanning restores the
 * original ALLOW, other actions keep their trust grants, and land rules are
 * never touched by a ban.
 */
class EntryBanDenyTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static LandSnapshot land(LandId id, UUID owner) {
        return land(id, owner, 0, 0);
    }

    private static LandSnapshot land(LandId id, UUID owner, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "Home", "home", OwnerRef.player(owner), WORLD,
                Set.of(new ChunkKey(WORLD, chunkX, chunkZ)), List.of(),
                0, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandRegistry registry(LandId id) {
        return LandRegistry.from(List.of(land(id, OWNER)));
    }

    private static LandAuthorisationSnapshot trustedAndBanned(LandId land, boolean banned) {
        Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct = new HashMap<>();
        direct.put(land, Map.of(TARGET, Set.copyOf(DirectTrustWhitelist.ALLOWED)));
        Map<LandId, Set<UUID>> bans = new HashMap<>();
        if (banned) {
            bans.put(land, Set.of(TARGET));
        }
        return LandAuthorisationSnapshot.copyOf(direct, Map.of(), bans);
    }

    private static SubjectPermissionLookup lookupWith(LandAuthorisationSnapshot snapshot) {
        LandAuthorisationCache cache = new LandAuthorisationCache();
        cache.publish(snapshot);
        return new ConfigSubjectPermissionLookup(
                PermissionDefaultsSnapshot::empty, cache::snapshot);
    }

    private static PermissionState decide(LandId land, UUID actor,
            ProtectionActionType action, SubjectPermissionLookup lookup) {
        LandRegistry lands = registry(land);
        SnapshotPermissionContextProvider provider =
                new SnapshotPermissionContextProvider(null, lookup);
        return PermissionResolver.resolve(
                provider.provide(actor, land, action, lands)).outcome();
    }

    @Test
    void banDeniesEntryDespiteTrustAllow() {
        LandId land = new LandId(UUID.randomUUID());
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.ENTRY,
                        lookupWith(trustedAndBanned(land, true))),
                "ENTRY ban DENY must win over the direct trust ALLOW (DENY-first)");
    }

    @Test
    void unbanRestoresTrustAllow() {
        LandId land = new LandId(UUID.randomUUID());
        assertEquals(PermissionState.ALLOW,
                decide(land, TARGET, ProtectionActionType.ENTRY,
                        lookupWith(trustedAndBanned(land, false))),
                "removing the ban must restore the original trust ALLOW");
    }

    @Test
    void banLeavesOtherTrustGrantsAlone() {
        LandId land = new LandId(UUID.randomUUID());
        assertEquals(PermissionState.ALLOW,
                decide(land, TARGET, ProtectionActionType.BLOCK_BREAK,
                        lookupWith(trustedAndBanned(land, true))),
                "an ENTRY ban must not revoke other trusted actions");
    }

    @Test
    void banNeverDecidesLandRules() {
        LandId land = new LandId(UUID.randomUUID());
        LandRegistry lands = registry(land);
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(
                (landId, rule, snapshot) -> java.util.Optional.of(PermissionState.ALLOW),
                lookupWith(trustedAndBanned(land, true)));
        assertEquals(PermissionState.ALLOW,
                PermissionResolver.resolve(provider.provide(
                        TARGET, land, ProtectionActionType.FIRE_SPREAD, lands)).outcome(),
                "a ban must not change the LAND_RULE outcome");
    }

    @Test
    void banIsPerPlayerAndPerLand() {
        LandId land = new LandId(UUID.randomUUID());
        LandId other = new LandId(UUID.randomUUID());
        LandRegistry lands = LandRegistry.from(List.of(land(land, OWNER, 0, 0), land(other, OWNER, 5, 5)));
        Map<LandId, Map<ProtectionActionType, PermissionState>> defaults = new HashMap<>();
        Map<ProtectionActionType, PermissionState> allowEntry = new EnumMap<>(ProtectionActionType.class);
        allowEntry.put(ProtectionActionType.ENTRY, PermissionState.ALLOW);
        defaults.put(land, allowEntry);
        defaults.put(other, allowEntry);
        LandAuthorisationSnapshot snapshot = LandAuthorisationSnapshot.copyOf(
                Map.of(), defaults, Map.of(land, Set.of(TARGET)));
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(
                null, lookupWith(snapshot));
        assertEquals(PermissionState.DENY,
                PermissionResolver.resolve(
                        provider.provide(TARGET, land, ProtectionActionType.ENTRY, lands)).outcome(),
                "banned player is denied on the banned land");
        assertEquals(PermissionState.ALLOW,
                PermissionResolver.resolve(
                        provider.provide(TARGET, other, ProtectionActionType.ENTRY, lands)).outcome(),
                "the same player stays allowed on other lands");
        assertEquals(PermissionState.ALLOW,
                PermissionResolver.resolve(
                        provider.provide(STRANGER, land, ProtectionActionType.ENTRY, lands)).outcome(),
                "other players stay allowed on the banned land");
    }

    @Test
    void ownerGuaranteeStillApplies() {
        LandId land = new LandId(UUID.randomUUID());
        assertEquals(PermissionState.ALLOW,
                decide(land, OWNER, ProtectionActionType.ENTRY,
                        lookupWith(trustedAndBanned(land, true))),
                "the owner guarantee is untouched: owners always pass ENTRY");
    }
}
