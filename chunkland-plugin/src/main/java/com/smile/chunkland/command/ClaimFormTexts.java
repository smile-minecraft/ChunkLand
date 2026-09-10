package com.smile.chunkland.command;

import java.util.Locale;
import java.util.Objects;

/**
 * Plain-text copy for the Bedrock claim Modal Form.
 *
 * <p>Bedrock forms take raw strings, not MiniMessage, so this copy lives
 * outside the message pipeline on purpose: rendering is a literal
 * placeholder substitution and the land name is never parsed as markup.
 * English is the default; Traditional Chinese is served when the player's
 * locale is a Chinese variant. Locale-aware pipeline copy remains a
 * follow-up once a plain-text lang lookup exists.
 */
public record ClaimFormTexts(
        String title,
        String bodyTemplate,
        String confirmButton,
        String cancelButton,
        String unavailableText) {

    public ClaimFormTexts {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(bodyTemplate, "bodyTemplate");
        Objects.requireNonNull(confirmButton, "confirmButton");
        Objects.requireNonNull(cancelButton, "cancelButton");
        Objects.requireNonNull(unavailableText, "unavailableText");
        if (title.isBlank() || confirmButton.isBlank() || cancelButton.isBlank()) {
            throw new IllegalArgumentException("form title and buttons must not be blank");
        }
    }

    /** Resolve the copy table for a player locale; null or unknown locales fall back to English. */
    public static ClaimFormTexts forLocale(Locale locale) {
        if (locale != null && "zh".equalsIgnoreCase(locale.getLanguage())) {
            return new ClaimFormTexts("確認圈地",
                    "領地：<land_name>\n區塊：<chunk_count>\n確認碼：<generation>/<revision>\n"
                            + "花費：<price>\n上限：<limit>\n最低高度：<min_y>\n退費：<refund>",
                    "確認", "取消", "暫無資料");
        }
        return new ClaimFormTexts("Confirm land claim",
                "Land: <land_name>\nChunks: <chunk_count>\nConfirm code: <generation>/<revision>\n"
                        + "Price: <price>\nLimit: <limit>\nLowest height: <min_y>\nRefund: <refund>",
                "Confirm", "Cancel", "unavailable");
    }

    /**
     * Render the body for one preview with literal substitution. Missing
     * priced fields render as the unavailable marker, never as zero.
     */
    public String bodyFor(ClaimPreview preview) {
        Objects.requireNonNull(preview, "preview");
        return bodyTemplate
                .replace("<land_name>", preview.landName())
                .replace("<chunk_count>", Integer.toString(preview.chunkCount()))
                .replace("<generation>", Long.toString(preview.sessionGeneration()))
                .replace("<revision>", Long.toString(preview.selectionRevision()))
                .replace("<price>", preview.price().orElse(unavailableText))
                .replace("<limit>", preview.limit().orElse(unavailableText))
                .replace("<min_y>", preview.lowestHeight().orElse(unavailableText))
                .replace("<refund>", preview.refund().orElse(unavailableText));
    }
}
