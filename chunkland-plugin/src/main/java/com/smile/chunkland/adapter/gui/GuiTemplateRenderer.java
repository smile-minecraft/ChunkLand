package com.smile.chunkland.adapter.gui;

import java.util.Locale;
import java.util.Map;

/**
 * Renders one language template to plain display text for GUI injection.
 *
 * <p>Production resolves through the message pipeline (which owns strict
 * MiniMessage validation) and strips formatting to plain text, because
 * the GUI adapter paints item names and lore literally. The lookup never
 * touches Bukkit state: locale comes from the caller's snapshot, never
 * from a live player read.</p>
 */
public interface GuiTemplateRenderer {

    /**
     * @param key language key; never {@code null}
     * @param vars placeholder values; never {@code null}
     * @param locale preferred locale, or {@code null} for the default chain
     * @return plain display text with no MiniMessage tags; never {@code null}
     */
    String renderPlain(String key, Map<String, Object> vars, Locale locale);
}
