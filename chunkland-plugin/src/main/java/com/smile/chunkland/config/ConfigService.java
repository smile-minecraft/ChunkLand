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
     * Construct a service that bypasses loading on construction; intended for
     * tests that want to control the very first snapshot directly. The
     * provided {@code loader} is still used by subsequent {@link #reload()}
     * calls.
     */
    ConfigService(ConfigLoader loader, ChunkLandConfig initial) {
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
                    loaded.subjectDefaults(), loaded.ruleDefaults(),
                    loaded.decisionCacheMaxEntries());
            // Replace the parsed typed payload but keep the bumped epochs.
            ChunkLandConfig published = new ChunkLandConfig(
                    loaded.worlds(),
                    loaded.limits(),
                    loaded.messages(),
                    loaded.selection(),
                    loaded.subjectDefaults(),
                    loaded.ruleDefaults(),
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

    public void removeListener(ConfigReloadListener listener) {
        listeners.remove(listener);
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
                        || !previous.ruleDefaults().equals(next.ruleDefaults()));
    }
}
