package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.LandId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable filter for audit history reads.
 *
 * <p>Every field except paging is optional; only the supplied filters become
 * {@code WHERE} terms, so single-filter and combined queries alike stay on the
 * dedicated audit indexes instead of scanning the table. Results are always
 * newest-first ({@code timestamp DESC, id DESC}) with {@code LIMIT/OFFSET}
 * paging. The record is a pure query value: it carries no locale and never
 * influences what is stored.
 */
public record AuditSearchQuery(
        UUID actor,
        Instant since,
        String action,
        LandId landId,
        UUID worldId,
        int limit,
        int offset) {

    /** Upper bound for one page so a single read cannot balloon. */
    public static final int MAX_LIMIT = 100;

    public AuditSearchQuery {
        if (action != null && action.isBlank()) {
            throw new IllegalArgumentException("action must be null or non-blank");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, " + MAX_LIMIT + "]: " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
    }

    /** Unfiltered page; still ordered newest-first. */
    public static AuditSearchQuery all(int limit, int offset) {
        return new AuditSearchQuery(null, null, null, null, null, limit, offset);
    }

    public boolean hasActor() {
        return actor != null;
    }

    public boolean hasSince() {
        return since != null;
    }

    public boolean hasAction() {
        return action != null;
    }

    public boolean hasLandId() {
        return landId != null;
    }

    public boolean hasWorldId() {
        return worldId != null;
    }

    public boolean isUnfiltered() {
        return actor == null && since == null && action == null && landId == null && worldId == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuditSearchQuery that)) {
            return false;
        }
        return limit == that.limit
                && offset == that.offset
                && Objects.equals(actor, that.actor)
                && Objects.equals(since, that.since)
                && Objects.equals(action, that.action)
                && Objects.equals(landId, that.landId)
                && Objects.equals(worldId, that.worldId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actor, since, action, landId, worldId, limit, offset);
    }
}
