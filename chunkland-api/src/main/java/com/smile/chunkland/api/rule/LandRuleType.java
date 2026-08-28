package com.smile.chunkland.api.rule;

/**
 * Environment rule types a Land may enforce (spec §55). Unlike
 * {@link com.smile.chunkland.api.permission.ProtectionActionType}, these have no
 * subject dimension — they answer "can this world mechanic happen here?" rather
 * than "may this player act?".
 */
public enum LandRuleType {
    PVP,
    EXPLOSION_TERRAIN,
    EXPLOSION_ENTITY,
    FIRE_SPREAD,
    FIRE_BURN,
    MOB_GRIEFING,
    FLUID_FLOW,
    PISTON,
    HOPPER_TRANSFER,
    HOSTILE_MOB_SPAWN,
    PASSIVE_MOB_SPAWN
}
