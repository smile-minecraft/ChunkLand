package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionState;
import java.util.List;
import java.util.Objects;

/**
 * Read-only classifier that turns one resolver decision back into the
 * structural layer that produced it.
 *
 * <p>The layer follows the context structure in resolver order — never the
 * human-readable explanation wording, which is allowed to change. Gate-level
 * short-circuits (admin bypass, server-land steward) are reported as their
 * own layers instead of being dressed up as binding layers. The service
 * performs no I/O, SQL, Bukkit, Economy or network access and keeps no
 * cache: callers resolve a fresh context through the shared provider and
 * classify it here.
 */
public final class PermissionExplainService {

    private PermissionExplainService() {
    }

    /**
     * Classifies one decision against the context that produced it.
     *
     * @param ctx the same immutable context the resolver decided on
     * @param decision the resolver outcome and source for that context
     * @param covering the subland covering the explained position, or
     *                 {@code null} outside every subland
     * @param steward whether the caller holds the server-land steward grant
     * @param serverLand whether the target land is a Server Land
     * @return the structured explain; never {@code null}
     */
    public static PermissionExplain explain(PermissionContext ctx,
            PermissionDecision decision, SubLandId covering,
            boolean steward, boolean serverLand) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(decision, "decision");
        boolean stewardGrant = steward && serverLand;
        PermissionExplainLayer layer;
        if (ctx.adminBypass()) {
            layer = PermissionExplainLayer.BYPASS;
        } else if (stewardGrant) {
            layer = PermissionExplainLayer.STEWARD;
        } else {
            layer = switch (decision.source()) {
                case SUBJECT_PERMISSION -> subjectLayer(ctx);
                case LAND_RULE -> ruleLayer(ctx);
                case COMBINED -> combinedLayer(ctx);
            };
        }
        String coveringId = covering == null ? null : covering.value().toString();
        return new PermissionExplain(ctx.action(), decision.outcome(), decision.source(),
                layer, reasonFor(layer, decision, covering != null),
                coveringId, ctx.isOwner(), ctx.adminBypass(), stewardGrant);
    }

    /**
     * Subject chain in resolver order: owner guarantee, then the subland
     * layers ahead of the land chain, then the shared defaults.
     */
    static PermissionExplainLayer subjectLayer(PermissionContext ctx) {
        if (ctx.isOwner()) {
            return PermissionExplainLayer.GUARANTEE;
        }
        if (aggregate(ctx, ctx.subLandBindings()) != PermissionState.INHERIT) {
            return PermissionExplainLayer.SUBLAND_BINDING;
        }
        if (ctx.subLandDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.SUBLAND_DEFAULT;
        }
        if (aggregate(ctx, ctx.landBindings()) != PermissionState.INHERIT) {
            return PermissionExplainLayer.LAND_BINDING;
        }
        if (ctx.landDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.LAND_DEFAULT;
        }
        if (ctx.worldDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.WORLD_DEFAULT;
        }
        if (ctx.globalDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.GLOBAL_DEFAULT;
        }
        return PermissionExplainLayer.IMPLICIT_DENY;
    }

    /**
     * Rule chain in resolver order: subland rule, land rule (which already
     * folds the rule world/global defaults), then the shared layers, then
     * the fail-closed default.
     */
    static PermissionExplainLayer ruleLayer(PermissionContext ctx) {
        if (ctx.subLandRule() != PermissionState.INHERIT) {
            return PermissionExplainLayer.SUBLAND_RULE;
        }
        if (ctx.landRule() != PermissionState.INHERIT) {
            return PermissionExplainLayer.LAND_RULE;
        }
        if (ctx.worldDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.WORLD_DEFAULT;
        }
        if (ctx.globalDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.GLOBAL_DEFAULT;
        }
        return PermissionExplainLayer.IMPLICIT_DENY;
    }

    /**
     * Combined chain without the owner guarantee: the subject half and the
     * rule half must both allow. A denial reports the half that denied;
     * an allow reports the subject half.
     */
    static PermissionExplainLayer combinedLayer(PermissionContext ctx) {
        PermissionState subject = subjectOutcome(ctx);
        if (subject != PermissionState.ALLOW) {
            return subjectLayerNoGuarantee(ctx);
        }
        PermissionState rule = ruleOutcome(ctx);
        if (rule != PermissionState.ALLOW) {
            return ruleLayer(ctx);
        }
        return subjectLayerNoGuarantee(ctx);
    }

    private static PermissionState subjectOutcome(PermissionContext ctx) {
        PermissionState sub = aggregate(ctx, ctx.subLandBindings());
        if (sub != PermissionState.INHERIT) {
            return sub;
        }
        if (ctx.subLandDefault() != PermissionState.INHERIT) {
            return ctx.subLandDefault();
        }
        PermissionState land = aggregate(ctx, ctx.landBindings());
        if (land != PermissionState.INHERIT) {
            return land;
        }
        if (ctx.landDefault() != PermissionState.INHERIT) {
            return ctx.landDefault();
        }
        if (ctx.worldDefault() != PermissionState.INHERIT) {
            return ctx.worldDefault();
        }
        if (ctx.globalDefault() != PermissionState.INHERIT) {
            return ctx.globalDefault();
        }
        return PermissionState.DENY;
    }

    private static PermissionExplainLayer subjectLayerNoGuarantee(PermissionContext ctx) {
        if (aggregate(ctx, ctx.subLandBindings()) != PermissionState.INHERIT) {
            return PermissionExplainLayer.SUBLAND_BINDING;
        }
        if (ctx.subLandDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.SUBLAND_DEFAULT;
        }
        if (aggregate(ctx, ctx.landBindings()) != PermissionState.INHERIT) {
            return PermissionExplainLayer.LAND_BINDING;
        }
        if (ctx.landDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.LAND_DEFAULT;
        }
        if (ctx.worldDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.WORLD_DEFAULT;
        }
        if (ctx.globalDefault() != PermissionState.INHERIT) {
            return PermissionExplainLayer.GLOBAL_DEFAULT;
        }
        return PermissionExplainLayer.IMPLICIT_DENY;
    }

    private static PermissionState ruleOutcome(PermissionContext ctx) {
        if (ctx.subLandRule() != PermissionState.INHERIT) {
            return ctx.subLandRule();
        }
        if (ctx.landRule() != PermissionState.INHERIT) {
            return ctx.landRule();
        }
        if (ctx.worldDefault() != PermissionState.INHERIT) {
            return ctx.worldDefault();
        }
        if (ctx.globalDefault() != PermissionState.INHERIT) {
            return ctx.globalDefault();
        }
        return PermissionState.DENY;
    }

    /**
     * Flat DENY-first aggregation over one binding layer, counting only the
     * bindings scoped to the resolved action — the same precedence the
     * resolver applies, re-expressed here so the classifier never parses
     * the resolver wording.
     */
    private static PermissionState aggregate(PermissionContext ctx,
            List<PermissionBinding> bindings) {
        boolean anyAllow = false;
        for (PermissionBinding binding : bindings) {
            if (binding == null || binding.permission() == null) {
                continue;
            }
            if (binding.permission().action() != ctx.action()) {
                continue;
            }
            PermissionState state = binding.permission().state();
            if (state == PermissionState.DENY) {
                return PermissionState.DENY;
            }
            if (state == PermissionState.ALLOW) {
                anyAllow = true;
            }
        }
        return anyAllow ? PermissionState.ALLOW : PermissionState.INHERIT;
    }

    private static String reasonFor(PermissionExplainLayer layer,
            PermissionDecision decision, boolean covering) {
        String where = covering ? " (covering subland present)" : " (no covering subland)";
        return switch (layer) {
            case BYPASS -> "Admin bypass: full protection bypass -> " + decision.outcome();
            case STEWARD -> "Server-land steward: owner-equivalent grant -> "
                    + decision.outcome();
            case GUARANTEE -> "Owner guarantee: actor is owner -> " + decision.outcome();
            case SUBLAND_BINDING -> "SubLand binding aggregate decides -> "
                    + decision.outcome() + where;
            case SUBLAND_DEFAULT -> "SubLand default decides -> "
                    + decision.outcome() + where;
            case LAND_BINDING -> "Land binding aggregate decides -> "
                    + decision.outcome() + where;
            case LAND_DEFAULT -> "Land default decides -> " + decision.outcome() + where;
            case WORLD_DEFAULT -> "World default decides -> " + decision.outcome() + where;
            case GLOBAL_DEFAULT -> "Global default decides -> " + decision.outcome() + where;
            case SUBLAND_RULE -> "SubLand rule decides -> " + decision.outcome() + where;
            case LAND_RULE -> "Land rule decides -> " + decision.outcome() + where;
            case IMPLICIT_DENY -> "No explicit value at any layer; implicit default "
                    + decision.outcome() + " (fail-closed)" + where;
        };
    }
}
