package com.smile.chunkland.api.history;

import java.util.List;

/**
 * Immutable answer to one {@link HistoryQuery}.
 *
 * <p>An unavailable result (backend absent, lookup failed, rows unparsable)
 * is always empty: fail-closed callers reply a generic unavailable message
 * instead of a half-filled payload.
 */
public record HistoryResult(List<HistoryEntry> entries,
                            boolean truncated,
                            boolean available) {

    public HistoryResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** Available answer with at most the queried limit of entries. */
    public static HistoryResult of(List<HistoryEntry> entries, boolean truncated) {
        return new HistoryResult(entries, truncated, true);
    }

    /** Fail-closed answer: unavailable and always empty. */
    public static HistoryResult unavailable() {
        return new HistoryResult(List.of(), false, false);
    }
}
