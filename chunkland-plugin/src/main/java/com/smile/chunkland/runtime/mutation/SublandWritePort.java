package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Durable commit seam for SubLand create/delete behind the public typed
 * mutations.
 *
 * <p>The production implementation drives the same pieces the chat path
 * uses — the pure {@link com.smile.chunkland.subland.SubLandService}
 * validation (parent containment, 3D overlap, limits, depth floor) and
 * the same atomic commit, runtime publish and single public Post event —
 * but without the chat confirmation lifecycle: there is no selection
 * session to accept or release, and idempotency comes from the public
 * operation ledger row the caller created first, not from a single-use
 * confirmation mark. Validation failures complete exceptionally (the
 * caller records its terminal ledger row); only a fully committed and
 * published mutation completes normally.
 */
public interface SublandWritePort {

    /**
     * Durably create one precise SubLand under its parent.
     *
     * @param actor confirming operator
     * @param parent live parent snapshot the candidate was validated against
     * @param candidate precise candidate for the parent (carries a
     *         {@code Cuboid}; legacy chunk-only geometry is rejected)
     * @param expectedStructure parent structure revision observed by the caller
     * @return the persisted candidate
     */
    CompletionStage<SubLandSnapshot> create(
            UUID actor, LandSnapshot parent, SubLandSnapshot candidate, long expectedStructure);

    /**
     * Durably delete one SubLand from its parent.
     *
     * @param actor confirming operator
     * @param parent live parent snapshot observed by the caller
     * @param target SubLand to remove
     * @param expectedStructure parent structure revision observed by the caller
     */
    CompletionStage<Void> delete(
            UUID actor, LandSnapshot parent, SubLandId target, long expectedStructure);
}
