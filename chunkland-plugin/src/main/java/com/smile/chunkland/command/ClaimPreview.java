package com.smile.chunkland.command;

import com.smile.chunkland.selection.SelectionSession;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, same-source preview for the {@code /land claim} confirmation
 * copy on both chat and Bedrock Form.
 *
 * <p>The name, chunk count and token pair are read from the same live
 * {@link SelectionSession} the saga request is built from, so the Java chat
 * confirmation and the Bedrock Modal Form always describe one selection.
 * Price, limit, lowest height and refund have no formal source at the command
 * layer in this milestone (pricing lives inside the saga, limits in the quota
 * service, depths in the vertical follow-up, refunds in their own saga), so
 * they stay empty by construction and render as an explicit unavailable
 * marker instead of a fabricated number. A later milestone fills them here
 * without touching the form or chat rendering.
 */
public record ClaimPreview(
        String landName,
        int chunkCount,
        long sessionGeneration,
        long selectionRevision,
        Optional<String> price,
        Optional<String> limit,
        Optional<String> lowestHeight,
        Optional<String> refund) {

    public ClaimPreview {
        Objects.requireNonNull(landName, "landName");
        if (landName.isBlank()) {
            throw new IllegalArgumentException("landName must not be blank");
        }
        if (chunkCount <= 0) {
            throw new IllegalArgumentException("chunkCount must be positive");
        }
        if (sessionGeneration < 0) {
            throw new IllegalArgumentException("sessionGeneration must not be negative");
        }
        if (selectionRevision < 0) {
            throw new IllegalArgumentException("selectionRevision must not be negative");
        }
        price = orEmpty(price);
        limit = orEmpty(limit);
        lowestHeight = orEmpty(lowestHeight);
        refund = orEmpty(refund);
    }

    /**
     * Snapshot the display fields from the live session the claim would run
     * against. The not-yet-available priced fields stay empty; callers must
     * render them as unavailable, never as zero.
     */
    public static ClaimPreview fromSession(SelectionSession session, String landName) {
        Objects.requireNonNull(session, "session");
        return new ClaimPreview(landName, session.selectedChunks().size(),
                session.sessionGeneration(), session.selectionRevision(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Optional<String> orEmpty(Optional<String> value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String text = value.get();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }
}
