package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.config.ConfigReloadListener;
import com.smile.chunkland.config.ReloadDiff;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Owns the one-session-per-player registry and all session lifecycle side effects.
 *
 * <p>All public operations are serialized. A timeout callback carries an opaque
 * generation token, so a cancelled or replaced timeout can never expire a newer
 * session. Runtime collaborators are kept in entries, never in
 * {@link SelectionSession}.</p>
 */
public final class SelectionSessionManager implements ConfigReloadListener {
    private final Object monitor = new Object();
    private final Map<UUID, Entry> sessions = new LinkedHashMap<>();
    private final Set<UUID> cleaningPlayers = new HashSet<>();
    private final SelectionTimeoutScheduler timeoutScheduler;
    private final SelectionVisualizationTaskController visualizationTasks;
    private final SelectionNotifier notifier;
    private final SelectionClock clock;
    private final Supplier<Duration> timeoutProvider;
    private Duration timeout;
    private final SelectionWorldNameResolver worldNameResolver;
    private final SelectionStructureRevisionLookup structureRevisions;
    private boolean disabled;

    public SelectionSessionManager(
            SelectionTimeoutScheduler timeoutScheduler,
            SelectionVisualizationTaskController visualizationTasks,
            SelectionNotifier notifier,
            SelectionClock clock,
            Duration timeout,
            SelectionWorldNameResolver worldNameResolver,
            SelectionStructureRevisionLookup structureRevisions) {
        this(
                timeoutScheduler,
                visualizationTasks,
                notifier,
                clock,
                () -> timeout,
                worldNameResolver,
                structureRevisions);
    }

    public SelectionSessionManager(
            SelectionTimeoutScheduler timeoutScheduler,
            SelectionVisualizationTaskController visualizationTasks,
            SelectionNotifier notifier,
            SelectionClock clock,
            Supplier<Duration> timeoutProvider,
            SelectionWorldNameResolver worldNameResolver,
            SelectionStructureRevisionLookup structureRevisions) {
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.visualizationTasks = Objects.requireNonNull(visualizationTasks, "visualizationTasks");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeoutProvider = Objects.requireNonNull(timeoutProvider, "timeoutProvider");
        this.timeout = requirePositiveTimeout(timeoutProvider.get());
        this.worldNameResolver = Objects.requireNonNull(worldNameResolver, "worldNameResolver");
        this.structureRevisions = Objects.requireNonNull(structureRevisions, "structureRevisions");
    }

    public SelectionSessionManager(
            SelectionTimeoutScheduler timeoutScheduler,
            SelectionVisualizationTaskController visualizationTasks,
            SelectionNotifier notifier,
            SelectionClock clock,
            Duration timeout) {
        this(
                timeoutScheduler,
                visualizationTasks,
                notifier,
                clock,
                timeout,
                ignored -> Optional.empty(),
                SelectionStructureRevisionLookup.unavailable());
    }

    /** Install a session, replacing the previous session for the same player. */
    public SelectionSession start(SelectionSession session) {
        Objects.requireNonNull(session, "session");
        CleanupPlan replacement;
        synchronized (monitor) {
            ensureEnabled();
            if (cleaningPlayers.contains(session.playerId())) {
                throw new IllegalStateException("selection session cleanup is in progress");
            }
            Entry previous = sessions.get(session.playerId());
            replacement = previous == null ? null : beginCleanup(previous, SelectionEndReason.REPLACED);
        }
        if (replacement != null) {
            performCleanup(replacement);
        }

        Entry next;
        Object timeoutToken;
        synchronized (monitor) {
            ensureEnabled();
            if (cleaningPlayers.contains(session.playerId()) || sessions.containsKey(session.playerId())) {
                throw new IllegalStateException("selection session is being changed");
            }
            next = new Entry(session);
            timeoutToken = next.timeoutToken;
            sessions.put(session.playerId(), next);
        }
        try {
            schedule(next, timeoutToken);
        } catch (RuntimeException ex) {
            CleanupPlan failedStart;
            synchronized (monitor) {
                failedStart = beginCleanup(next, timeoutToken, SelectionEndReason.REPLACED, false);
            }
            if (failedStart != null) {
                performCleanup(failedStart);
            }
            throw ex;
        }
        return session;
    }

