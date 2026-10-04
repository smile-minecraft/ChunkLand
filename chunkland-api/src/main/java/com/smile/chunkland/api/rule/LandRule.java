package com.smile.chunkland.api.rule;

import com.smile.chunkland.api.permission.PermissionState;
import java.util.Objects;

/**
 * A single Land Rule setting: a rule type together with its tri-state value
 * {@link PermissionState} tri-state as ordinary
 * permissions but have no subject dimension and follow the
 * SubLand → Land → World → Global resolution order.
 *
 * <p>Thread-safe immutable value object.
 */
public record LandRule(LandRuleType type, PermissionState state) {
    public LandRule {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
    }
}
