package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Public protection coverage table: every {@link ProtectionActionType} sits
 * in exactly one tier with a stated enforcement mechanism or deferral
 * reason.
 *
 * <ul>
 *   <li>{@code P0_ENFORCED}: the 21 actions intercepted before this
 *       milestone (13 in the early skeleton, 8 more for entry, vehicles,
 *       item frames, armor stands, hangings, farmland trampling, and fire).</li>
  *   <li>{@code P1_ENFORCED}: actions with a live enforcement path, including
  *       paths wired by earlier milestones (explosions, adjacent dispense)
  *       and the five paths added here (redstone use, generic entity
  *       interaction, mob griefing, hostile and passive natural spawns), plus
  *       the projectile-landing extension of the dispense crossing, plus the
  *       five management actions enforced by the shared domain gate (no Bukkit
  *       block listener; command and future GUI/Form entry points resolve the
  *       gate before touching the mutation pipeline).</li>
 *   <li>{@code DEFERRED}: directional cross actions with no dedicated Bukkit
 *       event. Their policy is already enforced indirectly: the piston,
 *       fluid, and hopper handlers judge both ends under the matching source
 *       action, which reads the same rule the directional action would read.
 *       Dedicated directional wiring stays a future milestone.</li>
 * </ul>
 */
public final class ProtectionCoverage {

    /** Which tier of the public list an action belongs to. */
    public enum Tier {
        P0_ENFORCED,
        P1_ENFORCED,
        DEFERRED
    }

    /** One row of the public list: the action, its tier, and how or why. */
    public record Coverage(ProtectionActionType action, Tier tier, String mechanism) {
        public Coverage {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(mechanism, "mechanism");
            if (mechanism.isBlank()) {
                throw new IllegalArgumentException("mechanism must not be blank");
            }
        }
    }

    private static final Map<ProtectionActionType, Coverage> TABLE = build();

    private ProtectionCoverage() {
    }

    /**
     * @return an unmodifiable view of the full coverage table (one row per
     *         action, no gaps, no duplicates)
     */
    public static Map<ProtectionActionType, Coverage> table() {
        return TABLE;
    }

    /** @return the single row for the action (never null). */
    public static Coverage coverageOf(ProtectionActionType action) {
        Objects.requireNonNull(action, "action");
        Coverage found = TABLE.get(action);
        if (found == null) {
            throw new IllegalStateException("Coverage table incomplete; missing: " + action);
        }
        return found;
    }

    private static void put(Map<ProtectionActionType, Coverage> table,
                            ProtectionActionType action, Tier tier, String mechanism) {
        table.put(action, new Coverage(action, tier, mechanism));
    }

