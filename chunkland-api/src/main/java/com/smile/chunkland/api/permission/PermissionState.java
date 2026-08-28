package com.smile.chunkland.api.permission;

/**
 * Tri-state value of an ordinary permission (spec §24). A plain boolean is
 * explicitly forbidden; {@code INHERIT} means "fall through to the next
 * resolution level".
 */
public enum PermissionState {
    ALLOW,
    DENY,
    INHERIT
}
