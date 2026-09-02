package com.smile.chunkland.selection;

/** Stable reason vocabulary for clearing or invalidating a Selection Session. */
public enum SelectionEndReason {
    PLAYER_QUIT,
    CANCELLED,
    WORLD_CHANGED,
    PLUGIN_DISABLED,
    CONFIG_CHANGED,
    TIMEOUT,
    LAND_DELETED,
    LAND_STRUCTURE_CHANGED,
    SUBLAND_DELETED,
    REPLACED
}
