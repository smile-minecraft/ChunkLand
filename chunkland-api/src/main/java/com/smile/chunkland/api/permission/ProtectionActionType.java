package com.smile.chunkland.api.permission;

import java.util.Objects;

/**
 * Every protection action ChunkLand can decide on.
 *
 * <p>Each constant declares the {@link DecisionSource} it resolves from. The set
 * below is the contract core. Additional actions added later extend this enum;
 * the contract that every constant declares a non-null {@link DecisionSource} is
 * fixed now.
 */
public enum ProtectionActionType {
    // --- SUBJECT_PERMISSION ---
    BLOCK_BREAK(DecisionSource.SUBJECT_PERMISSION),
    BLOCK_PLACE(DecisionSource.SUBJECT_PERMISSION),
    CONTAINER_OPEN(DecisionSource.SUBJECT_PERMISSION),
    WORKSTATION_USE(DecisionSource.SUBJECT_PERMISSION),
    DOOR_USE(DecisionSource.SUBJECT_PERMISSION),
    BUTTON_USE(DecisionSource.SUBJECT_PERMISSION),
    LEVER_USE(DecisionSource.SUBJECT_PERMISSION),
    REDSTONE_USE(DecisionSource.SUBJECT_PERMISSION),
    BUCKET_USE(DecisionSource.SUBJECT_PERMISSION),
    ENTRY(DecisionSource.SUBJECT_PERMISSION),
    VEHICLE_USE(DecisionSource.SUBJECT_PERMISSION),
    ENTITY_INTERACT(DecisionSource.SUBJECT_PERMISSION),
    ENTITY_DAMAGE(DecisionSource.SUBJECT_PERMISSION),
    ITEM_FRAME(DecisionSource.SUBJECT_PERMISSION),
    ARMOR_STAND(DecisionSource.SUBJECT_PERMISSION),
    HANGING_ENTITY(DecisionSource.SUBJECT_PERMISSION),
    FARMLAND_TRAMPLE(DecisionSource.SUBJECT_PERMISSION),

    // --- LAND_RULE ---
    PLAYER_DAMAGE_PLAYER(DecisionSource.LAND_RULE),
    PISTON_MOVE(DecisionSource.LAND_RULE),
    FLUID_FLOW(DecisionSource.LAND_RULE),
    HOPPER_TRANSFER(DecisionSource.LAND_RULE),
    FIRE_SPREAD(DecisionSource.LAND_RULE),
    FIRE_BURN(DecisionSource.LAND_RULE),
    EXPLOSION_TERRAIN(DecisionSource.LAND_RULE),
    EXPLOSION_ENTITY(DecisionSource.LAND_RULE),
    MOB_GRIEFING(DecisionSource.LAND_RULE),
    HOSTILE_MOB_SPAWN(DecisionSource.LAND_RULE),
    PASSIVE_MOB_SPAWN(DecisionSource.LAND_RULE),

    // --- Cross-boundary (all LAND_RULE) ---
    BLOCK_MOVE_IN(DecisionSource.LAND_RULE),
    BLOCK_MOVE_OUT(DecisionSource.LAND_RULE),
    FLUID_ENTER(DecisionSource.LAND_RULE),
    FLUID_EXIT(DecisionSource.LAND_RULE),
    ITEM_TRANSFER_IN(DecisionSource.LAND_RULE),
    ITEM_TRANSFER_OUT(DecisionSource.LAND_RULE),
    DISPENSER_CROSS_BOUNDARY(DecisionSource.LAND_RULE),

    // --- Management (all SUBJECT_PERMISSION) ---
    // Owner Guarantee applies, so the land owner always passes without
    // Admin Bypass; strangers fail closed unless explicitly authorised.
    MANAGE_MEMBER(DecisionSource.SUBJECT_PERMISSION),
    MANAGE_PERMISSION(DecisionSource.SUBJECT_PERMISSION),
    MANAGE_SUBLAND(DecisionSource.SUBJECT_PERMISSION),
    EXPAND_LAND(DecisionSource.SUBJECT_PERMISSION),
    DELETE_LAND(DecisionSource.SUBJECT_PERMISSION);

    private final DecisionSource decisionSource;

    ProtectionActionType(DecisionSource decisionSource) {
        this.decisionSource = Objects.requireNonNull(decisionSource, "decisionSource");
    }

    /** The {@link DecisionSource} this action resolves from. */
    public DecisionSource decisionSource() {
        return decisionSource;
    }
}