    /** Alias that makes the replacement behavior explicit at call sites. */
    public SelectionSession createOrReplace(SelectionSession session) {
        return start(session);
    }

    public Optional<SelectionSession> sessionFor(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (monitor) {
            Entry entry = sessions.get(playerId);
            return entry == null || entry.cleaning ? Optional.empty() : Optional.of(entry.session);
        }
    }

    boolean registryContains(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (monitor) {
            return sessions.containsKey(playerId);
        }
    }

    public Map<UUID, SelectionSession> snapshot() {
        synchronized (monitor) {
            Map<UUID, SelectionSession> copy = new LinkedHashMap<>();
            sessions.forEach((playerId, entry) -> {
                if (!entry.cleaning) {
                    copy.put(playerId, entry.session);
                }
            });
            return Map.copyOf(copy);
        }
    }

    public int size() {
        synchronized (monitor) {
            return (int) sessions.values().stream().filter(entry -> !entry.cleaning).count();
        }
    }

    public boolean isDisabled() {
        synchronized (monitor) {
            return disabled;
        }
    }

    /**
     * Apply an update from the immutable session snapshot that produced the action.
     * Snapshot identity is checked by reference so equal-valued replacement sessions remain distinct.
     */
    public Optional<SelectionSession> updateSelection(
            UUID playerId, SelectionSession expectedSession, SelectionUpdate update) {
        return updateInternal(playerId, expectedSession, null, update);
    }

    /**
     * Apply an update while explicitly supplying the target Land revision observed
     * by the caller. A mismatch invalidates the session before any update is kept.
     */
    public Optional<SelectionSession> updateSelection(
            UUID playerId,
            SelectionSession expectedSession,
            long currentStructureRevision,
            SelectionUpdate update) {
        return updateInternal(playerId, expectedSession, currentStructureRevision, update);
    }

    private Optional<SelectionSession> updateInternal(
            UUID playerId,
            SelectionSession expectedSession,
            Long suppliedStructureRevision,
            SelectionUpdate update) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(expectedSession, "expectedSession");
        Objects.requireNonNull(update, "update");
        Entry entry;
        synchronized (monitor) {
            if (disabled) {
                return Optional.empty();
            }
            entry = sessions.get(playerId);
            if (entry == null || entry.cleaning || entry.session != expectedSession) {
                return Optional.empty();
            }
        }

        OptionalLong currentStructure = structureRevision(entry, suppliedStructureRevision);
        if (!structureIsCurrent(entry, currentStructure)) {
            CleanupPlan invalidation;
            synchronized (monitor) {
                invalidation = beginCleanup(entry, SelectionEndReason.LAND_STRUCTURE_CHANGED);
            }
            if (invalidation != null) {
                performCleanup(invalidation);
            }
            return Optional.empty();
        }

