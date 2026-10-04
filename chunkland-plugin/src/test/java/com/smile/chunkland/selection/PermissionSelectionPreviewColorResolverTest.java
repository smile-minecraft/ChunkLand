package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.protection.SubjectPermissionLookup;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PermissionSelectionPreviewColorResolverTest {
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LandId LAND = new LandId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final SelectionPoint POINT = new SelectionPoint(WORLD, 4, 64, 4);

    @Test
    void ownPlayerLandIsGreen() {
        PermissionSelectionPreviewColorResolver resolver = resolver(Map.of());
        assertEquals(SelectionPreviewColor.OWN, resolver.resolve(ACTOR,
                new SelectionLandContext(LAND, OwnerRef.player(ACTOR), 1L), POINT));
    }

    @Test
    void foreignLandIsBlueWhenEntryAndAThirdOrFourthTierActionAllow() {
        PermissionSelectionPreviewColorResolver resolver = resolver(Map.of(
                ProtectionActionType.ENTRY, PermissionState.ALLOW,
                ProtectionActionType.BUCKET_USE, PermissionState.ALLOW));
        assertEquals(SelectionPreviewColor.ACCESSIBLE, resolver.resolve(ACTOR,
                new SelectionLandContext(LAND, OwnerRef.player(UUID.randomUUID()), 1L), POINT));
    }

    @Test
    void entryAllowButOnlySecondTierAllowStaysRed() {
        PermissionSelectionPreviewColorResolver resolver = resolver(Map.of(
                ProtectionActionType.ENTRY, PermissionState.ALLOW,
                ProtectionActionType.DOOR_USE, PermissionState.ALLOW));
        assertEquals(SelectionPreviewColor.BLOCKED, resolver.resolve(ACTOR,
                new SelectionLandContext(LAND, OwnerRef.player(UUID.randomUUID()), 1L), POINT));
    }

    @Test
    void entryAllowAndFourthTierAllowIsBlue() {
        PermissionSelectionPreviewColorResolver resolver = resolver(Map.of(
                ProtectionActionType.ENTRY, PermissionState.ALLOW,
                ProtectionActionType.MANAGE_MEMBER, PermissionState.ALLOW));
        assertEquals(SelectionPreviewColor.ACCESSIBLE, resolver.resolve(ACTOR,
                new SelectionLandContext(LAND, OwnerRef.player(UUID.randomUUID()), 1L), POINT));
    }

    @Test
    void coveringSublandDenyOverridesLandAllowForThePreviewSample() {
        SubLandSnapshot subLand = new SubLandSnapshot(
                new SubLandId(UUID.randomUUID()), LAND, "den", 60, 70,
                java.util.Set.of(new ChunkKey(WORLD, 0, 0)));
        SubjectPermissionLookup subject = new SubjectPermissionLookup() {
            @Override
            public Grant grants(UUID actor, LandId landId, ProtectionActionType action, LandRegistry snapshot) {
                if (action == ProtectionActionType.ENTRY || action == ProtectionActionType.BUCKET_USE) {
                    return new Grant(List.of(new PermissionBinding(PermissionSubject.player(ACTOR),
                            new Permission(action, PermissionState.ALLOW))),
                            PermissionState.INHERIT, PermissionState.INHERIT, PermissionState.INHERIT);
                }
                return new Grant(List.of(), PermissionState.INHERIT,
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }

            @Override
            public SublandGrant sublandGrants(UUID actor, LandId landId, SubLandId sublandId,
                    ProtectionActionType action, LandRegistry snapshot) {
                if (action == ProtectionActionType.BUCKET_USE) {
                    return new SublandGrant(List.of(new PermissionBinding(PermissionSubject.player(ACTOR),
                            new Permission(action, PermissionState.DENY))),
                            PermissionState.INHERIT);
                }
                return new SublandGrant(List.of(), PermissionState.INHERIT);
            }
        };
        LandRegistry registry = LandRegistry.from(List.of(new LandSnapshot(
                LAND, "Land", "land", OwnerRef.player(UUID.randomUUID()), WORLD,
                java.util.Set.of(new ChunkKey(WORLD, 0, 0)), List.of(subLand), 1, 0,
                Instant.EPOCH, Instant.EPOCH)));
        PermissionSelectionPreviewColorResolver resolver = new PermissionSelectionPreviewColorResolver(
                new ProtectionEngine(() -> registry, new SnapshotPermissionContextProvider(null, subject)));

        assertEquals(SelectionPreviewColor.BLOCKED, resolver.resolve(ACTOR,
                new SelectionLandContext(LAND, OwnerRef.player(UUID.randomUUID()), 1L), POINT));
    }

    @Test
    void entryDenyAndThirdTierAllowStaysBlocked() {
        PermissionSelectionPreviewColorResolver resolver = resolver(Map.of(
                ProtectionActionType.ENTRY, PermissionState.DENY,
                ProtectionActionType.BUCKET_USE, PermissionState.ALLOW));
        assertEquals(SelectionPreviewColor.BLOCKED, resolver.resolve(ACTOR,
                new SelectionLandContext(LAND, OwnerRef.player(UUID.randomUUID()), 1L), POINT));
    }

    @Test
    void entryDenyAndOnlySecondTierAllowStayRed() {
        PermissionSelectionPreviewColorResolver resolver = resolver(Map.of(
                ProtectionActionType.DOOR_USE, PermissionState.ALLOW));
        assertEquals(SelectionPreviewColor.BLOCKED, resolver.resolve(ACTOR,
                new SelectionLandContext(LAND, OwnerRef.player(UUID.randomUUID()), 1L), POINT));
    }

    @Test
    void unavailableSnapshotAndResolverFailureStayRed() {
        SelectionLandContext foreign = new SelectionLandContext(LAND, OwnerRef.player(UUID.randomUUID()), 1L);
        assertEquals(SelectionPreviewColor.BLOCKED,
                new PermissionSelectionPreviewColorResolver(new ProtectionEngine(() -> null,
                        ProtectionEngine.inheritOnlyProvider())).resolve(ACTOR, foreign, POINT));
        assertEquals(SelectionPreviewColor.BLOCKED, resolverWithProvider((actor, land, action, snapshot) -> {
            throw new IllegalStateException("permission backend unavailable");
        }).resolve(ACTOR, foreign, POINT));
    }

    @Test
    void serverLandIsNotTreatedAsPlayerOwned() {
        SelectionLandContext server = new SelectionLandContext(LAND, OwnerRef.server(), 1L);
        assertEquals(SelectionPreviewColor.BLOCKED, resolver(Map.of()).resolve(ACTOR, server, POINT));
    }

    private static PermissionSelectionPreviewColorResolver resolver(Map<ProtectionActionType, PermissionState> states) {
        return resolverWithProvider((actor, land, action, snapshot) -> new PermissionContext(
                action, false,
                List.of(new PermissionBinding(PermissionSubject.player(ACTOR),
                        new Permission(action, states.getOrDefault(action, PermissionState.DENY)))),
                PermissionState.INHERIT, PermissionState.INHERIT));
    }

    private static PermissionSelectionPreviewColorResolver resolverWithProvider(
            PermissionContextProvider provider) {
        LandRegistry registry = LandRegistry.from(List.of(new LandSnapshot(
                LAND, "Land", "land", OwnerRef.player(UUID.randomUUID()), WORLD,
                java.util.Set.of(new ChunkKey(WORLD, 0, 0)), List.of(), 1, 0,
                Instant.EPOCH, Instant.EPOCH)));
        return new PermissionSelectionPreviewColorResolver(
                new ProtectionEngine(() -> registry, provider));
    }
}
