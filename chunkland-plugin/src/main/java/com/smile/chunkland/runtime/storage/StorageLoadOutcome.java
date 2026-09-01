package com.smile.chunkland.runtime.storage;

import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable result of the startup storage load.
 *
 * <p>All collections are defensively copied and exposed as unmodifiable views.
 * The cause, when present, is the original exception and is never swallowed.
 * The published registry never contains snapshots from {@code ISOLATED} worlds.
 */
public final class StorageLoadOutcome {

    private final GlobalStorageState globalState;
    private final StorageFailurePolicy appliedPolicy;
    private final StorageLoadFailureKind failureKind;
    private final Throwable cause;
    private final Map<UUID, WorldStorageStatus> worldStatuses;
    private final LandRegistry publishedRegistry;
    private final List<String> warnings;

    private StorageLoadOutcome(
            GlobalStorageState globalState,
            StorageFailurePolicy appliedPolicy,
            StorageLoadFailureKind failureKind,
            Throwable cause,
            Map<UUID, WorldStorageStatus> worldStatuses,
            LandRegistry publishedRegistry,
            List<String> warnings) {
        this.globalState = Objects.requireNonNull(globalState, "globalState");
        this.appliedPolicy = appliedPolicy;
        this.failureKind = failureKind;
        this.cause = cause;
        this.worldStatuses = Collections.unmodifiableMap(Map.copyOf(Objects.requireNonNull(worldStatuses, "worldStatuses")));
        this.publishedRegistry = Objects.requireNonNull(publishedRegistry, "publishedRegistry");
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (globalState == GlobalStorageState.READY) {
            if (appliedPolicy != null || failureKind != null || cause != null) {
                throw new IllegalArgumentException("READY outcome must not carry policy/kind/cause");
            }
        } else {
            if (appliedPolicy == null || failureKind == null || cause == null) {
                throw new IllegalArgumentException("failed outcome must carry policy/kind/cause");
            }
        }
        // Invariant: isolated worlds must not have their snapshots in the published registry
        for (Map.Entry<UUID, WorldStorageStatus> e : this.worldStatuses.entrySet()) {
            if (e.getValue() == WorldStorageStatus.ISOLATED) {
                if (this.publishedRegistry.worlds().containsKey(e.getKey())) {
                    throw new IllegalArgumentException("ISOLATED world " + e.getKey() + " must not be published");
                }
            }
        }
        // Ready-state consistency: HEALTHY must be published and published must be HEALTHY
        if (globalState == GlobalStorageState.READY) {
            for (Map.Entry<UUID, WorldStorageStatus> e : this.worldStatuses.entrySet()) {
                if (e.getValue() == WorldStorageStatus.HEALTHY
                        && !this.publishedRegistry.worlds().containsKey(e.getKey())) {
                    throw new IllegalArgumentException(
                            "HEALTHY world " + e.getKey() + " must be present in published registry");
                }
            }
            for (UUID publishedWid : this.publishedRegistry.worlds().keySet()) {
                WorldStorageStatus s = this.worldStatuses.get(publishedWid);
                if (s != WorldStorageStatus.HEALTHY) {
                    throw new IllegalArgumentException(
                            "published world " + publishedWid + " must have HEALTHY status but was " + s);
                }
            }
        }
    }

    public static StorageLoadOutcome ready(
            Map<UUID, WorldStorageStatus> worldStatuses,
            LandRegistry publishedRegistry,
            List<String> warnings) {
        return new StorageLoadOutcome(
                GlobalStorageState.READY, null, null, null,
                worldStatuses, publishedRegistry, warnings);
    }

    public static StorageLoadOutcome failed(
            GlobalStorageState globalState,
            StorageFailurePolicy appliedPolicy,
            StorageLoadFailureKind failureKind,
            Throwable cause,
            Map<UUID, WorldStorageStatus> worldStatuses,
            LandRegistry publishedRegistry,
            List<String> warnings) {
        if (globalState == GlobalStorageState.READY) {
            throw new IllegalArgumentException("failed outcome cannot be READY");
        }
        return new StorageLoadOutcome(
                globalState, appliedPolicy, failureKind, cause,
                worldStatuses, publishedRegistry, warnings);
    }

    public GlobalStorageState globalState() { return globalState; }

    public Optional<StorageFailurePolicy> appliedPolicy() { return Optional.ofNullable(appliedPolicy); }

    public Optional<StorageLoadFailureKind> failureKind() { return Optional.ofNullable(failureKind); }

    public Optional<Throwable> cause() { return Optional.ofNullable(cause); }

    public Map<UUID, WorldStorageStatus> worldStatuses() { return worldStatuses; }

    public LandRegistry publishedRegistry() { return publishedRegistry; }

    public List<String> warnings() { return warnings; }

    public boolean isReady() { return globalState == GlobalStorageState.READY; }

    public boolean isLockdown() { return globalState == GlobalStorageState.LOCKDOWN; }

    public boolean isStopped() { return globalState == GlobalStorageState.STOPPED; }

    public WorldStorageStatus worldStatus(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return worldStatuses.get(worldId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StorageLoadOutcome other)) return false;
        return globalState == other.globalState
                && appliedPolicy == other.appliedPolicy
                && failureKind == other.failureKind
                && Objects.equals(cause == null ? null : cause.getClass().getName() + ":" + cause.getMessage(),
                                  other.cause == null ? null : other.cause.getClass().getName() + ":" + other.cause.getMessage())
                && worldStatuses.equals(other.worldStatuses)
                && publishedRegistry.worlds().equals(other.publishedRegistry.worlds())
                && publishedRegistry.lands().equals(other.publishedRegistry.lands())
                && warnings.equals(other.warnings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(globalState, appliedPolicy, failureKind, worldStatuses, publishedRegistry.worlds(), publishedRegistry.lands(), warnings);
    }

    @Override
    public String toString() {
        return "StorageLoadOutcome{globalState=" + globalState
                + ", appliedPolicy=" + appliedPolicy
                + ", failureKind=" + failureKind
                + ", cause=" + (cause == null ? "null" : cause.getClass().getSimpleName() + ":" + cause.getMessage())
                + ", worldStatuses=" + worldStatuses
                + ", warnings=" + warnings + "}";
    }
}
