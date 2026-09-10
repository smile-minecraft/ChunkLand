package com.smile.chunkland.gui;

import java.util.Objects;

/**
 * Immutable binding between one inventory slot and its {@link GuiAction}.
 *
 * @param slot zero-based inventory slot; never negative
 * @param action callback run after upstream click validation; never {@code null}
 */
public record GuiButton(int slot, GuiAction action) {

    public GuiButton {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must not be negative: " + slot);
        }
        Objects.requireNonNull(action, "action");
    }
}
