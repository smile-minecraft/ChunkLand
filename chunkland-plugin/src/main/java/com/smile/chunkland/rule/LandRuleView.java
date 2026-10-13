package com.smile.chunkland.rule;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Objects;

/**
 * One rule's display triple: the land's own override, the value that
 * actually decides, and where that value comes from.
 *
 * @param explicit the land's own override ({@code INHERIT} when unset)
 * @param effective the deciding value (concrete; {@code INHERIT} only when
 *                  the source is {@link Source#UNAVAILABLE})
 * @param source where the effective value comes from
 */
public record LandRuleView(LandRuleType rule, PermissionState explicit,
        PermissionState effective, Source source) {

    /** Where one rule's effective value comes from. */
    public enum Source {
        /** The land's own override. */
        LAND,
        /** The world's rule default. */
        WORLD,
        /** The server-wide rule default. */
        GLOBAL,
        /** The built-in default. */
        BUILT_IN,
        /** The durable rows are not loaded; nothing may be shown. */
        UNAVAILABLE
    }

    public LandRuleView {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(explicit, "explicit");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(source, "source");
    }
}
