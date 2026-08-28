package com.smile.chunkland.api.land;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identifier of a {@code SubLand}.
 *
 * <p>Thread-safe value object (see {@link LandId}).
 */
public record SubLandId(UUID value) {
    public SubLandId {
        Objects.requireNonNull(value, "value");
    }
}
