package com.smile.chunkland.api.mutation;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Typed mutation request: one immutable shape per {@link MutationKind}.
 *
 * <p>Every shape carries the same five separated envelope fields, never merged
 * and never omitted:
 *
 * <ul>
 *   <li>{@code actorUuid} — the operator performing the mutation (always a
 *   player or console identity, even for server-owned land).</li>
 *   <li>{@code targetOwner} — the owner of the targeted land: the future owner
 *   for creates, the observed owner otherwise.</li>
 *   <li>{@code landId} — the targeted land, or {@code null} when it does not
 *   exist yet (create) or when the change is sub-land scoped.</li>
 *   <li>{@code expectedVersion} — the optimistic-concurrency expectation, or
 *   {@code null} only for creates, which have nothing to compare against.</li>
 *   <li>{@code operationId} — the idempotency key of this operation.</li>
 * </ul>
 *
 * <p><b>Authorization contract.</b> A constructed request is a claim, not a
 * proof — and today nothing downstream verifies the claim. The pipeline
 * currently receives neither the actor nor any actor check: actor and target
 * owner are recorded as separate fields so a future pipeline can enforce
 * them, but callers must not treat a constructed or accepted request as
 * authorized. In particular, nothing today stops a caller from naming an
 * arbitrary target owner.
 *
 * <p><b>Resubmission contract.</b> The same {@code operationId} resubmitted
 * must not take effect twice — but that is not enforced yet. The current
 * pipeline mints its own operation ids, so resubmitting one typed request
 * today would run as two separate operations. Keep the same {@code
 * operationId} across retries anyway, so the future durable dedup can
 * recognize them, and reconcile through the read API before retrying. The
 * typed submission entries refuse execution until the pipeline can carry the
 * identifier through.
 *
 * <p>Construction rejects missing safety fields fail-fast ({@link
 * NullPointerException} for a missing actor, owner, target, expectation or
 * operation identifier; {@link IllegalArgumentException} for a malformed
 * payload or a wrong expectation dimension). A request that cannot be built
 * can never be submitted. Unknown kinds cannot be constructed at all; the
 * typed submission entry points currently reject every kind.
 *
 * <p>Thread-safe: all shapes are immutable value objects with defensively
 * copied collections.
 *
 * @since 0.1.0
 */
