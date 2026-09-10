package com.smile.chunkland.protection;

/**
 * Structural layer that decided one permission outcome.
 *
 * <p>The values mirror the resolver chain without parsing its wording:
 * gate-level short-circuits first, then the SubLand layers ahead of the
 * land chain, then the shared world/global layers, then the rule layers,
 * and finally the fail-closed implicit default.
 */
public enum PermissionExplainLayer {
    /** Admin bypass short-circuit (full protection bypass). */
    BYPASS,
    /** Server-land steward grant (owner-equivalent, management gate only). */
    STEWARD,
    /** Owner guarantee for subject-permission actions. */
    GUARANTEE,
    /** Aggregated direct-player and group bindings on the covering subland. */
    SUBLAND_BINDING,
    /** Default of the covering subland. */
    SUBLAND_DEFAULT,
    /** Aggregated direct-player and group bindings on the land. */
    LAND_BINDING,
    /** Default of the land. */
    LAND_DEFAULT,
    /** Shared subject default of the world. */
    WORLD_DEFAULT,
    /** Shared subject default of the whole server. */
    GLOBAL_DEFAULT,
    /** Rule of the covering subland. */
    SUBLAND_RULE,
    /** Effective rule of the land (already folds rule world/global). */
    LAND_RULE,
    /** No layer produced a value; the resolver denied fail-closed. */
    IMPLICIT_DENY
}
