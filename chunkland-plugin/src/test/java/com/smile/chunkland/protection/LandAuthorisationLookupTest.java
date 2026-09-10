package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
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
 * Land bindings and land defaults answer through the production lookup and
 * publish chain: trust beats a denying default, untrust falls back to the
 * default, publishes are immutable snapshots, and management actions plus
 * land rules stay out of reach of direct bindings.
 */
class LandAuthorisationLookupTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static LandSnapshot land(LandId id, UUID owner) {
        return new LandSnapshot(id, "Home", "home", OwnerRef.player(owner), WORLD,
                Set.of(new ChunkKey(WORLD, 0, 0)), List.of(),
                0, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandRegistry registry(LandId id) {
        return LandRegistry.from(List.of(land(id, OWNER)));
    }

    private static LandAuthorisationSnapshot snapshotWithTrustAndDenyDefault(LandId land) {
        Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct = new HashMap<>();
        direct.put(land, Map.of(TARGET,
                Set.copyOf(DirectTrustWhitelist.ALLOWED)));
        Map<LandId, Map<ProtectionActionType, PermissionState>> defaults = new HashMap<>();
        Map<ProtectionActionType, PermissionState> landDefaults = new EnumMap<>(ProtectionActionType.class);
        landDefaults.put(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY);
        defaults.put(land, landDefaults);
        return LandAuthorisationSnapshot.copyOf(direct, defaults);
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
    void trustBeatsDenyingDefaultAndUntrustFallsBack() {
        LandId land = new LandId(UUID.randomUUID());
        LandAuthorisationSnapshot trusted = snapshotWithTrustAndDenyDefault(land);
        assertEquals(PermissionState.ALLOW,
                decide(land, TARGET, ProtectionActionType.BLOCK_BREAK, lookupWith(trusted)),
                "direct binding must win over a denying land default");
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.BLOCK_BREAK, lookupWith(trusted)),
                "strangers without a binding must observe the land default");

        // Untrust: only the binding row is gone, the default still denies.
        Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct = new HashMap<>();
        direct.put(land, Map.of());
        Map<LandId, Map<ProtectionActionType, PermissionState>> defaults = new HashMap<>();
        Map<ProtectionActionType, PermissionState> landDefaults = new EnumMap<>(ProtectionActionType.class);
        landDefaults.put(ProtectionActionType.BLOCK_BREAK, PermissionState.DENY);
        defaults.put(land, landDefaults);
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.BLOCK_BREAK,
                        lookupWith(LandAuthorisationSnapshot.copyOf(direct, defaults))));

        // Default ALLOW reaches strangers and the trusted player alike.
        Map<ProtectionActionType, PermissionState> allow = new EnumMap<>(ProtectionActionType.class);
        allow.put(ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW);
        Map<LandId, Map<ProtectionActionType, PermissionState>> allowDefaults = new HashMap<>();
        allowDefaults.put(land, allow);
        SubjectPermissionLookup allowLookup = lookupWith(
                LandAuthorisationSnapshot.copyOf(Map.of(), allowDefaults));
        assertEquals(PermissionState.ALLOW,
                decide(land, STRANGER, ProtectionActionType.BLOCK_BREAK, allowLookup));
    }

    @Test
    void publishIsImmutableAndVisibleToNewReadersOnly() {
        LandId land = new LandId(UUID.randomUUID());
        LandAuthorisationCache cache = new LandAuthorisationCache();
        LandAuthorisationSnapshot before = LandAuthorisationSnapshot.empty();
        cache.publish(before);
        SubjectPermissionLookup lookup =
                new ConfigSubjectPermissionLookup(PermissionDefaultsSnapshot::empty, cache::snapshot);
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.BLOCK_BREAK, lookup));

        LandAuthorisationSnapshot after = snapshotWithTrustAndDenyDefault(land);
        cache.publish(after);
        // The previously captured snapshot object keeps answering with its own values.
        assertEquals(PermissionState.INHERIT, before.landDefault(land, ProtectionActionType.BLOCK_BREAK));
        assertTrue(before.directAllows(TARGET, land).isEmpty());
        // New readers through the same lookup observe the publish immediately.
        assertEquals(PermissionState.ALLOW,
                decide(land, TARGET, ProtectionActionType.BLOCK_BREAK, lookup));
        assertSame(after, cache.snapshot());
        assertNotSame(before, cache.snapshot());
    }

    @Test
    void ownerGuaranteeAndManagementAndRulesAreUnaffected() {
        LandId land = new LandId(UUID.randomUUID());
        SubjectPermissionLookup lookup = lookupWith(snapshotWithTrustAndDenyDefault(land));
        assertEquals(PermissionState.ALLOW,
                decide(land, OWNER, ProtectionActionType.BLOCK_BREAK, lookup),
                "owner guarantee must hold regardless of bindings and defaults");
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.MANAGE_MEMBER, lookup),
                "trust whitelist must never authorise management actions");
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.MANAGE_PERMISSION, lookup),
                "trust whitelist must never authorise permission administration");
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.FIRE_SPREAD, lookup),
                "land rules must stay outside direct bindings");
        assertEquals(DecisionSource.LAND_RULE,
                ProtectionActionType.FIRE_SPREAD.decisionSource());
    }

    @Test
    void failingLandSourceFailsClosedAndSnapshotRejectsNulls() {
        LandId land = new LandId(UUID.randomUUID());
        SubjectPermissionLookup broken = new ConfigSubjectPermissionLookup(
                PermissionDefaultsSnapshot::empty, () -> {
                    throw new IllegalStateException("injected");
                });
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.BLOCK_BREAK, broken));
        SubjectPermissionLookup absent = new ConfigSubjectPermissionLookup(
                PermissionDefaultsSnapshot::empty, null);
        assertEquals(PermissionState.DENY,
                decide(land, TARGET, ProtectionActionType.BLOCK_BREAK, absent));

        LandAuthorisationSnapshot empty = LandAuthorisationSnapshot.copyOf(null, null);
        assertTrue(empty.directAllows(TARGET, land).isEmpty());
        assertEquals(PermissionState.INHERIT,
                empty.landDefault(land, ProtectionActionType.BLOCK_BREAK));
        assertThrows(NullPointerException.class, () -> empty.directAllows(null, land));
        assertThrows(NullPointerException.class, () -> empty.landDefault(land, null));
    }
}
