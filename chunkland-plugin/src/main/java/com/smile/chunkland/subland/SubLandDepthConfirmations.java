package com.smile.chunkland.subland;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Actor-scoped, in-memory depth confirmations for SubLand retries.
 *
 * <p>Each successful durable extend records one request against the actor and
 * the still-live selection session. A confirmation is removed when the domain
 * asks whether it exists, and restored only when the later durable mutation
 * fails before commit. This makes the confirmation single-use without losing a
 * retry after a failed create or update.
 *
 * <p>The confirmation is never persisted and never touches Bukkit. Expiry is
 * checked against the clock, while the live session identity check ensures a
 * timed-out or replaced session cannot make an old confirmation usable.
 */
public final class SubLandDepthConfirmations implements DepthExtensionPort {

    /** Short default lifetime; production keeps this below the selection timeout. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final Object monitor = new Object();
    private final Map<UUID, Map<RequestKey, Entry>> entries = new HashMap<>();
    private final SelectionSessionManager selections;
    private final Clock clock;
    private final Duration ttl;

    public SubLandDepthConfirmations(
            SelectionSessionManager selections, Clock clock, Duration ttl) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = requirePositive(ttl);
    }

    public SubLandDepthConfirmations(SelectionSessionManager selections, Clock clock) {
        this(selections, clock, DEFAULT_TTL);
    }

    /**
     * Record a successful extend for the still-live session.
     *
     * @return {@code false} when the session is no longer current or the clock
     *         cannot produce a finite expiry
     */
    public boolean record(UUID actor, SelectionSession session, LandId parentId, int requestedMinY) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(parentId, "parentId");
        Instant expiresAt;
        try {
            expiresAt = clock.instant().plus(ttl);
        } catch (RuntimeException failure) {
            return false;
        }
        synchronized (monitor) {
            if (!isCurrent(actor, session)) {
                return false;
            }
            RequestKey key = new RequestKey(parentId, requestedMinY);
            Map<RequestKey, Entry> actorEntries = entries.computeIfAbsent(
                    actor, ignored -> new HashMap<>());
            actorEntries.put(key, new Entry(session, expiresAt));
            return true;
        }
    }

    /** The legacy no-actor port cannot prove ownership and therefore fails closed. */
    @Override
    public boolean isDepthExtendConfirmed(LandId parentId, int effectiveMinY, int requestedMinY) {
        return false;
    }

    @Override
    public boolean isDepthExtendConfirmed(
            UUID actor, LandId parentId, int effectiveMinY, int requestedMinY) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(parentId, "parentId");
        if (requestedMinY >= effectiveMinY) {
            return false;
        }
        synchronized (monitor) {
            purgeExpired(actor);
            Map<RequestKey, Entry> actorEntries = entries.get(actor);
            if (actorEntries == null) {
                return false;
            }
            RequestKey key = new RequestKey(parentId, requestedMinY);
            Entry entry = actorEntries.get(key);
            if (entry == null || !isCurrent(actor, entry.session())) {
                actorEntries.remove(key);
                if (actorEntries.isEmpty()) {
                    entries.remove(actor);
                }
                return false;
            }
            actorEntries.remove(key);
            if (actorEntries.isEmpty()) {
                entries.remove(actor);
            }
            return true;
        }
    }

    @Override
    public void onDurableFailure(UUID actor, LandId parentId, int requestedMinY) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(parentId, "parentId");
        synchronized (monitor) {
            Map<RequestKey, Entry> actorEntries = entries.get(actor);
            if (actorEntries != null && actorEntries.containsKey(new RequestKey(parentId, requestedMinY))) {
                return;
            }
            Optional<SelectionSession> current;
            try {
                current = selections.sessionFor(actor);
            } catch (RuntimeException failure) {
                return;
            }
            if (current.isEmpty() || clock.instant().isAfter(Instant.MAX.minus(ttl))) {
                return;
            }
            Entry restored = new Entry(current.get(), clock.instant().plus(ttl));
            entries.computeIfAbsent(actor, ignored -> new HashMap<>())
                    .put(new RequestKey(parentId, requestedMinY), restored);
        }
    }

    /** Visible for deterministic tests and diagnostics. */
    public int size() {
        synchronized (monitor) {
            purgeAllExpired();
            return entries.values().stream().mapToInt(Map::size).sum();
        }
    }

    /** Remove all actor-scoped confirmations, primarily for lifecycle cleanup. */
    public void clear() {
        synchronized (monitor) {
            entries.clear();
        }
    }

    private boolean isCurrent(UUID actor, SelectionSession session) {
        try {
            return selections.sessionFor(actor).map(current -> current == session).orElse(false);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void purgeExpired(UUID actor) {
        Map<RequestKey, Entry> actorEntries = entries.get(actor);
        if (actorEntries == null) {
            return;
        }
        Instant now = clock.instant();
        actorEntries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (actorEntries.isEmpty()) {
            entries.remove(actor);
        }
    }

    private void purgeAllExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(actorEntry -> {
            actorEntry.getValue().entrySet().removeIf(
                    entry -> !entry.getValue().expiresAt().isAfter(now));
            return actorEntry.getValue().isEmpty();
        });
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "ttl");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return value;
    }

    private record RequestKey(LandId parentId, int requestedMinY) {
        private RequestKey {
            Objects.requireNonNull(parentId, "parentId");
        }
    }

    private record Entry(SelectionSession session, Instant expiresAt) {
        private Entry {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
