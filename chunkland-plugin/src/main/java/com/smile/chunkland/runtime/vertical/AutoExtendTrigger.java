package com.smile.chunkland.runtime.vertical;

/**
 * Closed taxonomy of operation kinds for the Auto Extend trigger rule
 * (spec §18).
 *
 * <p>Only {@link #BLOCK_BREAK} and {@link #BLOCK_PLACE} may trigger an
 * extend, and only when the decision gate additionally confirms an
 * authorized actor, land membership and an operation plane below the
 * effective protection depth. Every other kind is silent by construction:
 *
 * <ul>
 *   <li>{@code MOVE_OR_TELEPORT} — 玩家單純移動或傳送
 *   <li>{@code MOB_ACTIVITY} — 怪物活動
 *   <li>{@code EXPLOSION} — 爆炸
 *   <li>{@code FLUID_FLOW} — 水流 / 岩漿流
 *   <li>{@code FIRE} — 火焰
 *   <li>{@code FALLING_OR_DROPPED} — 掉落物
 *   <li>{@code PROJECTILE} — projectile
 *   <li>{@code PISTON} — 活塞推動
 *   <li>{@code UNAUTHORIZED_ATTEMPT} — 無權限玩家的失敗操作
 * </ul>
 *
 * <p>Pure classification: no Bukkit, SQL or I/O. Callers map platform
 * events to these values; unknown future event types must map to a silent
 * kind so automation can never extend protection by default.
 */
public enum AutoExtendTrigger {
    BLOCK_BREAK,
    BLOCK_PLACE,
    MOVE_OR_TELEPORT,
    MOB_ACTIVITY,
    EXPLOSION,
    FLUID_FLOW,
    FIRE,
    FALLING_OR_DROPPED,
    PROJECTILE,
    PISTON,
    UNAUTHORIZED_ATTEMPT;

    /** True only for the two legal development operations. */
    public boolean triggersExtend() {
        return this == BLOCK_BREAK || this == BLOCK_PLACE;
    }
}
