package com.smile.chunkland.api.permission;

/**
 * Where a protection decision is resolved from (spec §51-1). Every
 * {@link ProtectionActionType} must declare exactly one of these.
 */
public enum DecisionSource {
    /** "Does this player have the right to do this?" — ignores environment rules. */
    SUBJECT_PERMISSION,
    /** "Can this world mechanic happen here?" — ignores the player's ordinary ACL. */
    LAND_RULE,
    /** Requires both a permission and a rule check (spec §51-1.4). */
    COMBINED
}