public sealed interface TypedMutationRequest
        permits TypedMutationRequest.LandCreate,
                TypedMutationRequest.LandDelete,
                TypedMutationRequest.LandChunkAdd,
                TypedMutationRequest.LandChunkRemove,
                TypedMutationRequest.LandRename,
                TypedMutationRequest.SublandCreate,
                TypedMutationRequest.SublandDelete,
                TypedMutationRequest.PermissionChange,
                TypedMutationRequest.RuleChange {

    /** Which mutation this request describes. Never {@code null}. */
    MutationKind kind();

    /** Operator performing the mutation. Never {@code null}. */
    UUID actorUuid();

    /** Owner of the targeted land (future owner for creates). Never {@code null}. */
    OwnerRef targetOwner();

    /**
     * Targeted land, or {@code null} for creates (no target exists yet) and
     * for sub-land-scoped permission changes.
     */
    LandId landId();

    /**
     * Optimistic-concurrency expectation, or {@code null} only for creates.
     */
    ExpectedVersion expectedVersion();

    /** Idempotency key of this operation. Never {@code null}. */
    UUID operationId();

    /**
     * Render this request as the legacy envelope shape for the current pipeline.
     *
     * <p>This is a lossy projection, not a submission path. The legacy
     * envelope has no room for the expected version or the operation id, and
     * its single owner field carries the target owner, so the actor never
     * reaches downstream. Submitting this projection directly would execute
     * the mutation without version comparison, without idempotency and
     * without any actor check — the refuse-or-enforce invariant forbids
     * exactly that, which is why the typed submission entries refuse instead
     * of converting. Future pipeline work consumes the typed request itself.
     *
     * @return immutable legacy envelope describing the same mutation
     */
    MutationRequest toLegacyRequest();

    private static UUID requireActor(UUID actorUuid) {
        return Objects.requireNonNull(actorUuid, "actorUuid");
    }

    private static OwnerRef requireTargetOwner(OwnerRef targetOwner) {
        return Objects.requireNonNull(targetOwner, "targetOwner");
    }

    private static LandId requireTargetLand(LandId landId) {
        return Objects.requireNonNull(landId, "landId");
    }

    private static UUID requireOperation(UUID operationId) {
        return Objects.requireNonNull(operationId, "operationId");
    }

    private static ExpectedVersion requireStructure(ExpectedVersion expectedVersion) {
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        expectedVersion.requireStructureRevision();
        return expectedVersion;
    }

    private static ExpectedVersion requirePolicy(ExpectedVersion expectedVersion) {
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        expectedVersion.requireLandPolicyRevision();
        return expectedVersion;
    }

    private static String requireDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return displayName;
    }

    private static Set<ChunkKey> checkedChunks(Set<ChunkKey> chunks, UUID worldId) {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(worldId, "worldId");
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks must not contain null");
            if (!worldId.equals(chunk.worldId())) {
                throw new IllegalArgumentException("chunk worldId does not match request worldId");
            }
        }
        return Set.copyOf(chunks);
    }

    /**
     * Create a new land.
     *
     * <p>{@code landId} and {@code expectedVersion} must both be {@code null}:
     * the target does not exist yet, so there is nothing to address or to
     * compare against. Supplying either is rejected.
     *
     * @since 0.1.0
     */
    record LandCreate(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId landId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            UUID worldId,
            Set<ChunkKey> chunks,
            String displayName) implements TypedMutationRequest {

        public LandCreate {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            if (landId != null) {
                throw new IllegalArgumentException("create has no target landId yet");
            }
            if (expectedVersion != null) {
                throw new IllegalArgumentException("create carries no expectedVersion");
            }
            requireOperation(operationId);
            Objects.requireNonNull(worldId, "worldId");
            chunks = checkedChunks(chunks, worldId);
            displayName = requireDisplayName(displayName);
        }

        @Override
        public MutationKind kind() {
            return MutationKind.LAND_CREATE;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), null, targetOwner, chunks, displayName);
        }
    }

    /**
     * Delete an existing land.
     *
     * @since 0.1.0
     */
    record LandDelete(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId landId,
            ExpectedVersion expectedVersion,
            UUID operationId) implements TypedMutationRequest {

        public LandDelete {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            requireTargetLand(landId);
            requireStructure(expectedVersion);
            requireOperation(operationId);
        }

        @Override
        public MutationKind kind() {
            return MutationKind.LAND_DELETE;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), landId, targetOwner, Set.of(), null);
        }
    }

    /**
     * Add chunks to an existing land.
     *
     * @since 0.1.0
     */
    record LandChunkAdd(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId landId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            UUID worldId,
            Set<ChunkKey> chunks) implements TypedMutationRequest {

        public LandChunkAdd {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            requireTargetLand(landId);
            requireStructure(expectedVersion);
            requireOperation(operationId);
            Objects.requireNonNull(worldId, "worldId");
            chunks = checkedChunks(chunks, worldId);
        }

        @Override
        public MutationKind kind() {
            return MutationKind.LAND_CHUNK_ADD;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), landId, targetOwner, chunks, null);
        }
    }

    /**
     * Remove chunks from an existing land.
     *
     * @since 0.1.0
     */
    record LandChunkRemove(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId landId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            UUID worldId,
            Set<ChunkKey> chunks) implements TypedMutationRequest {

        public LandChunkRemove {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            requireTargetLand(landId);
            requireStructure(expectedVersion);
            requireOperation(operationId);
            Objects.requireNonNull(worldId, "worldId");
            chunks = checkedChunks(chunks, worldId);
        }

        @Override
        public MutationKind kind() {
            return MutationKind.LAND_CHUNK_REMOVE;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), landId, targetOwner, chunks, null);
        }
    }

    /**
     * Rename an existing land. Deeper name rules stay with the pipeline; the
     * shape only guarantees a non-blank candidate.
     *
     * @since 0.1.0
     */
    record LandRename(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId landId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            String displayName) implements TypedMutationRequest {

        public LandRename {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            requireTargetLand(landId);
            requireStructure(expectedVersion);
            requireOperation(operationId);
            displayName = requireDisplayName(displayName);
        }

        @Override
        public MutationKind kind() {
            return MutationKind.LAND_RENAME;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), landId, targetOwner, Set.of(), displayName);
        }
    }

    /**
     * Create a sub-land inside an existing land.
     *
     * <p>{@code cuboid} is the precise block-level geometry path and may be
     * {@code null} for legacy chunk-only geometry. When present, its complete
     * chunk projection must equal {@code chunks}; a partial or foreign
     * projection is rejected.
     *
     * @since 0.1.0
     */
    record SublandCreate(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId parentLandId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            UUID worldId,
            String subLandName,
            Set<ChunkKey> chunks,
            Cuboid cuboid) implements TypedMutationRequest {

        public SublandCreate {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            requireTargetLand(parentLandId);
            requireStructure(expectedVersion);
            requireOperation(operationId);
            Objects.requireNonNull(worldId, "worldId");
            subLandName = requireDisplayName(subLandName);
            chunks = checkedChunks(chunks, worldId);
            if (cuboid != null && !cuboid.coveredChunks(worldId).equals(chunks)) {
                throw new IllegalArgumentException(
                        "chunks must equal the cuboid's complete chunk projection");
            }
        }

        @Override
        public MutationKind kind() {
            return MutationKind.SUBLAND_CREATE;
        }

        @Override
        public LandId landId() {
            return parentLandId;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), parentLandId, targetOwner, chunks, subLandName);
        }
    }

    /**
     * Delete a sub-land from its parent land.
     *
     * @since 0.1.0
     */
    record SublandDelete(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId parentLandId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            SubLandId subLandId) implements TypedMutationRequest {

        public SublandDelete {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            requireTargetLand(parentLandId);
            requireStructure(expectedVersion);
            requireOperation(operationId);
            Objects.requireNonNull(subLandId, "subLandId");
        }

        @Override
        public MutationKind kind() {
            return MutationKind.SUBLAND_DELETE;
        }

        @Override
        public LandId landId() {
            return parentLandId;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), parentLandId, targetOwner, Set.of(), null);
        }
    }

    /**
     * Change one permission setting.
     *
     * <p>Exactly one of {@code landId} / {@code subLandId} is set, mirroring
     * the committed event. Each change kind additionally fixes its own
     * required fields, mirroring what the production services actually fire:
     * subject changes ({@code TRUST}, {@code UNTRUST}, {@code BAN}, {@code
     * UNBAN}) require a subject and forbid action, state and profile;
     * {@code DEFAULT_SET} requires action and state and forbids subject and
     * profile; {@code BIND} requires subject and profile and forbids action
     * and state; {@code UNBIND} requires a subject, forbids action and state,
     * and leaves the profile optional (unbinding does not need to name the
     * profile being removed).
     *
     * @since 0.1.0
     */
    record PermissionChange(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId landId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            SubLandId subLandId,
            PermissionChangedEvent.ChangeKind changeKind,
            PermissionChangedEvent.SubjectKind subjectKind,
            UUID subjectId,
            ProtectionActionType action,
            PermissionState state,
            UUID profileId) implements TypedMutationRequest {

        public PermissionChange {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            if ((landId == null) == (subLandId == null)) {
                throw new IllegalArgumentException("exactly one of landId/subLandId must be set");
            }
            requirePolicy(expectedVersion);
            requireOperation(operationId);
            Objects.requireNonNull(changeKind, "changeKind");
            switch (changeKind) {
                case TRUST, UNTRUST, BAN, UNBAN -> {
                    if (subjectKind == null || subjectId == null) {
                        throw new IllegalArgumentException(changeKind + " requires a subject");
                    }
                    if (action != null || state != null || profileId != null) {
                        throw new IllegalArgumentException(
                                changeKind + " carries no action, state or profile");
                    }
                }
                case DEFAULT_SET -> {
                    if (action == null || state == null) {
                        throw new IllegalArgumentException("DEFAULT_SET requires action and state");
                    }
                    if (subjectKind != null || subjectId != null || profileId != null) {
                        throw new IllegalArgumentException(
                                "DEFAULT_SET is subject-free and carries no profile");
                    }
                }
                case BIND -> {
                    if (subjectKind == null || subjectId == null || profileId == null) {
                        throw new IllegalArgumentException("BIND requires subject and profile");
                    }
                    if (action != null || state != null) {
                        throw new IllegalArgumentException("BIND carries no action or state");
                    }
                }
                case UNBIND -> {
                    if (subjectKind == null || subjectId == null) {
                        throw new IllegalArgumentException("UNBIND requires a subject");
                    }
                    if (action != null || state != null) {
                        throw new IllegalArgumentException("UNBIND carries no action or state");
                    }
                }
            }
        }

        @Override
        public MutationKind kind() {
            return MutationKind.PERMISSION_CHANGE;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), landId, targetOwner, Set.of(), null);
        }
    }

    /**
     * Change one land rule. Rules are land scoped, so {@code landId} is always
     * required.
     *
     * @since 0.1.0
     */
    record RuleChange(
            UUID actorUuid,
            OwnerRef targetOwner,
            LandId landId,
            ExpectedVersion expectedVersion,
            UUID operationId,
            LandRuleType rule,
            PermissionState state) implements TypedMutationRequest {

        public RuleChange {
            requireActor(actorUuid);
            requireTargetOwner(targetOwner);
            requireTargetLand(landId);
            requirePolicy(expectedVersion);
            requireOperation(operationId);
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(state, "state");
        }

        @Override
        public MutationKind kind() {
            return MutationKind.RULE_CHANGE;
        }

        @Override
        public MutationRequest toLegacyRequest() {
            return new MutationRequest(kind(), landId, targetOwner, Set.of(), null);
        }
    }
}
