package com.smile.chunkland.wand;

import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Seam for the short guidance prompts the wand click/lifecycle path emits.
 *
 * <p>The handler names the step and, for steps that announce an accepted
 * rectangle, hands over its {@code width}/{@code height} chunk variables;
 * single-chunk steps instead hand over the actual {@code chunk_count}, which
 * is the number the irregular selection is priced and previewed by. It
 * never renders chat text. Production routes each step's {@link Kind#messageKey()}
 * plus the variables through the shared message pipeline, tests record the
 * steps directly. Prompts are one-per-transition, never per tick, so the seam
 * cannot spam.
 */
@FunctionalInterface
public interface WandFeedback {

    enum Kind {
        /** First valid corner recorded; the range is open. */
        FIRST_POINT("selection.wand.first_point"),
        /** First corner on the actor's own land; an edit selection is open. */
        EDIT_TARGET("selection.wand.edit_target"),
        /** Second corner recorded; the chunk rectangle is ready. */
        SECOND_POINT("selection.wand.second_point"),
        /** An existing rectangle was resized in place (first corner kept). */
        RESIZED("selection.wand.resized"),
        /** The click hit an existing land the selection may not cover. */
        BLOCKED("selection.wand.blocked"),
        /** A single chunk was added to the selection; vars carry chunk_count. */
        CHUNK_ADDED("selection.wand.chunk_added"),
        /** A single chunk was removed from the selection; vars carry chunk_count. */
        CHUNK_REMOVED("selection.wand.chunk_removed"),
        /** A single-chunk removal would split the selection, so it was refused. */
        CHUNK_SPLIT("selection.wand.chunk_split"),
        /** A single-chunk edit would exceed the selection size limit. */
        CHUNK_LIMIT("selection.wand.chunk_limit"),
        /** The click is outside the active SubLand parent land. */
        SUBLAND_OUTSIDE("selection.wand.subland_outside"),
        /** Land data is not hydrated yet, so the click cannot be judged safely. */
        UNAVAILABLE("selection.wand.unavailable"),
        /** The wand came back to the main hand; the range restarts clean. */
        RESET("selection.wand.reset");

        private final String messageKey;

        Kind(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * Emit one guidance prompt. {@code vars} carries the message variables for
     * the key (currently {@code width}/{@code height} for the accepted-size
     * steps); steps without variables pass an empty map.
     */
    void send(Player player, Kind kind, Map<String, Object> vars);

    static WandFeedback none() {
        return (player, kind, vars) -> { };
    }
}
