package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.permission.PermissionState;
import org.junit.jupiter.api.Test;

/** The land-default toggle target: DENY goes ALLOW, everything else goes DENY. */
class ManagementGuiToggleTest {

    @Test
    void denyTargetsAllow() {
        assertEquals(PermissionState.ALLOW,
                ManagementGuiToggle.targetFor(PermissionState.DENY));
    }

    @Test
    void allowTargetsDeny() {
        assertEquals(PermissionState.DENY,
                ManagementGuiToggle.targetFor(PermissionState.ALLOW));
    }

    @Test
    void unsetTargetsDeny() {
        assertEquals(PermissionState.DENY,
                ManagementGuiToggle.targetFor(PermissionState.INHERIT),
                "unset (INHERIT) must toggle to DENY, never guess ALLOW");
    }

    @Test
    void nullCurrentIsRejected() {
        assertThrows(NullPointerException.class,
                () -> ManagementGuiToggle.targetFor(null));
    }
}
