package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

/**
 * Scope regression: the confirm-click rewrite must only stamp the explicit
 * confirm command. Other clicks that happen to carry {@code <revision>} or
 * {@code <land_name>} placeholders must never be rewritten.
 */
class ConfirmClickScopeRegressionTest {

    private static final Map<String, Object> VARS = Map.of(
            "land_name", "Home",
            "generation", "0",
            "revision", "7");

    @Test
    void nonConfirmRevisionClickIsNotRewritten() {
        Component rendered = Component.text("info")
                .clickEvent(ClickEvent.runCommand("/land info <revision>"));
        assertThrows(MessageException.class,
                () -> ConfirmClick.rewriteConfirmClick(rendered, VARS),
                "a non-confirm click carrying <revision> must never be stamped");
    }

    @Test
    void nonConfirmLandNameClickIsNotRewritten() {
        Component rendered = Component.text("rename")
                .clickEvent(ClickEvent.runCommand("/land rename <land_name>"));
        assertThrows(MessageException.class,
                () -> ConfirmClick.rewriteConfirmClick(rendered, VARS),
                "a non-confirm click carrying <land_name> must never be stamped");
    }

    @Test
    void nonConfirmKeyPassesThroughUntouched() {
        Component rendered = Component.text("[Confirm]")
                .clickEvent(ClickEvent.runCommand("/land confirm <generation> <revision> <land_name>"));
        Component same = ConfirmClick.rewriteConfirmClick(rendered, VARS, "land.claim.success");
        assertSame(rendered, same, "non-confirm keys must pass through without any rewrite");
        Component nullKey = ConfirmClick.rewriteConfirmClick(rendered, VARS, null);
        assertSame(rendered, nullKey, "null key must pass through without any rewrite");
    }

    @Test
    void explicitConfirmKeysAreRewritten() {
        for (String key : new String[]{"land.claim.confirm", "command.land.claim.confirm"}) {
            assertTrue(ConfirmClick.isConfirmKey(key), key + " must be a confirm key");
            Component rendered = Component.text("[Confirm]")
                    .clickEvent(ClickEvent.runCommand("/land confirm <generation> <revision> <land_name>"));
            Component stamped = ConfirmClick.rewriteConfirmClick(rendered, VARS, key);
            assertEquals("/land confirm 0 7 Home", stamped.clickEvent().value(),
                    key + " must stamp the confirm click");
        }
        assertFalse(ConfirmClick.isConfirmKey("land.claim.success"));
    }

    @Test
    void multiClickKeepsNonTargetClickUntouched() {
        Component confirm = Component.text("[Confirm]")
                .clickEvent(ClickEvent.runCommand("/land confirm <generation> <revision> <land_name>"));
        Component other = Component.text("[Info]")
                .clickEvent(ClickEvent.runCommand("/land info <revision>"));
        Component rendered = Component.text().append(confirm).append(other).build();
        Component stamped = ConfirmClick.rewriteConfirmClick(rendered, VARS);
        assertEquals(2, stamped.children().size());
        assertEquals("/land confirm 0 7 Home",
                stamped.children().get(0).clickEvent().value(),
                "the confirm click must be stamped");
        assertEquals("/land info <revision>",
                stamped.children().get(1).clickEvent().value(),
                "the non-target click must stay untouched");
    }
}
