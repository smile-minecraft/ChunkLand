package com.smile.chunkland.api.permission;

import java.util.List;

/**
 * Pure, immutable, explainable Permission Resolver.
 *
 * <p>It applies the deterministic resolution chain for a single
 * {@link ProtectionActionType}, routing by the action's {@link DecisionSource}:
 *
 * <pre>
 * SUBJECT_PERMISSION : Admin Bypass -> Owner Guarantee -> SubLand binding (DENY-first)
 *                      -> SubLand Default -> Land binding -> Land Default
 *                      -> World Default -> Global Default -> implicit DENY
 * LAND_RULE          : Admin Bypass -> SubLand Rule -> Land Rule -> World Default
 *                      -> Global Default -> implicit DENY  (Owner Guarantee NEVER applies)
 * COMBINED           : subject chain (no Owner Guarantee) AND rule chain; both ALLOW -> ALLOW
 * </pre>
 *
 * <p>Key invariants enforced here:
 * <ul>
 *   <li><b>No EVERYONE binding.</b> Bindings are only ever {@code PLAYER} or
 *       {@code GROUP}; the resolver aggregates them uniformly, so a wildcard subject
 *       cannot exist.</li>
 *   <li><b>Action-scoped aggregation.</b> Each binding layer only counts bindings
 *       whose {@code action} equals the resolved action, so a binding for a different
 *       action can never leak into the decision.</li>
 *   <li><b>Direct ALLOW does not override Group DENY.</b> Direct player and group
 *       bindings share one aggregation layer with flat DENY-first precedence.</li>
 *   <li><b>Owner Guarantee is SUBJECT_PERMISSION-only.</b> It never rescues a
 *       {@code LAND_RULE} (or the subject half of a {@code COMBINED}) decision, so
 *       environment rules are never bypassed.</li>
 *   <li><b>Admin Bypass is a full bypass.</b> When enabled it short-circuits every
 *       category to ALLOW at the very top.</li>
 *   <li><b>Fail-closed default.</b> When every layer yields {@code INHERIT}, the
 *       decision is {@code DENY} (a missing value must not grant access).</li>
 * </ul>
 *
 * <p>The resolver is stateless and performs no I/O, SQL, Bukkit, Economy, or network
 * access, and keeps no global mutable cache. It is a pure function of its input.
 */
public final class PermissionResolver {

    /** Implicit decision when no layer produces an explicit value (fail-closed). */
    static final PermissionState IMPLICIT_DEFAULT = PermissionState.DENY;

    private PermissionResolver() {
    }

    /**
     * Resolve one decision for the given context. Admin Bypass, when enabled, is the
     * highest layer and short-circuits every category to ALLOW; otherwise the chain
     * is selected by the action's {@link DecisionSource}.
     */
    public static PermissionDecision resolve(PermissionContext ctx) {
        if (ctx.adminBypass()) {
            return new PermissionDecision(PermissionState.ALLOW, ctx.action().decisionSource(),
                    "Admin bypass: full protection bypass -> ALLOW");
        }
        return switch (ctx.action().decisionSource()) {
            case SUBJECT_PERMISSION -> resolveSubject(ctx, true);
            case LAND_RULE -> resolveRule(ctx);
            case COMBINED -> resolveCombined(ctx);
        };
    }

