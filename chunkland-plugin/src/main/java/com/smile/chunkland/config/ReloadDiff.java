package com.smile.chunkland.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Diff between the previous and the new {@link ChunkLandConfig} snapshots.
 *
 * <p>The diff is delivered to {@link ConfigReloadListener} after the new
 * snapshot has been atomically published. The {@code oldGlobalPolicyEpoch}
 * and {@code newGlobalPolicyEpoch} pair allows listeners to compare epoch
 * counters without needing a reference to the previous snapshot.</p>
 *
 * <p>World sets are computed by content-equality of the {@link WorldSettings}
 * value, so a reload whose parsed YAML produces an identical settings map
 * will report no changed worlds. Removed worlds are still reported so
 * listeners can drop derived state.</p>
 *
 * <p>{@code globalPolicyChanged} is content-based and independent of the global
 * epoch: it is true only when limits, messages, selection timeout, the
 * economy section, or the subject/rule permission defaults changed.</p>
 */
public record ReloadDiff(
        Set<String> changedWorlds,
        Set<String> addedWorlds,
        Set<String> removedWorlds,
        long oldGlobalPolicyEpoch,
        long newGlobalPolicyEpoch,
        boolean globalPolicyChanged) {

    public ReloadDiff(Set<String> changedWorlds,
                      Set<String> addedWorlds,
                      Set<String> removedWorlds,
                      long oldGlobalPolicyEpoch,
                      long newGlobalPolicyEpoch) {
        this(changedWorlds, addedWorlds, removedWorlds, oldGlobalPolicyEpoch, newGlobalPolicyEpoch, false);
    }

    public ReloadDiff {
        Objects.requireNonNull(changedWorlds, "changedWorlds");
        Objects.requireNonNull(addedWorlds, "addedWorlds");
        Objects.requireNonNull(removedWorlds, "removedWorlds");
        if (newGlobalPolicyEpoch < oldGlobalPolicyEpoch) {
            throw new IllegalArgumentException(
                    "newGlobalPolicyEpoch (" + newGlobalPolicyEpoch
                            + ") must not be smaller than oldGlobalPolicyEpoch ("
                            + oldGlobalPolicyEpoch + ")");
        }
    }

    /** Convenience constructor that defensively copies the input sets. */
    public static ReloadDiff of(Set<String> changed,
                                Set<String> added,
                                Set<String> removed,
                                long oldEpoch,
                                long newEpoch) {
        return of(changed, added, removed, oldEpoch, newEpoch, false);
    }

    public static ReloadDiff of(Set<String> changed,
                                Set<String> added,
                                Set<String> removed,
                                long oldEpoch,
                                long newEpoch,
                                boolean globalPolicyChanged) {
        return new ReloadDiff(
                Collections.unmodifiableSet(new LinkedHashSet<>(changed)),
                Collections.unmodifiableSet(new LinkedHashSet<>(added)),
                Collections.unmodifiableSet(new LinkedHashSet<>(removed)),
                oldEpoch,
                newEpoch,
                globalPolicyChanged);
    }
}
