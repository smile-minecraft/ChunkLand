package com.smile.chunkland.api.land;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identifier of a {@code Land}.
 *
 * <p>Thread-safe: a pure value object with no mutable state. Instances may be
 * shared freely across threads and used as map/set keys.
 */
public record LandId(UUID value) {
    public LandId {
        Objects.requireNonNull(value, "value");
    }
}
