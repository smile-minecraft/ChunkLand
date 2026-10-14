package com.smile.chunkland.api.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedMutationRequestTest {

    private final UUID world = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID actor = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final OwnerRef owner = OwnerRef.player(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final LandId landId = new LandId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private final UUID operationId = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private Set<ChunkKey> oneChunk() {
        return Set.of(new ChunkKey(world, 1, 2));
    }

    private ExpectedVersion structureVersion() {
        return ExpectedVersion.structure(7L);
    }

    private ExpectedVersion policyVersion() {
        return ExpectedVersion.policy(3L);
    }

    @Test
    void landCreateCarriesFiveSeparatedFields() {
        var req = new TypedMutationRequest.LandCreate(
                actor, owner, null, null, operationId, world, oneChunk(), "Home");
        assertEquals(MutationKind.LAND_CREATE, req.kind());
        assertEquals(actor, req.actorUuid());
        assertEquals(owner, req.targetOwner());
        assertNull(req.landId(), "create has no target land yet");
        assertNull(req.expectedVersion(), "create has nothing to compare against");
        assertEquals(operationId, req.operationId());
        assertEquals(world, req.worldId());
        assertEquals("Home", req.displayName());
    }

    @Test
    void landDeleteCarriesFiveSeparatedFields() {
        var req = new TypedMutationRequest.LandDelete(
                actor, owner, landId, structureVersion(), operationId);
        assertEquals(MutationKind.LAND_DELETE, req.kind());
        assertEquals(actor, req.actorUuid());
        assertEquals(owner, req.targetOwner());
        assertEquals(landId, req.landId());
        assertEquals(structureVersion(), req.expectedVersion());
        assertEquals(operationId, req.operationId());
    }

    @Test
    void landChunkAddCarriesFiveSeparatedFields() {
        var req = new TypedMutationRequest.LandChunkAdd(
                actor, owner, landId, structureVersion(), operationId, world, oneChunk());
        assertEquals(MutationKind.LAND_CHUNK_ADD, req.kind());
        assertEquals(landId, req.landId());
        assertEquals(operationId, req.operationId());
        assertEquals(oneChunk(), req.chunks());
    }

    @Test
    void landChunkRemoveCarriesFiveSeparatedFields() {
        var req = new TypedMutationRequest.LandChunkRemove(
                actor, owner, landId, structureVersion(), operationId, world, oneChunk());
        assertEquals(MutationKind.LAND_CHUNK_REMOVE, req.kind());
        assertEquals(landId, req.landId());
        assertEquals(operationId, req.operationId());
    }

    @Test
    void landRenameCarriesFiveSeparatedFields() {
        var req = new TypedMutationRequest.LandRename(
                actor, owner, landId, structureVersion(), operationId, "NewName");
        assertEquals(MutationKind.LAND_RENAME, req.kind());
        assertEquals(landId, req.landId());
        assertEquals("NewName", req.displayName());
        assertEquals(operationId, req.operationId());
    }

    @Test
    void sublandCreateCarriesParentLandAndGeometry() {
        var cuboid = new Cuboid(16, 0, 32, 31, 255, 47);
        Set<ChunkKey> chunks = cuboid.coveredChunks(world);
        var req = new TypedMutationRequest.SublandCreate(
                actor, owner, landId, structureVersion(), operationId, world, "Cabin", chunks, cuboid);
        assertEquals(MutationKind.SUBLAND_CREATE, req.kind());
        assertEquals(landId, req.landId(), "landId() exposes the parent land");
        assertEquals(landId, req.parentLandId());
        assertEquals(operationId, req.operationId());
        assertEquals(cuboid, req.cuboid());
    }

    @Test
    void sublandDeleteCarriesParentAndSubLand() {
        var subLandId = new SubLandId(UUID.randomUUID());
        var req = new TypedMutationRequest.SublandDelete(
                actor, owner, landId, structureVersion(), operationId, subLandId);
        assertEquals(MutationKind.SUBLAND_DELETE, req.kind());
        assertEquals(landId, req.landId());
        assertEquals(subLandId, req.subLandId());
        assertEquals(operationId, req.operationId());
    }

    @Test
    void permissionChangeCarriesFiveSeparatedFields() {
        var subject = UUID.randomUUID();
        var req = new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.TRUST,
                PermissionChangedEvent.SubjectKind.PLAYER, subject,
                null, null, null);
        assertEquals(MutationKind.PERMISSION_CHANGE, req.kind());
        assertEquals(landId, req.landId());
        assertEquals(policyVersion(), req.expectedVersion());
        assertEquals(operationId, req.operationId());
        assertEquals(subject, req.subjectId());
    }

    @Test
    void ruleChangeCarriesFiveSeparatedFields() {
        var req = new TypedMutationRequest.RuleChange(
                actor, owner, landId, policyVersion(), operationId, LandRuleType.PVP, PermissionState.DENY);
        assertEquals(MutationKind.RULE_CHANGE, req.kind());
        assertEquals(landId, req.landId());
        assertEquals(LandRuleType.PVP, req.rule());
        assertEquals(PermissionState.DENY, req.state());
        assertEquals(operationId, req.operationId());
    }

    @Test
    void everyKindRejectsMissingOperationId() {
        UUID noOperation = null;
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.LandCreate(
                actor, owner, null, null, noOperation, world, oneChunk(), "Home"));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.LandDelete(
                actor, owner, landId, structureVersion(), noOperation));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.LandChunkAdd(
                actor, owner, landId, structureVersion(), noOperation, world, oneChunk()));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.LandChunkRemove(
                actor, owner, landId, structureVersion(), noOperation, world, oneChunk()));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.LandRename(
                actor, owner, landId, structureVersion(), noOperation, "NewName"));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.SublandCreate(
                actor, owner, landId, structureVersion(), noOperation, world, "Cabin", oneChunk(), null));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.SublandDelete(
                actor, owner, landId, structureVersion(), noOperation, new SubLandId(UUID.randomUUID())));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), noOperation, null,
                PermissionChangedEvent.ChangeKind.TRUST, PermissionChangedEvent.SubjectKind.PLAYER,
                UUID.randomUUID(), null, null, null));
        assertThrows(NullPointerException.class, () -> new TypedMutationRequest.RuleChange(
                actor, owner, landId, policyVersion(), noOperation, LandRuleType.PVP, PermissionState.DENY));
    }

    @Test
    void createRejectsTargetLandAndVersion() {
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.LandCreate(
                actor, owner, landId, null, operationId, world, oneChunk(), "Home"));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.LandCreate(
                actor, owner, null, structureVersion(), operationId, world, oneChunk(), "Home"));
    }

    @Test
    void structuralKindsRequireStructureRevision() {
        var policyOnly = policyVersion();
        assertThrows(IllegalArgumentException.class, () ->
                new TypedMutationRequest.LandDelete(actor, owner, landId, policyOnly, operationId));
        assertThrows(IllegalArgumentException.class, () ->
                new TypedMutationRequest.LandRename(actor, owner, landId, policyOnly, operationId, "X"));
    }

    @Test
    void policyKindsRequirePolicyRevision() {
        var structureOnly = structureVersion();
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, structureOnly, operationId, null,
                PermissionChangedEvent.ChangeKind.TRUST, PermissionChangedEvent.SubjectKind.PLAYER,
                UUID.randomUUID(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.RuleChange(
                actor, owner, landId, structureOnly, operationId, LandRuleType.PVP, PermissionState.DENY));
    }

    @Test
    void nonCreateKindsRequireTargetLand() {
        assertThrows(NullPointerException.class, () ->
                new TypedMutationRequest.LandDelete(actor, owner, null, structureVersion(), operationId));
        assertThrows(NullPointerException.class, () ->
                new TypedMutationRequest.RuleChange(
                        actor, owner, null, policyVersion(), operationId, LandRuleType.PVP, PermissionState.DENY));
    }

    @Test
    void chunkPayloadIsDefensivelyCopiedAndUnmodifiable() {
        var mutable = new HashSet<>(oneChunk());
        var req = new TypedMutationRequest.LandChunkAdd(
                actor, owner, landId, structureVersion(), operationId, world, mutable);
        mutable.add(new ChunkKey(world, 9, 9));
        assertEquals(1, req.chunks().size());
        assertThrows(UnsupportedOperationException.class,
                () -> req.chunks().add(new ChunkKey(world, 3, 3)));
    }

    @Test
    void sameOperationIdBuildsEqualRequests() {
        var first = new TypedMutationRequest.LandDelete(actor, owner, landId, structureVersion(), operationId);
        var second = new TypedMutationRequest.LandDelete(actor, owner, landId, structureVersion(), operationId);
        assertEquals(first, second);
        var other = new TypedMutationRequest.LandDelete(
                actor, owner, landId, structureVersion(), UUID.randomUUID());
        assertTrue(!first.equals(other));
    }

    @Test
    void actorMayEqualOrDifferFromTargetOwner() {
        var selfOwned = OwnerRef.player(actor);
        var self = new TypedMutationRequest.LandDelete(
                actor, selfOwned, landId, structureVersion(), operationId);
        assertEquals(self.actorUuid().toString(), self.targetOwner().key().substring("PLAYER:".length()));
        var admin = new TypedMutationRequest.LandDelete(actor, owner, landId, structureVersion(), operationId);
        assertTrue(!admin.actorUuid().toString().equals(admin.targetOwner().key()));
        assertNotNull(admin);
    }

    @Test
    void serverOwnedLandCreate() {
        var req = new TypedMutationRequest.LandCreate(
                actor, OwnerRef.server(), null, null, operationId, world, oneChunk(), "Spawn");
        assertEquals(OwnerRef.server(), req.targetOwner());
    }

    @Test
    void blankDisplayNameAndEmptyChunksRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.LandCreate(
                actor, owner, null, null, operationId, world, oneChunk(), "  "));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.LandCreate(
                actor, owner, null, null, operationId, world, Set.of(), "Home"));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.LandRename(
                actor, owner, landId, structureVersion(), operationId, ""));
    }

    @Test
    void mixedWorldChunksRejected() {
        var mixed = Set.of(new ChunkKey(world, 1, 1), new ChunkKey(UUID.randomUUID(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.LandCreate(
                actor, owner, null, null, operationId, world, mixed, "Home"));
    }

    @Test
    void sublandCuboidMustProjectToChunks() {
        var cuboid = new Cuboid(16, 0, 32, 31, 255, 47);
        Set<ChunkKey> matching = cuboid.coveredChunks(world);
        assertNotNull(new TypedMutationRequest.SublandCreate(
                actor, owner, landId, structureVersion(), operationId, world, "Cabin", matching, cuboid));
        var elsewhere = new Cuboid(0, 0, 0, 15, 255, 15);
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.SublandCreate(
                actor, owner, landId, structureVersion(), operationId, world, "Cabin", oneChunk(), elsewhere));
        assertNotNull(new TypedMutationRequest.SublandCreate(
                actor, owner, landId, structureVersion(), operationId, world, "Cabin", oneChunk(), null));
    }

    @Test
    void permissionChangeRequiresExactlyOneScope() {
        var subLandId = new SubLandId(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, null, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.TRUST, PermissionChangedEvent.SubjectKind.PLAYER,
                UUID.randomUUID(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, subLandId,
                PermissionChangedEvent.ChangeKind.TRUST, PermissionChangedEvent.SubjectKind.PLAYER,
                UUID.randomUUID(), null, null, null));
        assertNotNull(new TypedMutationRequest.PermissionChange(
                actor, owner, null, policyVersion(), operationId, subLandId,
                PermissionChangedEvent.ChangeKind.TRUST, PermissionChangedEvent.SubjectKind.PLAYER,
                UUID.randomUUID(), null, null, null));
    }

    @Test
    void subjectKindsRequireSubjectAndForbidActionStateProfile() {
        var subject = UUID.randomUUID();
        for (var kind : new PermissionChangedEvent.ChangeKind[] {
                PermissionChangedEvent.ChangeKind.TRUST,
                PermissionChangedEvent.ChangeKind.UNTRUST,
                PermissionChangedEvent.ChangeKind.BAN,
                PermissionChangedEvent.ChangeKind.UNBAN}) {
            assertNotNull(new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, policyVersion(), operationId, null,
                    kind, PermissionChangedEvent.SubjectKind.PLAYER, subject, null, null, null));
            assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, policyVersion(), operationId, null,
                    kind, null, null, null, null, null));
            assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, policyVersion(), operationId, null,
                    kind, PermissionChangedEvent.SubjectKind.PLAYER, subject,
                    ProtectionActionType.BLOCK_BREAK, null, null));
            assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, policyVersion(), operationId, null,
                    kind, PermissionChangedEvent.SubjectKind.PLAYER, subject,
                    null, PermissionState.ALLOW, null));
            assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, policyVersion(), operationId, null,
                    kind, PermissionChangedEvent.SubjectKind.PLAYER, subject,
                    null, null, UUID.randomUUID()));
        }
    }

    @Test
    void defaultSetRequiresActionAndStateAndForbidsSubject() {
        assertNotNull(new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.DEFAULT_SET, null, null,
                ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW, null));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.DEFAULT_SET, null, null,
                null, PermissionState.ALLOW, null));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.DEFAULT_SET, null, null,
                ProtectionActionType.BLOCK_BREAK, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.DEFAULT_SET,
                PermissionChangedEvent.SubjectKind.PLAYER, UUID.randomUUID(),
                ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW, null));
    }

    @Test
    void bindRequiresSubjectAndProfileAndForbidsActionState() {
        var subject = UUID.randomUUID();
        var profile = UUID.randomUUID();
        assertNotNull(new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.BIND, PermissionChangedEvent.SubjectKind.GROUP,
                subject, null, null, profile));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.BIND, PermissionChangedEvent.SubjectKind.GROUP,
                subject, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.BIND, PermissionChangedEvent.SubjectKind.GROUP,
                subject, ProtectionActionType.BLOCK_BREAK, null, profile));
    }

    @Test
    void unbindRequiresSubjectWithOptionalProfile() {
        var subject = UUID.randomUUID();
        assertNotNull(new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.UNBIND, PermissionChangedEvent.SubjectKind.PLAYER,
                subject, null, null, null));
        assertNotNull(new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.UNBIND, PermissionChangedEvent.SubjectKind.PLAYER,
                subject, null, null, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, owner, landId, policyVersion(), operationId, null,
                PermissionChangedEvent.ChangeKind.UNBIND, null, null, null, null, null));
    }

    @Test
    void expectedVersionValidation() {
        assertThrows(NullPointerException.class, () -> new ExpectedVersion(null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedVersion(-1L, null));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedVersion(null, -5L));
        assertEquals(7L, ExpectedVersion.structure(7L).structureRevision());
        assertEquals(3L, ExpectedVersion.policy(3L).landPolicyRevision());
    }

    @Test
    void legacyMappingPreservesKindLandAndPayload() {
        var create = new TypedMutationRequest.LandCreate(
                actor, owner, null, null, operationId, world, oneChunk(), "Home");
        var legacy = create.toLegacyRequest();
        assertEquals(MutationKind.LAND_CREATE, legacy.kind());
        assertNull(legacy.landId());
        assertEquals(owner, legacy.requestedBy());
        assertEquals(oneChunk(), legacy.chunks());
        assertEquals("Home", legacy.displayName());

        var rename = new TypedMutationRequest.LandRename(
                actor, owner, landId, structureVersion(), operationId, "NewName");
        var legacyRename = rename.toLegacyRequest();
        assertEquals(MutationKind.LAND_RENAME, legacyRename.kind());
        assertEquals(landId, legacyRename.landId());
        assertEquals("NewName", legacyRename.displayName());
    }
}
