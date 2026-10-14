package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable input for a single land shrink ({@code CHUNK_REMOVE}).
 *
 * <p>The target land already exists: {@code targetLandId} is required, and the
 * chunk set is the delta to remove, never the full land. The three optimistic
 * tokens are all required — {@code selectionRevision}, {@code
 * sessionGeneration} and {@code structureRevision} — so any stale or
 * unverifiable confirmation fails closed in the validator instead of passing
 * by default.
 *
 * <p>{@code operationId} is the idempotency key of this operation. Chat
 * handlers mint a fresh key per accepted execution; the typed public entry
 * carries the caller-supplied key through. A {@code null} key preserves the
 * legacy behaviour: the saga mints its own key deep inside, so resubmits run
 * as independent operations. A non-null key is carried end to end into the
 * ledger, so a resubmit converges on the recorded result instead of running
 * twice. Validators additionally treat an explicitly tracked operation
 * (see {@code tracked} below) as sessionless: session-binding tokens that
 * are absent are skipped (the typed entry has no wand session to bind to)
 * while the structure token stays required and enforced. Legacy requests
 * without a key keep the historical strict behaviour, so existing callers
 * observe no change.
 *
 * <p>{@code tracked} marks an explicitly tracked operation: one that
 * carries an idempotency key end to end and has no wand session to bind
 * to (the typed public entry). Validators skip only absent session-binding
 * tokens for tracked operations; the structure token stays required.
 * Tracking without a key is rejected, and untracked requests keep the
 * historical strict behaviour even when they happen to carry a key, so a
 * future caller can never silently inherit the exemption.
 */
public record ShrinkRequest(
        OwnerRef owner,
        UUID actorUuid,
        UUID worldId,
        LandId targetLandId,
        Set<ChunkKey> delta,
        Long selectionRevision,
        Long sessionGeneration,
        Long structureRevision,
        UUID operationId,
        boolean tracked) {

    public ShrinkRequest {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(targetLandId, "targetLandId");
        Objects.requireNonNull(delta, "delta");
        if (delta.isEmpty()) {
            throw new IllegalArgumentException("delta must not be empty");
        }
        for (ChunkKey chunk : delta) {
            Objects.requireNonNull(chunk, "delta must not contain null");
            if (!worldId.equals(chunk.worldId())) {
                throw new IllegalArgumentException("chunk worldId does not match request worldId");
            }
        }
        delta = Set.copyOf(delta);
        if (selectionRevision != null && selectionRevision < 0) {
            throw new IllegalArgumentException("selectionRevision must not be negative");
        }
        if (sessionGeneration != null && sessionGeneration < 0) {
            throw new IllegalArgumentException("sessionGeneration must not be negative");
        }
        if (structureRevision != null && structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must not be negative");
        }
        if (tracked && operationId == null) {
            throw new IllegalArgumentException("tracked operations carry an operationId");
        }
    }

    /**
     * Legacy eight-argument form without an idempotency key. Delegates
     * untracked with a {@code null} key so historical callers keep the
     * legacy mint-inside behaviour unchanged.
     */
    public ShrinkRequest(
            OwnerRef owner,
            UUID actorUuid,
            UUID worldId,
            LandId targetLandId,
            Set<ChunkKey> delta,
            Long selectionRevision,
            Long sessionGeneration,
            Long structureRevision) {
        this(owner, actorUuid, worldId, targetLandId, delta, selectionRevision,
                sessionGeneration, structureRevision, null, false);
    }
}
