package com.smile.chunkland.selection;

import java.util.Optional;
import java.util.UUID;

/** Resolves a stored world UUID to its current config key without storing a World. */
@FunctionalInterface
public interface SelectionWorldNameResolver {
    Optional<String> nameOf(UUID worldId);
}
