package com.smile.chunkland.command;

import java.util.Locale;
import java.util.Objects;

/**
 * Plain-text copy for the Bedrock selection Simple menu.
 *
 * <p>Bedrock forms take raw strings, not MiniMessage, so this copy lives
 * outside the message pipeline on purpose, exactly like the claim form
 * copy: rendering is a literal placeholder substitution. English is the
 * default; Traditional Chinese is served when the player's locale is a
 * Chinese variant.
 */
public record SelectionFormTexts(
        String title,
        String contentTemplate,
        String toggleButton,
        String addButton,
        String removeButton,
        String closeButton) {

    public SelectionFormTexts {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(contentTemplate, "contentTemplate");
        Objects.requireNonNull(toggleButton, "toggleButton");
        Objects.requireNonNull(addButton, "addButton");
        Objects.requireNonNull(removeButton, "removeButton");
        Objects.requireNonNull(closeButton, "closeButton");
        if (title.isBlank() || toggleButton.isBlank() || addButton.isBlank()
                || removeButton.isBlank() || closeButton.isBlank()) {
            throw new IllegalArgumentException("form title and buttons must not be blank");
        }
    }

    /** Resolve the copy table for a player locale; null or unknown locales fall back to English. */
    public static SelectionFormTexts forLocale(Locale locale) {
        if (locale != null && "zh".equalsIgnoreCase(locale.getLanguage())) {
            return new SelectionFormTexts("調整選區",
                    "模式：<mode>\n區塊數：<chunk_count>",
                    "切換模式", "加入腳下區塊", "移除腳下區塊", "關閉");
        }
        return new SelectionFormTexts("Adjust selection",
                "Mode: <mode>\nChunks: <chunk_count>",
                "Switch mode", "Add chunk here", "Remove chunk here", "Close");
    }

    /** Render the menu body for one live selection snapshot. */
    public String contentFor(boolean chunkEdit, int chunkCount) {
        Objects.requireNonNull(contentTemplate, "contentTemplate");
        return contentTemplate
                .replace("<mode>", chunkEdit ? "chunk" : "rectangle")
                .replace("<chunk_count>", Integer.toString(chunkCount));
    }
}