    /**
     * Subject chain: Owner Guarantee (optional) -> SubLand binding aggregate
     * -> SubLand Default -> Land binding aggregate -> Land Default -> World Default ->
     * Global Default -> implicit DENY. Each layer stops the chain on an explicit value.
     */
    static PermissionDecision resolveSubject(PermissionContext ctx, boolean applyOwnerGuarantee) {
        if (applyOwnerGuarantee && ctx.isOwner()) {
            return new PermissionDecision(PermissionState.ALLOW, DecisionSource.SUBJECT_PERMISSION,
                    "Owner guarantee: actor is owner of SUBJECT_PERMISSION action " + ctx.action() + " -> ALLOW");
        }
        var sub = aggregate(ctx, ctx.subLandBindings());
        if (sub != PermissionState.INHERIT) {
            return subjectLayer("SubLand binding aggregate", sub);
        }
        if (ctx.subLandDefault() != PermissionState.INHERIT) {
            return subjectLayer("SubLand default", ctx.subLandDefault());
        }
        var land = aggregate(ctx, ctx.landBindings());
        if (land != PermissionState.INHERIT) {
            return subjectLayer("Land binding aggregate", land);
        }
        if (ctx.landDefault() != PermissionState.INHERIT) {
            return subjectLayer("Land default", ctx.landDefault());
        }
        if (ctx.worldDefault() != PermissionState.INHERIT) {
            return subjectLayer("World default", ctx.worldDefault());
        }
        if (ctx.globalDefault() != PermissionState.INHERIT) {
            return subjectLayer("Global default", ctx.globalDefault());
        }
        return new PermissionDecision(IMPLICIT_DEFAULT, DecisionSource.SUBJECT_PERMISSION,
                "No explicit value at any subject layer; implicit default " + IMPLICIT_DEFAULT + " (fail-closed)");
    }

    /**
     * Rule chain: SubLand Rule -> Land Rule -> World Default -> Global Default
     * -> implicit DENY. Owner Guarantee is intentionally absent so environment rules
     * bind the owner too.
     */
    static PermissionDecision resolveRule(PermissionContext ctx) {
        if (ctx.subLandRule() != PermissionState.INHERIT) {
            return ruleLayer("SubLand rule", ctx.subLandRule());
        }
        if (ctx.landRule() != PermissionState.INHERIT) {
            return ruleLayer("Land rule", ctx.landRule());
        }
        if (ctx.worldDefault() != PermissionState.INHERIT) {
            return ruleLayer("World default", ctx.worldDefault());
        }
        if (ctx.globalDefault() != PermissionState.INHERIT) {
            return ruleLayer("Global default", ctx.globalDefault());
        }
        return new PermissionDecision(IMPLICIT_DEFAULT, DecisionSource.LAND_RULE,
                "No explicit value at any rule layer; implicit default " + IMPLICIT_DEFAULT + " (fail-closed)");
    }

    /**
     * COMBINED: the subject chain (without Owner Guarantee) and the rule
     * chain are both required; both must ALLOW for the combined decision to be ALLOW,
     * any DENY on either side yields DENY. A missing value resolves to the implicit
     * DENY, so COMBINED is DENY unless both sides are explicitly ALLOW. No action
     * currently uses COMBINED; the semantics are defined here for when one is added.
     */
    static PermissionDecision resolveCombined(PermissionContext ctx) {
        var subject = resolveSubject(ctx, false);
        var rule = resolveRule(ctx);
        var outcome = (subject.outcome() == PermissionState.ALLOW && rule.outcome() == PermissionState.ALLOW)
                ? PermissionState.ALLOW
                : PermissionState.DENY;
        return new PermissionDecision(outcome, DecisionSource.COMBINED,
                "COMBINED: subject=" + subject.outcome() + " rule=" + rule.outcome() + " -> " + outcome);
    }

    /**
     * Flat DENY-first aggregation across the bindings of one layer, counting only those
     * whose action matches the resolved action. Any DENY wins; otherwise
     * any ALLOW wins; otherwise INHERIT.
     */
    static PermissionState aggregate(PermissionContext ctx, List<PermissionBinding> bindings) {
        boolean anyAllow = false;
        for (var b : bindings) {
            if (b.permission().action() != ctx.action()) {
                continue; // action-scoped: never let a different action's binding leak in
            }
            var s = b.permission().state();
            if (s == PermissionState.DENY) {
                return PermissionState.DENY;
            }
            if (s == PermissionState.ALLOW) {
                anyAllow = true;
            }
        }
        return anyAllow ? PermissionState.ALLOW : PermissionState.INHERIT;
    }

    private static PermissionDecision subjectLayer(String label, PermissionState state) {
        return new PermissionDecision(state, DecisionSource.SUBJECT_PERMISSION,
                label + ": " + state + " -> " + state);
    }

    private static PermissionDecision ruleLayer(String label, PermissionState state) {
        return new PermissionDecision(state, DecisionSource.LAND_RULE,
                label + ": " + state + " -> " + state);
    }
}
