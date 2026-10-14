package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import java.util.concurrent.CompletionStage;

/**
 * Durable commit seam for permission changes behind the public typed
 * mutations.
 *
 * <p>The production implementation routes each
 * {@link com.smile.chunkland.api.event.PermissionChangedEvent.ChangeKind}
 * to the same service the chat and management paths use, with the same
 * single public Post event per committed mutation:
 *
 * <ul>
 *   <li>{@code TRUST} / {@code UNTRUST} / {@code BAN} / {@code UNBAN} —
 *   land scoped only, through the direct-trust service.</li>
 *   <li>{@code DEFAULT_SET} — land scoped only, through the conditional
 *   default write that pins the observed value and authorisation
 *   generation.</li>
 *   <li>{@code BIND} / {@code UNBIND} — land or subland scoped, through
 *   the generic binding service with the durable owner-namespace check
 *   inside the transaction.</li>
 * </ul>
 *
 * <p>Scope validation (land-only kinds on a subland scope, group subjects
 * on subject-player kinds, non-whitelisted default actions) is owned by
 * the typed facade before this seam is touched; implementations only
 * perform the durable commit they were built for. Baseline pins for the
 * conditional writes are read from the live authorisation snapshot
 * inside the implementation, so the pin is always fresher than the
 * facade's pre-check and the transaction stays the authority.
 */
public interface PermissionWritePort {

    /**
     * Durably apply one permission change.
     *
     * @param request validated typed request (scope already checked by the caller)
     * @param scopeParent live land snapshot owning the change: the target
     *         land for land-scoped changes, the parent land for
     *         subland-scoped binding changes
     */
    CompletionStage<Void> apply(TypedMutationRequest.PermissionChange request, LandSnapshot scopeParent);
}
