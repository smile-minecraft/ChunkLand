package com.smile.chunkland.api.permission;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, full-resolution context for one protection decision (spec §25-§27, §51-1).
 *
 * <p>It expresses the complete §27 chain for a single {@link ProtectionActionType}
 * on a single Land. The resolver walks these layers top-down and stops at the first
 * explicit (non-{@code INHERIT}) value; {@code INHERIT} falls through to the next
 * layer. Within a binding layer, direct-player and group bindings are aggregated
 * together with flat DENY-first precedence (spec §26.1) — direct does <em>not</em>
 * outrank group.
 *
 * <h3>Subject chain (SUBJECT_PERMISSION)</h3>
 * <pre>
 * Admin Bypass
 *   -> Owner Guarantee (only if isOwner && action source is SUBJECT_PERMISSION)
 *   -> SubLand Direct + Groups  (aggregate, DENY > ALLOW > INHERIT)
 *   -> SubLand Default
 *   -> Land Direct + Groups     (aggregate)
 *   -> Land Default
 *   -> World Default
 *   -> Global Default
 *   -> implicit DENY (fail-closed)
 * </pre>
 *
 * <h3>Rule chain (LAND_RULE)</h3>
 * <pre>
 * Admin Bypass
 *   -> SubLand Rule
 *   -> Land Rule
 *   -> World Default
 *   -> Global Default
 *   -> implicit DENY (fail-closed)
 * </pre>
 * Owner Guarantee never applies to rules (spec §27.2); the owner is bound by
 * environment rules like anyone else.
 *
 * <h3>COMBINED</h3>
 * The subject chain (without Owner Guarantee) and the rule chain are both required;
 * both must ALLOW for the combined decision to be ALLOW, any DENY on either side
 * yields DENY (spec §51-1.4).
 *
 * <p>Admin Bypass is a complete protection bypass: when enabled it short-circuits
 * every category to ALLOW at the very top (spec §27, §87). It is intentionally
 * off by default and supplied by the caller.
 *
 * <p>Compatibility: the original five-argument constructor is retained and maps to
 * the Land-level bindings/default (subland / world / global defaults are
 * {@code INHERIT}, {@code adminBypass=false}); {@link #subjectBindings()} is kept as
 * an alias for the Land-level bindings so earlier consumers keep working.
 *
 * <p>Thread-safe: binding lists are defensively copied on construction and the
 * exposed views are unmodifiable. The resolver performs no I/O, SQL, Bukkit,
 * Economy, or network access and keeps no global mutable cache.
 */
public record PermissionContext(
        ProtectionActionType action,
        boolean adminBypass,
        boolean isOwner,
        List<PermissionBinding> subLandBindings,
        PermissionState subLandDefault,
        List<PermissionBinding> landBindings,
        PermissionState landDefault,
        PermissionState worldDefault,
        PermissionState globalDefault,
        PermissionState subLandRule,
        PermissionState landRule) {

    public PermissionContext {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subLandBindings, "subLandBindings");
        Objects.requireNonNull(landBindings, "landBindings");
        Objects.requireNonNull(subLandDefault, "subLandDefault");
        Objects.requireNonNull(landDefault, "landDefault");
        Objects.requireNonNull(worldDefault, "worldDefault");
        Objects.requireNonNull(globalDefault, "globalDefault");
        Objects.requireNonNull(subLandRule, "subLandRule");
        Objects.requireNonNull(landRule, "landRule");
        // Defensive copy -> the context owns immutable, unmodifiable snapshots.
        subLandBindings = List.copyOf(subLandBindings);
        landBindings = List.copyOf(landBindings);
    }

    /**
     * Compatibility constructor (maps to Land-level bindings/default; subland / world /
     * global defaults are INHERIT and adminBypass is false). Retained so existing
     * five-argument callers and tests keep working.
     */
    public PermissionContext(
            ProtectionActionType action,
            boolean isOwner,
            List<PermissionBinding> subjectBindings,
            PermissionState landDefault,
            PermissionState landRule) {
        this(action, false, isOwner,
                List.of(), PermissionState.INHERIT,
                List.copyOf(subjectBindings), landDefault,
                PermissionState.INHERIT, PermissionState.INHERIT,
                PermissionState.INHERIT, landRule);
    }

    /** Unmodifiable view of the Land-level bindings (direct player + groups). */
    public List<PermissionBinding> subjectBindings() {
        return landBindings;
    }

    /** Start building a full-context decision input, labelled per §27 layer. */
    public static Builder builder(ProtectionActionType action) {
        return new Builder(action);
    }

    /** Fluent builder for {@link PermissionContext}; the produced context is immutable. */
    public static final class Builder {
        private final ProtectionActionType action;
        private boolean adminBypass = false;
        private boolean isOwner = false;
        private List<PermissionBinding> subLandBindings = List.of();
        private PermissionState subLandDefault = PermissionState.INHERIT;
        private List<PermissionBinding> landBindings = List.of();
        private PermissionState landDefault = PermissionState.INHERIT;
        private PermissionState worldDefault = PermissionState.INHERIT;
        private PermissionState globalDefault = PermissionState.INHERIT;
        private PermissionState subLandRule = PermissionState.INHERIT;
        private PermissionState landRule = PermissionState.INHERIT;

        private Builder(ProtectionActionType action) {
            this.action = Objects.requireNonNull(action, "action");
        }

        public Builder adminBypass(boolean value) {
            this.adminBypass = value;
            return this;
        }

        public Builder isOwner(boolean value) {
            this.isOwner = value;
            return this;
        }

        public Builder subLandBindings(List<PermissionBinding> value) {
            this.subLandBindings = List.copyOf(value);
            return this;
        }

        public Builder subLandDefault(PermissionState value) {
            this.subLandDefault = Objects.requireNonNull(value, "subLandDefault");
            return this;
        }

        public Builder landBindings(List<PermissionBinding> value) {
            this.landBindings = List.copyOf(value);
            return this;
        }

        public Builder landDefault(PermissionState value) {
            this.landDefault = Objects.requireNonNull(value, "landDefault");
            return this;
        }

        public Builder worldDefault(PermissionState value) {
            this.worldDefault = Objects.requireNonNull(value, "worldDefault");
            return this;
        }

        public Builder globalDefault(PermissionState value) {
            this.globalDefault = Objects.requireNonNull(value, "globalDefault");
            return this;
        }

        public Builder subLandRule(PermissionState value) {
            this.subLandRule = Objects.requireNonNull(value, "subLandRule");
            return this;
        }

        public Builder landRule(PermissionState value) {
            this.landRule = Objects.requireNonNull(value, "landRule");
            return this;
        }

        public PermissionContext build() {
            return new PermissionContext(action, adminBypass, isOwner,
                    subLandBindings, subLandDefault, landBindings, landDefault,
                    worldDefault, globalDefault, subLandRule, landRule);
        }
    }
}
