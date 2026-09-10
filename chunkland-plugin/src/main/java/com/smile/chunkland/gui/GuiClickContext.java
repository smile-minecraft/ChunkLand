package com.smile.chunkland.gui;

import java.util.Objects;
import java.util.UUID;

/**
 * Validated click delivered to a {@link GuiAction}. The generation is the one
 * the upstream session authority issued at open time; the navigator only
 * builds this context after {@code validateClick} accepted the click and the
 * tracked top frame still carries the same generation.
 *
 * @param playerUuid clicked player; never {@code null}
 * @param generation upstream session generation the click was validated against
 * @param slot clicked slot
 * @param pageId owning page; never {@code null} or empty
 */
public record GuiClickContext(UUID playerUuid, long generation, int slot, String pageId) {

    public GuiClickContext {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(pageId, "pageId");
        if (pageId.isEmpty()) {
            throw new IllegalArgumentException("pageId must not be empty");
        }
    }
}
