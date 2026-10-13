package com.smile.chunkland.config;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the single, volatile, immutable {@link ChunkLandConfig} snapshot that
 * every ChunkLand subsystem reads.
 *
 * <h2>Read path</h2>
 * Callers obtain the current snapshot via {@link #current()}, which performs
 * one volatile read. The returned snapshot is fully immutable and may be
 * cached by the caller; subsequent reloads publish a new instance so cached
 * references are not mutated.
 *
 * <h2>Reload semantics</h2>
 * {@link #reload()} is atomic with respect to readers:
 * <ol>
 *   <li>The service loads + validates the next snapshot from its
 *       {@link ConfigLoader}.</li>
 *   <li>If loading or validation throws, the previous snapshot is preserved
 *       and no epoch is bumped — fail-closed.</li>
 *   <li>If loading succeeds, the new snapshot is built with bumped
 *       {@code globalPolicyEpoch} and bumped {@code worldPolicyEpochs} for
 *       every world in the union of the old and new world sets.</li>
 *   <li>The volatile reference is swapped in a single store; subsequent
 *       {@link #current()} calls observe the new snapshot.</li>
 *   <li>Registered {@link ConfigReloadListener}s are invoked synchronously
 *       with a {@link ReloadDiff}; the diff is computed from the
 *       pre-publish snapshot reference.</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 * Concurrent reload calls are serialized via an internal {@link ReentrantLock}
 * so that the epoch sequence stays strictly monotonic and no half-published
 * snapshot can be observed (the volatile store happens entirely inside the
 * critical section). Readers are lock-free.
 *
 * <h2>Boundary</h2>
 * The service is intentionally a singleton-style object. It does not depend on
 * Bukkit, Folia or any guarded scheduler; it lives in
 * {@code chunkland-plugin::config} and is wired up from
 * {@code ChunkLandPlugin.onEnable()}.
 */
public final class ConfigService {

    private final ReentrantLock reloadLock = new ReentrantLock();
    private final CopyOnWriteArrayList<ConfigReloadListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ReloadPreparer> preparers = new CopyOnWriteArrayList<>();
    private final ConfigLoader initialLoader;
    private volatile ConfigLoader loader;
    private volatile ChunkLandConfig snapshot;

    /**
     * Construct a service that loads its initial snapshot from {@code loader}.
     * If loading fails, the service starts with
     * {@link ChunkLandConfig#defaults()} and logs via the returned snapshot;
     * the caller can re-attempt via {@link #reload()}.
     */
    public ConfigService(ConfigLoader loader) {
        this.initialLoader = Objects.requireNonNull(loader, "loader");
        this.loader = loader;
        ChunkLandConfig loaded;
        try {
            loaded = loader.load();
        } catch (IOException | RuntimeException ex) {
            // Bootstrap failure is the caller's decision: ChunkLandPlugin's
            // onEnable chooses to fall back to defaults and warn; tests
            // assert the exception via assertThrows around the constructor.
            // Reload-time failures are handled inside reload() (fail-closed).
            throw ex instanceof RuntimeException re ? re
                    : new RuntimeException("failed to load initial config", ex);
        }
        this.snapshot = loaded;
    }

    /**
     * Construct a service that starts from the given snapshot and uses
     * {@code loader} for subsequent {@link #reload()} calls without loading
     * at construction.
     *
     * <p>Production uses this after the startup resolver has already picked
     * the initial snapshot (valid file, seeded first install, last-known-good
     * or conservative fallback): the resolver owns classification, this
     * service owns the volatile snapshot afterwards. Tests use it to control
     * the very first snapshot directly.
     */
    public ConfigService(ConfigLoader loader, ChunkLandConfig initial) {
        this.initialLoader = Objects.requireNonNull(loader, "loader");
        this.loader = loader;
        this.snapshot = Objects.requireNonNull(initial, "initial");
    }

    /** Replace the loader; subsequent {@link #reload()} calls use the new one. */
    public void swapLoader(ConfigLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public ConfigLoader loader() {
        return loader;
    }

    public ConfigLoader initialLoader() {
        return initialLoader;
    }

    /** Return the current immutable snapshot. Lock-free. */
    public ChunkLandConfig current() {
        return snapshot;
    }

    /**
     * Atomically reload.
     *
     * <p>On success returns the new {@link ChunkLandConfig} and notifies every
     * registered listener with the {@link ReloadDiff}. On failure (validation
     * or I/O) returns the previous snapshot unchanged and does NOT advance
     * any epoch; no listener is notified.</p>
     */
    public ChunkLandConfig reload() {
        reloadLock.lock();
        try {
            ChunkLandConfig previous = this.snapshot;
            ChunkLandConfig loaded;
            try {
                loaded = loader.load();
            } catch (IOException | RuntimeException ignored) {
                // Fail-closed: validation errors and any other loader
                // failures preserve the previous snapshot and do NOT
                // advance any epoch counter.
                return previous;
            }
            ChunkLandConfig next = previous.withEpochsBumped(
                    loaded.worlds(), loaded.limits(), loaded.messages(), loaded.selection(),
                    loaded.subjectDefaults(), loaded.ruleDefaults(), loaded.economy(), loaded.audit(),
                    loaded.feedback(), loaded.decisionCacheMaxEntries());
            // Replace the parsed typed payload but keep the bumped epochs.
            ChunkLandConfig published = new ChunkLandConfig(
                    loaded.worlds(),
                    loaded.limits(),
                    loaded.messages(),
                    loaded.selection(),
                    loaded.subjectDefaults(),
                    loaded.ruleDefaults(),
                    loaded.economy(),
                    loaded.audit(),
                    loaded.feedback(),
                    next.globalPolicyEpoch(),
                    next.worldPolicyEpochs(),
                    loaded.decisionCacheMaxEntries());
            this.snapshot = published;
            ReloadDiff diff = computeDiff(previous, published);
            notifyListeners(diff);
            return published;
        } finally {
            reloadLock.unlock();
        }
    }

    public void addListener(ConfigReloadListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Gated reload with a distinguishable outcome.
     *
     * <p>Unlike {@link #reload()}, the result tells "nothing changed" apart
     * from "loading failed": a parseable candidate with content identical to
     * the live snapshot returns {@link ReloadOutcome.Kind#NO_CHANGE} without
     * publishing, bumping epochs or notifying listeners, while a loader
     * failure returns {@link ReloadOutcome.Kind#FAILED} with the cause and
     * keeps the previous snapshot. A gate rejection returns
     * {@link ReloadOutcome.Kind#REJECTED} before anything publishes, so a
     * refused candidate can never leave a half-applied generation behind. A
     * committed publish whose post-publish callbacks threw still returns
     * success, carrying the first callback failure so callers report
     * "committed but a callback failed" instead of pretending a rollback.
     *
     * <p>Between the gate and the publish, registered {@link ReloadPreparer}s
     * build and validate the derived state the candidate would publish. The
     * first throw fails the whole attempt with nothing published and no
     * listener notified.</p>
     *
     * <p>Reentrancy: the attempt takes the reload lock with
     * {@code tryLock}, so a second reload racing an in-flight one returns
     * {@link ReloadOutcome.Kind#BUSY} instead of queueing behind it.
     *
     * @param gate hot-swap gate consulted after loading, before publishing;
     *             null allows every parseable candidate
     */
    public ReloadOutcome tryReload(ReloadGate gate) {
        if (!reloadLock.tryLock()) {
            return ReloadOutcome.busy(this.snapshot);
        }
        try {
            ChunkLandConfig previous = this.snapshot;
            ChunkLandConfig loaded;
            try {
                loaded = loader.load();
            } catch (IOException | RuntimeException failure) {
                // Fail-closed like reload(): the previous snapshot stays, no
                // epoch moves, nobody is notified — but the failure is
                // reported with its cause instead of looking like no-change.
                return ReloadOutcome.failed(previous, failure);
            }
            ReloadGate.GateVerdict verdict = gate == null
                    ? ReloadGate.GateVerdict.allow()
                    : gate.check(previous, loaded);
            if (verdict == null || !verdict.allowed()) {
                String reason = verdict == null ? "reload gate refused" : verdict.reason();
                return ReloadOutcome.rejected(previous, reason);
            }
            if (sameContent(previous, loaded)) {
                return ReloadOutcome.noChange(previous);
            }
            try {
                for (ReloadPreparer preparer : preparers) {
                    preparer.prepare(loaded);
                }
            } catch (RuntimeException prepareFailure) {
                // Prepare-before-publish: derived state could not be built
                // for the candidate, so nothing publishes, no epoch moves
                // and nobody is notified. Reported as a failure with its
                // cause, never as no-change.
                return ReloadOutcome.failed(previous, prepareFailure);
            }
            ChunkLandConfig next = previous.withEpochsBumped(
                    loaded.worlds(), loaded.limits(), loaded.messages(), loaded.selection(),
                    loaded.subjectDefaults(), loaded.ruleDefaults(), loaded.economy(), loaded.audit(),
                    loaded.feedback(), loaded.decisionCacheMaxEntries());
            ChunkLandConfig published = new ChunkLandConfig(
                    loaded.worlds(),
                    loaded.limits(),
                    loaded.messages(),
                    loaded.selection(),
                    loaded.subjectDefaults(),
                    loaded.ruleDefaults(),
                    loaded.economy(),
                    loaded.audit(),
                    loaded.feedback(),
                    next.globalPolicyEpoch(),
                    next.worldPolicyEpochs(),
                    loaded.decisionCacheMaxEntries());
            this.snapshot = published;
            ReloadDiff diff = computeDiff(previous, published);
            RuntimeException callbackFailure = notifyListenersCollecting(diff);
            return ReloadOutcome.success(published, diff, callbackFailure);
        } finally {
            reloadLock.unlock();
        }
    }

    /**
     * Content equality ignoring the epoch counters: every typed section plus
     * the decision-cache budget.
     */
    static boolean sameContent(ChunkLandConfig left, ChunkLandConfig right) {
        return left.worlds().equals(right.worlds())
                && left.limits().equals(right.limits())
                && left.messages().equals(right.messages())
                && left.selection().equals(right.selection())
                && left.subjectDefaults().equals(right.subjectDefaults())
                && left.ruleDefaults().equals(right.ruleDefaults())
                && Objects.equals(left.economy(), right.economy())
                && left.audit().equals(right.audit())
                && left.feedback().equals(right.feedback())
                && left.decisionCacheMaxEntries() == right.decisionCacheMaxEntries();
    }

    public void removeListener(ConfigReloadListener listener) {
        listeners.remove(listener);
    }

    /**
     * Register a prepare hook for the gated reload path. Preparers run in
     * registration order after the gate allows a candidate and before it
     * publishes; the first throw fails the whole reload with nothing
     * published and no listener notified. The legacy {@link #reload()}
     * never consults preparers.
     */
    public void addPreparer(ReloadPreparer preparer) {
        preparers.add(Objects.requireNonNull(preparer, "preparer"));
    }

    private void notifyListeners(ReloadDiff diff) {
        // Iterate over the snapshot to avoid concurrent-mutation surprises.
        RuntimeException firstFailure = null;
        for (ConfigReloadListener listener : listeners) {
            try {
                listener.onConfigReload(diff);
            } catch (RuntimeException re) {
                // A misbehaving listener must not stop the others; collect the
                // first failure so the caller can surface it. The reload has
                // already committed by this point — there is no rollback.
                if (firstFailure == null) {
                    firstFailure = re;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * Same fan-out as {@link #notifyListeners(ReloadDiff)}, but the first
     * listener failure is returned instead of thrown so the gated reload
     * path can report "committed but a callback failed".
     */
    private RuntimeException notifyListenersCollecting(ReloadDiff diff) {
        RuntimeException firstFailure = null;
        for (ConfigReloadListener listener : listeners) {
            try {
                listener.onConfigReload(diff);
            } catch (RuntimeException re) {
                if (firstFailure == null) {
                    firstFailure = re;
                }
            }
        }
        return firstFailure;
    }

    private static ReloadDiff computeDiff(ChunkLandConfig previous, ChunkLandConfig next) {
        Map<String, WorldSettings> prevWorlds = previous.worlds();
        Map<String, WorldSettings> nextWorlds = next.worlds();
        Set<String> prevNames = prevWorlds.keySet();
        Set<String> nextNames = nextWorlds.keySet();
        Map<String, String> changed = new LinkedHashMap<>();
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        for (String name : prevNames) {
            if (!nextNames.contains(name)) {
                removed.add(name);
            } else if (!prevWorlds.get(name).equals(nextWorlds.get(name))) {
                changed.put(name, name);
            }
        }
        for (String name : nextNames) {
            if (!prevNames.contains(name)) {
                added.add(name);
            }
        }
        return ReloadDiff.of(
                changed.keySet(),
                added,
                removed,
                previous.globalPolicyEpoch(),
                next.globalPolicyEpoch(),
                !previous.limits().equals(next.limits())
                        || !previous.messages().equals(next.messages())
                        || !previous.selection().equals(next.selection())
                        || !previous.subjectDefaults().equals(next.subjectDefaults())
                        || !previous.ruleDefaults().equals(next.ruleDefaults())
                        || !previous.audit().equals(next.audit())
                        || !java.util.Objects.equals(previous.economy(), next.economy()));
    }
}