        SelectionSession updated;
        TimeoutRotation rotation;
        synchronized (monitor) {
            if (disabled || sessions.get(playerId) != entry || entry.cleaning || entry.session != expectedSession) {
                return Optional.empty();
            }
            long nextRevision;
            try {
                nextRevision = Math.addExact(entry.session.selectionRevision(), 1);
            } catch (ArithmeticException ex) {
                throw new IllegalStateException("selectionRevision overflow", ex);
            }
            updated = entry.session.withSelection(update, nextRevision, clock.now());
            entry.session = updated;
            rotation = beginTimeoutRotation(entry);
        }
        performTimeoutRotation(rotation);
        return Optional.of(updated);
    }

    private OptionalLong structureRevision(Entry entry, Long suppliedStructureRevision) {
        Optional<LandId> target = entry.session.targetLandId();
        if (target.isEmpty()) {
            return OptionalLong.empty();
        }
        if (suppliedStructureRevision != null) {
            return OptionalLong.of(suppliedStructureRevision);
        }
        try {
            return Objects.requireNonNull(
                    structureRevisions.currentRevision(target.get()), "structure revision lookup result");
        } catch (RuntimeException ex) {
            return OptionalLong.empty();
        }
    }

    private boolean structureIsCurrent(Entry entry, OptionalLong current) {
        return entry.session.targetLandId().isEmpty()
                || (current.isPresent()
                && current.getAsLong() >= 0
                && current.getAsLong() == entry.session.baseStructureRevision());
    }

    public boolean cancel(UUID playerId) {
        return clear(playerId, SelectionEndReason.CANCELLED);
    }

    public boolean clear(UUID playerId, SelectionEndReason reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        CleanupPlan cleanup;
        synchronized (monitor) {
            Entry entry = sessions.get(playerId);
            if (entry == null || entry.cleaning) {
                return false;
            }
            cleanup = beginCleanup(entry, reason);
        }
        performCleanup(cleanup);
        return true;
    }

    public void onPlayerQuit(UUID playerId) {
        clear(playerId, SelectionEndReason.PLAYER_QUIT);
    }

    public void onWorldChange(UUID playerId) {
        clear(playerId, SelectionEndReason.WORLD_CHANGED);
    }

    /** Same-world respawn is a no-op; cross-world respawn follows world-change semantics. */
    public void onRespawn(UUID playerId, UUID respawnWorldId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(respawnWorldId, "respawnWorldId");
        CleanupPlan cleanup = null;
        synchronized (monitor) {
            Entry entry = sessions.get(playerId);
            if (entry != null && !entry.cleaning && !entry.session.worldId().equals(respawnWorldId)) {
                cleanup = beginCleanup(entry, SelectionEndReason.WORLD_CHANGED);
            }
        }
        if (cleanup != null) {
            performCleanup(cleanup);
        }
    }

    public void onLandDeleted(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        clearMatching(landId, null, SelectionEndReason.LAND_DELETED);
    }

    public void onSubLandDeleted(SubLandId subLandId) {
        Objects.requireNonNull(subLandId, "subLandId");
        clearMatching(null, subLandId, SelectionEndReason.SUBLAND_DELETED);
    }

    /** Invalidate only sessions whose captured base revision is stale. */
    public void onLandStructureRevisionChanged(LandId landId, long currentRevision) {
        Objects.requireNonNull(landId, "landId");
        if (currentRevision < 0) {
            throw new IllegalArgumentException("currentRevision must be non-negative");
        }
        List<CleanupPlan> cleanups = new ArrayList<>();
        synchronized (monitor) {
            List<Entry> stale = new ArrayList<>();
            for (Entry entry : sessions.values()) {
                if (entry.session.targetLandId().filter(landId::equals).isPresent()
                        && entry.session.baseStructureRevision() != currentRevision) {
                    stale.add(entry);
                }
            }
            for (Entry entry : stale) {
                CleanupPlan cleanup = beginCleanup(entry, SelectionEndReason.LAND_STRUCTURE_CHANGED);
                if (cleanup != null) {
                    cleanups.add(cleanup);
                }
            }
        }
        cleanups.forEach(this::performCleanup);
    }

    @Override
    public void onConfigReload(ReloadDiff diff) {
        Objects.requireNonNull(diff, "diff");
        if (diff.globalPolicyChanged()) {
            Duration nextTimeout = requirePositiveTimeout(timeoutProvider.get());
            synchronized (monitor) {
                timeout = nextTimeout;
            }
        }
        Set<String> affectedWorlds = new java.util.LinkedHashSet<>();
        affectedWorlds.addAll(diff.changedWorlds());
        affectedWorlds.addAll(diff.addedWorlds());
        affectedWorlds.addAll(diff.removedWorlds());
        if (!diff.globalPolicyChanged() && affectedWorlds.isEmpty()) {
            return;
        }
        List<CleanupPlan> cleanups = new ArrayList<>();
        synchronized (monitor) {
            List<Entry> affected = new ArrayList<>();
            for (Entry entry : sessions.values()) {
                if (diff.globalPolicyChanged()) {
                    affected.add(entry);
                    continue;
                }
                Optional<String> worldName;
                try {
                    worldName = worldNameResolver.nameOf(entry.session.worldId());
                } catch (RuntimeException ex) {
                    worldName = Optional.empty();
                }
                // If the UUID cannot be resolved, retaining state would assume that
                // the changed config is unrelated. Invalidation is the safe choice.
                if (worldName.isEmpty() || affectedWorlds.contains(worldName.get())) {
                    affected.add(entry);
                }
            }
            for (Entry entry : affected) {
                CleanupPlan cleanup = beginCleanup(entry, SelectionEndReason.CONFIG_CHANGED);
                if (cleanup != null) {
                    cleanups.add(cleanup);
                }
            }
        }
        cleanups.forEach(this::performCleanup);
    }

    /** Stop all timeout and visualization resources and permanently close this manager. */
    public void disable() {
        List<CleanupPlan> cleanups = new ArrayList<>();
        synchronized (monitor) {
            if (disabled) {
                return;
            }
            disabled = true;
            List<Entry> entries = new ArrayList<>(sessions.values());
            for (Entry entry : entries) {
                CleanupPlan cleanup = beginCleanup(entry, SelectionEndReason.PLUGIN_DISABLED);
                if (cleanup != null) {
                    cleanups.add(cleanup);
                }
            }
        }
        cleanups.forEach(this::performCleanup);
    }

    private void clearMatching(LandId landId, SubLandId subLandId, SelectionEndReason reason) {
        List<CleanupPlan> cleanups = new ArrayList<>();
        synchronized (monitor) {
            List<Entry> matching = new ArrayList<>();
            for (Entry entry : sessions.values()) {
                boolean landMatches = landId != null
                        && entry.session.targetLandId().filter(landId::equals).isPresent();
                boolean subLandMatches = subLandId != null
                        && entry.session.targetSubLandId().filter(subLandId::equals).isPresent();
                if (landMatches || subLandMatches) {
                    matching.add(entry);
                }
            }
            for (Entry entry : matching) {
                CleanupPlan cleanup = beginCleanup(entry, reason);
                if (cleanup != null) {
                    cleanups.add(cleanup);
                }
            }
        }
        cleanups.forEach(this::performCleanup);
    }

    /** Mark an entry closed while still retaining it for the ordered stop callback. */
    private CleanupPlan beginCleanup(Entry entry, SelectionEndReason reason) {
        return beginCleanup(entry, reason, true);
    }

    private CleanupPlan beginCleanup(
            Entry entry, Object expectedTimeoutToken, SelectionEndReason reason, boolean notify) {
        if (sessions.get(entry.session.playerId()) != entry
                || entry.cleaning
                || entry.timeoutToken != expectedTimeoutToken) {
            return null;
        }
        return beginCleanup(entry, reason, notify);
    }

    private CleanupPlan beginCleanup(Entry entry, SelectionEndReason reason, boolean notify) {
        if (sessions.get(entry.session.playerId()) != entry || entry.cleaning) {
            return null;
        }
        entry.cleaning = true;
        cleaningPlayers.add(entry.session.playerId());
        entry.timeoutToken = new Object();
        SelectionTimeoutScheduler.Cancellable handle = entry.timeoutHandle;
        entry.timeoutHandle = SelectionTimeoutScheduler.Cancellable.noop();
        return new CleanupPlan(
                entry,
                handle,
                notify ? new SelectionNotification(
                        entry.session.playerId(),
                        reason,
                        entry.session.targetLandId(),
                        entry.session.targetSubLandId(),
                        reason == SelectionEndReason.LAND_STRUCTURE_CHANGED) : null);
    }

    /** Execute callbacks without holding the manager monitor, in the established order. */
    private void performCleanup(CleanupPlan cleanup) {
        try {
            cleanup.timeoutHandle().cancel();
        } catch (RuntimeException ignored) {
        }
        try {
            visualizationTasks.stop(cleanup.entry().session.playerId());
        } catch (RuntimeException ignored) {
        }
        synchronized (monitor) {
            sessions.remove(cleanup.entry().session.playerId(), cleanup.entry());
        }
        if (cleanup.notification() != null) {
            try {
                notifier.notify(cleanup.notification());
            } catch (RuntimeException ignored) {
            }
        }
        synchronized (monitor) {
            cleaningPlayers.remove(cleanup.entry().session.playerId());
        }
    }

    private void schedule(Entry entry) {
        Object timeoutToken;
        synchronized (monitor) {
            timeoutToken = entry.timeoutToken;
        }
        schedule(entry, timeoutToken);
    }

    private void schedule(Entry entry, Object timeoutToken) {
        UUID playerId;
        Duration delay;
        synchronized (monitor) {
            playerId = entry.session.playerId();
            delay = remaining(entry.session.lastActivity());
        }
        SelectionTimeoutScheduler.Cancellable handle = timeoutScheduler.schedule(
                playerId,
                delay,
                () -> timeout(playerId, timeoutToken));
        handle = Objects.requireNonNull(handle, "timeout scheduler returned null");

        boolean cancelImmediately;
        synchronized (monitor) {
            cancelImmediately = sessions.get(playerId) != entry || entry.cleaning || entry.timeoutToken != timeoutToken;
            if (!cancelImmediately) {
                entry.timeoutHandle = handle;
            }
        }
        if (cancelImmediately) {
            try {
                handle.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private TimeoutRotation beginTimeoutRotation(Entry entry) {
        SelectionTimeoutScheduler.Cancellable previousHandle = entry.timeoutHandle;
        Object timeoutToken = new Object();
        entry.timeoutToken = timeoutToken;
        entry.timeoutFired = false;
        entry.timeoutHandle = SelectionTimeoutScheduler.Cancellable.noop();
        return new TimeoutRotation(entry, previousHandle, timeoutToken);
    }

    private void performTimeoutRotation(TimeoutRotation rotation) {
        try {
            rotation.previousHandle().cancel();
        } catch (RuntimeException ignored) {
        }
        try {
            schedule(rotation.entry(), rotation.timeoutToken());
        } catch (RuntimeException ex) {
            CleanupPlan cleanup;
            synchronized (monitor) {
                cleanup = beginCleanup(
                        rotation.entry(), rotation.timeoutToken(), SelectionEndReason.REPLACED, false);
            }
            if (cleanup != null) {
                performCleanup(cleanup);
            }
            throw ex;
        }
    }

    private Duration remaining(Instant lastActivity) {
        Instant expires;
        try {
            expires = lastActivity.plus(timeout);
        } catch (ArithmeticException ex) {
            expires = Instant.MAX;
        }
        Instant now = clock.now();
        if (!now.isBefore(expires)) {
            return Duration.ZERO;
        }
        return Duration.between(now, expires);
    }

    private void timeout(UUID playerId, Object token) {
        CleanupPlan cleanup = null;
        synchronized (monitor) {
            Entry entry = sessions.get(playerId);
            if (entry == null || entry.cleaning || entry.timeoutToken != token || entry.timeoutFired) {
                return;
            }
            entry.timeoutFired = true;
            cleanup = beginCleanup(entry, SelectionEndReason.TIMEOUT);
        }
        if (cleanup != null) {
            performCleanup(cleanup);
        }
    }

    private void ensureEnabled() {
        if (disabled) {
            throw new IllegalStateException("selection session manager is disabled");
        }
    }

    private static Duration requirePositiveTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    private static final class Entry {
        SelectionSession session;
        SelectionTimeoutScheduler.Cancellable timeoutHandle = SelectionTimeoutScheduler.Cancellable.noop();
        Object timeoutToken = new Object();
        boolean timeoutFired;
        boolean cleaning;

        Entry(SelectionSession session) {
            this.session = session;
        }
    }

    private record CleanupPlan(
            Entry entry,
            SelectionTimeoutScheduler.Cancellable timeoutHandle,
            SelectionNotification notification) {
    }

    private record TimeoutRotation(
            Entry entry,
            SelectionTimeoutScheduler.Cancellable previousHandle,
            Object timeoutToken) {
    }
}
