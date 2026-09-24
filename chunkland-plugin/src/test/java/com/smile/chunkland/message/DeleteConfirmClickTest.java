package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Delete confirmation click: {@code command.land.delete.confirm} must stamp a
 * {@code suggest_command} click carrying the validated revision, so a single
 * misclick can never delete directly. Invalid revisions fail closed.
 */
class DeleteConfirmClickTest {

    private static final String DELETE_KEY = "command.land.delete.confirm";

    @Test
    void deleteConfirmKeyIsRecognised() {
        assertTrue(ConfirmClick.isConfirmKey(DELETE_KEY),
                "command.land.delete.confirm must be a confirm key");
        assertFalse(ConfirmClick.isConfirmKey("command.land.delete.failed"),
                "non-confirm delete keys must stay out of the rewrite");
    }

    @Test
    void deleteRewriteStampsSuggestCommand() {
        Component rendered = Component.text("[Fill]")
                .clickEvent(ClickEvent.suggestCommand("/land delete confirm <revision>"));
        Component stamped = ConfirmClick.rewriteConfirmClick(
                rendered, Map.of("revision", 7L), DELETE_KEY);
        assertNotNull(stamped.clickEvent(), "delete confirm must keep its click");
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, stamped.clickEvent().action(),
                "delete must stay suggest_command, never run_command");
        assertEquals("/land delete confirm 7", stamped.clickEvent().value());
    }

    @Test
    void deleteRewriteAcceptsStringRevision() {
        Component rendered = Component.text("[Fill]")
                .clickEvent(ClickEvent.suggestCommand("/land delete confirm <revision>"));
        Component stamped = ConfirmClick.rewriteConfirmClick(
                rendered, Map.of("revision", "7"), DELETE_KEY);
        assertEquals("/land delete confirm 7", stamped.clickEvent().value());
    }

    @Test
    void deleteRewriteRejectsBadRevisions() {
        Component rendered = Component.text("[Fill]")
                .clickEvent(ClickEvent.suggestCommand("/land delete confirm <revision>"));
        assertThrows(MessageException.class,
                () -> ConfirmClick.rewriteConfirmClick(rendered, Map.of(), DELETE_KEY),
                "missing revision must fail closed");
        assertThrows(MessageException.class,
                () -> ConfirmClick.rewriteConfirmClick(rendered, Map.of("revision", "abc"), DELETE_KEY),
                "non-numeric revision must fail closed");
        assertThrows(MessageException.class,
                () -> ConfirmClick.rewriteConfirmClick(rendered, Map.of("revision", "-1"), DELETE_KEY),
                "negative revision must fail closed");
        assertThrows(MessageException.class,
                () -> ConfirmClick.rewriteConfirmClick(rendered, null, DELETE_KEY),
                "null vars must fail closed");
    }

    @Test
    void deleteTemplateWithoutClickFailsClosed() {
        Component rendered = Component.text("plain body, no click");
        assertThrows(MessageException.class,
                () -> ConfirmClick.rewriteConfirmClick(rendered, Map.of("revision", "7"), DELETE_KEY),
                "a delete confirm template without a click must fail closed");
    }

    @Test
    void nonConfirmDeleteKeysPassThroughUntouched() {
        Component rendered = Component.text("[Fill]")
                .clickEvent(ClickEvent.suggestCommand("/land delete confirm <revision>"));
        Component same = ConfirmClick.rewriteConfirmClick(
                rendered, Map.of("revision", "7"), "command.land.delete.failed");
        assertSame(rendered, same, "non-confirm keys must pass through without any rewrite");
        Component nullKey = ConfirmClick.rewriteConfirmClick(
                rendered, Map.of("revision", "7"), null);
        assertSame(rendered, nullKey, "null key must pass through without any rewrite");
    }

    @Test
    void claimStampingKeepsRunCommand() {
        Component rendered = Component.text("[Confirm]")
                .clickEvent(ClickEvent.runCommand("/land confirm <generation> <revision> <land_name>"));
        Component stamped = ConfirmClick.rewriteConfirmClick(rendered,
                Map.of("land_name", "Home", "generation", "0", "revision", "7"),
                "land.claim.confirm");
        assertEquals(ClickEvent.Action.RUN_COMMAND, stamped.clickEvent().action(),
                "claim must stay run_command");
        assertEquals("/land confirm 0 7 Home", stamped.clickEvent().value());
    }

    @Test
    void deleteLangTemplatesPassStrictValidationAndStamp() throws Exception {
        for (String localeTag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
            String raw = cfg.getString("command.land.delete.confirm");
            assertNotNull(raw, localeTag + " command.land.delete.confirm");
            assertDoesNotThrow(
                    () -> ChunkLandMessagePipeline.validateTemplateStrict(
                            "command.land.delete.confirm", raw),
                    localeTag + " delete confirm template must pass strict validation");
            Component parsed = MiniMessage.miniMessage().deserialize(raw,
                    Placeholder.unparsed("revision", "7"));
            Component stamped = ConfirmClick.rewriteConfirmClick(
                    parsed, Map.of("revision", "7"), DELETE_KEY);
            String click = findClickValue(stamped);
            assertNotNull(click, localeTag + " delete confirm must keep a click");
            assertEquals("/land delete confirm 7", click,
                    localeTag + " click must carry the executable revision");
            assertEquals(ClickEvent.Action.SUGGEST_COMMAND, findClickAction(stamped),
                    localeTag + " click must stay suggest_command");
        }
    }

    private static String findClickValue(Component root) {
        if (root.clickEvent() != null) {
            return root.clickEvent().value();
        }
        for (Component child : root.children()) {
            String nested = findClickValue(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static ClickEvent.Action findClickAction(Component root) {
        if (root.clickEvent() != null) {
            return root.clickEvent().action();
        }
        for (Component child : root.children()) {
            ClickEvent.Action nested = findClickAction(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
