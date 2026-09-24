package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.event.ClickEvent;

import org.junit.jupiter.api.Test;

/**
 * Click payload reader shapes: Adventure 4 ({@code value()}), Adventure 5
 * ({@code payload()} whose result carries {@code value()}), and unknown
 * shapes (neither method) which must yield null without throwing.
 */
class AdventureClickPayloadTest {

    /** Mimics the Adventure 4 {@code ClickEvent} shape. */
    static final class ValueShape {
        private final String value;

        ValueShape(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** Mimics the Adventure 5 {@code ClickEvent} shape. */
    static final class PayloadShape {
        private final TextPayload payload;

        PayloadShape(String value) {
            this.payload = value == null ? null : new TextPayload(value);
        }

        public TextPayload payload() {
            return payload;
        }
    }

    /** Mimics the Adventure 5 {@code ClickEvent.Payload.Text} shape. */
    static final class TextPayload {
        private final String value;

        TextPayload(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** Payload without a text accessor (non-text payload shape). */
    static final class OpaquePayloadShape {
        public Object payload() {
            return new Object();
        }
    }

    /** Unknown shape: neither accessor exists. */
    static final class UnknownShape {
    }

    @Test
    void valueShapeReturnsOriginalString() {
        assertEquals("/land confirm 1 2 home",
                AdventureClickPayload.read(new ValueShape("/land confirm 1 2 home")));
    }

    @Test
    void valueShapeWithNullReturnsNull() {
        assertNull(AdventureClickPayload.read(new ValueShape(null)));
    }

    @Test
    void payloadShapeReturnsInnerString() {
        assertEquals("/land delete confirm 7",
                AdventureClickPayload.read(new PayloadShape("/land delete confirm 7")));
    }

    @Test
    void payloadShapeWithNullPayloadReturnsNull() {
        assertNull(AdventureClickPayload.read(new PayloadShape(null)));
    }

    @Test
    void nonTextPayloadReturnsNullWithoutThrowing() {
        assertNull(AdventureClickPayload.read(new OpaquePayloadShape()));
    }

    @Test
    void unknownShapeReturnsNullWithoutThrowing() {
        assertNull(AdventureClickPayload.read(new UnknownShape()));
    }

    @Test
    void nullClickReturnsNullWithoutThrowing() {
        assertNull(AdventureClickPayload.read(null));
    }

    /** Mimics an Adventure action object carrying a name. */
    static final class NamedAction {
        private final String name;

        NamedAction(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /** Mimics a click event whose action object carries a name. */
    static final class ActionShape {
        private final Object action;

        ActionShape(Object action) {
            this.action = action;
        }

        public Object action() {
            return action;
        }
    }

    /** Click shape with no action accessor at all. */
    static final class NoActionShape {
    }

    /**
     * Mimics the Adventure constant shape: the action class itself declares the
     * public static constant field, but the instance name does not match, so
     * only the constant identity path can recognise it.
     */
    static final class FieldAction {
        public static final FieldAction SUGGEST_COMMAND = new FieldAction("unrelated_name");

        private final String name;

        FieldAction(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    @Test
    void namedSuggestActionIsRecognised() {
        Object click = new ActionShape(new NamedAction("SUGGEST_COMMAND"));
        assertEquals("SUGGEST_COMMAND", AdventureClickPayload.actionName(click));
        assertTrue(AdventureClickPayload.isSuggestCommand(click));
        assertFalse(AdventureClickPayload.isRunCommand(click));
    }

    @Test
    void namedRunActionIsRecognised() {
        Object click = new ActionShape(new NamedAction("RUN_COMMAND"));
        assertEquals("RUN_COMMAND", AdventureClickPayload.actionName(click));
        assertTrue(AdventureClickPayload.isRunCommand(click));
        assertFalse(AdventureClickPayload.isSuggestCommand(click));
    }

    @Test
    void realSuggestClickReportsSuggestName() {
        Object click = ClickEvent.suggestCommand("/land delete confirm 7");
        assertEquals("SUGGEST_COMMAND", AdventureClickPayload.actionName(click));
        assertTrue(AdventureClickPayload.isSuggestCommand(click));
        assertFalse(AdventureClickPayload.isRunCommand(click));
    }

    @Test
    void realRunClickReportsRunName() {
        Object click = ClickEvent.runCommand("/land confirm 1 2 home");
        assertEquals("RUN_COMMAND", AdventureClickPayload.actionName(click));
        assertTrue(AdventureClickPayload.isRunCommand(click));
        assertFalse(AdventureClickPayload.isSuggestCommand(click));
    }

    @Test
    void unknownActionShapeReturnsNullWithoutThrowing() {
        assertNull(AdventureClickPayload.actionName(new NoActionShape()));
        assertFalse(AdventureClickPayload.isSuggestCommand(new NoActionShape()));
        assertFalse(AdventureClickPayload.isRunCommand(new NoActionShape()));
    }

    @Test
    void nullClickActionReturnsNullWithoutThrowing() {
        assertNull(AdventureClickPayload.actionName(null));
        assertFalse(AdventureClickPayload.isSuggestCommand(null));
        assertFalse(AdventureClickPayload.isRunCommand(null));
    }

    @Test
    void lowercaseUnderscoredNameIsRecognised() {
        Object click = new ActionShape(new NamedAction("suggest_command"));
        assertTrue(AdventureClickPayload.isSuggestCommand(click),
                "Adventure 5 reports suggest_command; normalisation must accept it");
        assertFalse(AdventureClickPayload.isRunCommand(click));
    }

    @Test
    void camelCaseNameIsRecognised() {
        Object click = new ActionShape(new NamedAction("SuggestCommand"));
        assertTrue(AdventureClickPayload.isSuggestCommand(click),
                "normalisation must accept the camel-case form as well");
        assertFalse(AdventureClickPayload.isRunCommand(click));
    }

    @Test
    void constantFieldIdentityIsRecognised() {
        Object click = new ActionShape(FieldAction.SUGGEST_COMMAND);
        assertTrue(AdventureClickPayload.isSuggestCommand(click),
                "identity against the reflective constant field must match on its own");
        assertFalse(AdventureClickPayload.isRunCommand(click));
    }

    @Test
    void nonTargetActionNameIsRejected() {
        Object click = new ActionShape(new NamedAction("open_url"));
        assertFalse(AdventureClickPayload.isSuggestCommand(click));
        assertFalse(AdventureClickPayload.isRunCommand(click));
    }
}
