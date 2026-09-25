package com.smile.chunkland.gui;

import com.smile.chunkland.api.permission.PermissionState;
import java.util.Objects;

/**
 * Land-default toggle target for one action.
 *
 * <p>The mapping is deliberately safety-first: an explicit {@code DENY}
 * goes {@code ALLOW}, and anything else — {@code ALLOW} or unset
 * ({@code INHERIT}) — goes {@code DENY}. The confirm page shows both ends
 * of the transition before anything is written.
 */
public final class ManagementGuiToggle {

    private ManagementGuiToggle() {
    }

    /**
     * Toggle target for the current land default.
     *
     * @param current current land default; never {@code null}
     *     ({@code INHERIT} means unset)
     * @return the state a confirmed toggle must write; never {@code null}
     */
    public static PermissionState targetFor(PermissionState current) {
        Objects.requireNonNull(current, "current");
        return current == PermissionState.DENY ? PermissionState.ALLOW : PermissionState.DENY;
    }
}
