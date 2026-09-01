package com.smile.chunkland.runtime.api;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Optional;

/**
 * Memory-only lookup for the effective rule state of a land.
 * Returns {@link Optional#empty()} when the land does not exist.
 * The lookup must derive its answer from the given immutable {@link LandRegistry}
 * snapshot — the same instance the caller used for the existence check — and
 * must never independently re-read a newer publication.
 */
@FunctionalInterface
public interface LandRuleLookup {
    Optional<PermissionState> getRule(LandId landId, LandRuleType rule, LandRegistry snapshot);
}
