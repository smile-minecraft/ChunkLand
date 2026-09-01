package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.mutation.MutationRequest;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Derives an immutable set of logical reservation keys from a {@link MutationRequest}.
 * Keys are stable strings; no mutable collection is exposed to callers.
 */
public final class ReservationKeyExtractor {

    public Set<String> keys(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        Set<String> out = new LinkedHashSet<>();
        if (!request.chunks().isEmpty()) {
            for (ChunkKey ck : request.chunks()) {
                out.add(ck.worldId().toString() + ":" + ck.chunkX() + ":" + ck.chunkZ());
            }
            return Set.copyOf(out);
        }
        if (request.displayName() != null && request.requestedBy() != null) {
            String normalized = LandName.normalize(request.displayName());
            out.add("name:" + request.requestedBy().key() + ":" + normalized);
            return Set.copyOf(out);
        }
        if (request.landId() != null) {
            out.add("land:" + request.landId().value().toString());
            return Set.copyOf(out);
        }
        out.add("op:" + request.kind().name());
        return Set.copyOf(out);
    }
}