    private static Map<ProtectionActionType, Coverage> build() {
        Map<ProtectionActionType, Coverage> table = new EnumMap<>(ProtectionActionType.class);
        // --- P0: the 21 previously intercepted actions ---
        put(table, ProtectionActionType.BLOCK_BREAK, Tier.P0_ENFORCED,
                "onBlockBreak (early skeleton)");
        put(table, ProtectionActionType.BLOCK_PLACE, Tier.P0_ENFORCED,
                "onBlockPlace (early skeleton)");
        put(table, ProtectionActionType.CONTAINER_OPEN, Tier.P0_ENFORCED,
                "onPlayerInteract right-click on storage blocks (early skeleton)");
        put(table, ProtectionActionType.WORKSTATION_USE, Tier.P0_ENFORCED,
                "onPlayerInteract right-click on crafting blocks (early skeleton)");
        put(table, ProtectionActionType.DOOR_USE, Tier.P0_ENFORCED,
                "onPlayerInteract right-click on doors and gates (early skeleton)");
        put(table, ProtectionActionType.BUTTON_USE, Tier.P0_ENFORCED,
                "onPlayerInteract right-click on buttons (early skeleton)");
        put(table, ProtectionActionType.LEVER_USE, Tier.P0_ENFORCED,
                "onPlayerInteract right-click on levers (early skeleton)");
        put(table, ProtectionActionType.BUCKET_USE, Tier.P0_ENFORCED,
                "onBucketFill/onBucketEmpty (early skeleton)");
        put(table, ProtectionActionType.ENTITY_DAMAGE, Tier.P0_ENFORCED,
                "onEntityDamage player harming non-player (early skeleton)");
        put(table, ProtectionActionType.PLAYER_DAMAGE_PLAYER, Tier.P0_ENFORCED,
                "onEntityDamage player harming player (early skeleton)");
        put(table, ProtectionActionType.PISTON_MOVE, Tier.P0_ENFORCED,
                "onPistonExtend/onPistonRetract at every moved block and destination");
        put(table, ProtectionActionType.FLUID_FLOW, Tier.P0_ENFORCED,
                "onFluidFlow at source and destination blocks");
        put(table, ProtectionActionType.HOPPER_TRANSFER, Tier.P0_ENFORCED,
                "onHopperTransfer at source and destination inventories");
        put(table, ProtectionActionType.ENTRY, Tier.P0_ENFORCED,
                "onPlayerMove at the destination (cross-chunk moves, plus in-chunk moves "
                        + "that cross a subland covering) plus onPlayerTeleport destination");
        put(table, ProtectionActionType.VEHICLE_USE, Tier.P0_ENFORCED,
                "onVehicleEnter plus onVehicleDamage by player");
        put(table, ProtectionActionType.ITEM_FRAME, Tier.P0_ENFORCED,
                "onPlayerInteractEntity plus hanging place/break routing");
        put(table, ProtectionActionType.ARMOR_STAND, Tier.P0_ENFORCED,
                "onPlayerInteractEntity plus onArmorStandManipulate and damage routing");
        put(table, ProtectionActionType.HANGING_ENTITY, Tier.P0_ENFORCED,
                "onHangingPlace/onHangingBreak for non-frame hangings");
        put(table, ProtectionActionType.FARMLAND_TRAMPLE, Tier.P0_ENFORCED,
                "onPlayerInteract PHYSICAL on soil plus onEntityInteract for mobs");
        put(table, ProtectionActionType.FIRE_SPREAD, Tier.P0_ENFORCED,
                "onFireSpread for flame spreading (grass and vines stay vanilla)");
        put(table, ProtectionActionType.FIRE_BURN, Tier.P0_ENFORCED,
                "onFireBurn at the burning block");
        // --- P1: live listener paths (earlier milestones plus this one) ---
        put(table, ProtectionActionType.EXPLOSION_TERRAIN, Tier.P1_ENFORCED,
                "onEntityExplode/onBlockExplode per-block filtering (cross-boundary milestone)");
        put(table, ProtectionActionType.EXPLOSION_ENTITY, Tier.P1_ENFORCED,
                "onExplosionEntityDamage per victim (cross-boundary milestone)");
        put(table, ProtectionActionType.DISPENSER_CROSS_BOUNDARY, Tier.P1_ENFORCED,
                "onDispenserDispense adjacent check (cross-boundary milestone) "
                        + "plus onProjectileHit landing check (P1 list)");
        put(table, ProtectionActionType.REDSTONE_USE, Tier.P1_ENFORCED,
                "onPlayerInteract right-click on redstone parts and PHYSICAL on "
                        + "pressure plates, plus onEntityInteract for mobs on plates (P1 list)");
        put(table, ProtectionActionType.ENTITY_INTERACT, Tier.P1_ENFORCED,
                "onPlayerInteractEntity for generic entities (P1 list)");
        put(table, ProtectionActionType.MOB_GRIEFING, Tier.P1_ENFORCED,
                "onEntityChangeBlock at the changed block (P1 list)");
        put(table, ProtectionActionType.HOSTILE_MOB_SPAWN, Tier.P1_ENFORCED,
                "onCreatureSpawn for natural spawns of monsters (P1 list)");
        put(table, ProtectionActionType.PASSIVE_MOB_SPAWN, Tier.P1_ENFORCED,
                "onCreatureSpawn for natural spawns of non-monsters (P1 list)");
        // --- P1: management actions enforced by the shared domain gate ---
        // No Bukkit block listener: command and future GUI/Form entry points
        // resolve ManagementPermissionGate before touching the mutation pipeline.
        put(table, ProtectionActionType.MANAGE_MEMBER, Tier.P1_ENFORCED,
                "ManagementPermissionGate domain gate shared by command/GUI/Form entry points");
        put(table, ProtectionActionType.MANAGE_PERMISSION, Tier.P1_ENFORCED,
                "ManagementPermissionGate domain gate shared by command/GUI/Form entry points");
        put(table, ProtectionActionType.MANAGE_SUBLAND, Tier.P1_ENFORCED,
                "ManagementPermissionGate domain gate shared by command/GUI/Form entry points");
        put(table, ProtectionActionType.EXPAND_LAND, Tier.P1_ENFORCED,
                "ManagementPermissionGate domain gate shared by command/GUI/Form entry points");
        put(table, ProtectionActionType.DELETE_LAND, Tier.P1_ENFORCED,
                "ManagementPermissionGate domain gate shared by command/GUI/Form entry points");
        // --- Deferred: directional cross actions without a dedicated event ---
        String indirect = "Deferred: no dedicated Bukkit event; enforced indirectly via ";
        put(table, ProtectionActionType.BLOCK_MOVE_IN, Tier.DEFERRED,
                indirect + "PISTON_MOVE, which the piston handler judges at both ends");
        put(table, ProtectionActionType.BLOCK_MOVE_OUT, Tier.DEFERRED,
                indirect + "PISTON_MOVE, which the piston handler judges at both ends");
        put(table, ProtectionActionType.FLUID_ENTER, Tier.DEFERRED,
                indirect + "FLUID_FLOW, which the fluid handler judges at both ends");
        put(table, ProtectionActionType.FLUID_EXIT, Tier.DEFERRED,
                indirect + "FLUID_FLOW, which the fluid handler judges at both ends");
        put(table, ProtectionActionType.ITEM_TRANSFER_IN, Tier.DEFERRED,
                indirect + "HOPPER_TRANSFER, which the hopper handler judges at both ends");
        put(table, ProtectionActionType.ITEM_TRANSFER_OUT, Tier.DEFERRED,
                indirect + "HOPPER_TRANSFER, which the hopper handler judges at both ends");
        if (table.size() != ProtectionActionType.values().length) {
            throw new IllegalStateException("Coverage table incomplete; listed " + table.size()
                    + " of " + ProtectionActionType.values().length + " actions");
        }
        return Collections.unmodifiableMap(table);
    }
}
