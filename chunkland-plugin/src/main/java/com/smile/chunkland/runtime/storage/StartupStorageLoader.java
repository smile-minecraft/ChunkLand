package com.smile.chunkland.runtime.storage;

import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Injectable startup loader that applies the storage failure policy and orphan
 * world isolation on top of a raw {@link SnapshotLoader}.
 *
 * <p>No Bukkit types are referenced; the world catalog and logger are injected
 * seams. All failure paths are fail-closed and preserve the original cause.
 */
public final class StartupStorageLoader {

    private final SnapshotLoader snapshotLoader;
    private final WorldCatalog worldCatalog;
    private final StorageFailurePolicy failurePolicy;
    private final StorageLogger logger;

    public StartupStorageLoader(
            SnapshotLoader snapshotLoader,
            WorldCatalog worldCatalog,
            StorageFailurePolicy failurePolicy,
            StorageLogger logger) {
        this.snapshotLoader = Objects.requireNonNull(snapshotLoader, "snapshotLoader");
        this.worldCatalog = Objects.requireNonNull(worldCatalog, "worldCatalog");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Load and classify storage state. Never returns an empty index as success
     * when the loader threw, and never publishes orphan snapshots.
     */
    public StorageLoadOutcome load() {
        List<LandSnapshot> all;
        try {
            all = snapshotLoader.load();
            if (all == null) {
                throw new IllegalStateException("SnapshotLoader returned null");
            }
        } catch (Exception failure) {
            StorageLoadFailureKind kind = classify(failure);
            GlobalStorageState globalState = policyToState(failurePolicy);
            String msg = "storage bootstrap failed [" + kind + "]: " + failure.getMessage();
            logger.error(msg, failure);
            Map<UUID, WorldStorageStatus> emptyStatuses = Map.of();
            LandRegistry emptyRegistry = LandRegistry.empty();
            return StorageLoadOutcome.failed(globalState, failurePolicy, kind, failure, emptyStatuses, emptyRegistry, List.of());
        }

        // Partition into healthy vs orphan (ISOLATED)
        Set<UUID> isolatedWorlds = new HashSet<>();
        Set<UUID> healthyWorlds = new HashSet<>();
        List<LandSnapshot> healthySnapshots = new ArrayList<>();
        Map<UUID, WorldStorageStatus> statuses = new HashMap<>();
        List<String> warnings = new ArrayList<>();

        // First, determine world status
        for (LandSnapshot snap : all) {
            if (snap == null) {
                // Malformed row: treat as fail-closed via exception path
                IllegalStateException malformed = new IllegalStateException("malformed row: null snapshot");
                StorageLoadFailureKind kind = StorageLoadFailureKind.MALFORMED_ROW;
                GlobalStorageState globalState = policyToState(failurePolicy);
                logger.error("storage load encountered malformed row", malformed);
                return StorageLoadOutcome.failed(globalState, failurePolicy, kind, malformed,
                        Map.of(), LandRegistry.empty(), List.of());
            }
            UUID wid = snap.worldId();
            boolean exists;
            try {
                exists = worldCatalog.exists(wid);
            } catch (Exception catalogFailure) {
                StorageLoadFailureKind kind = classify(catalogFailure);
                GlobalStorageState globalState = policyToState(failurePolicy);
                String msg = "world catalog failure: " + catalogFailure.getMessage();
                logger.error(msg, catalogFailure);
                return StorageLoadOutcome.failed(
                        globalState, failurePolicy, kind, catalogFailure,
                        Map.of(), LandRegistry.empty(), List.of());
            }
            if (!exists) {
                isolatedWorlds.add(wid);
            } else {
                healthyWorlds.add(wid);
            }
        }

        // Build statuses and warnings
        for (UUID wid : isolatedWorlds) {
            statuses.put(wid, WorldStorageStatus.ISOLATED);
            // Count lands per isolated world
            long count = all.stream().filter(s -> s.worldId().equals(wid)).count();
            String warn = "WARN: world " + wid + " has " + count + " orphan land(s); entering ISOLATED (data retained, index not published, mutations rejected)";
            warnings.add(warn);
            logger.warn(warn);
        }
        for (UUID wid : healthyWorlds) {
            // If a world was both? Not possible because existence is per UUID.
            statuses.put(wid, WorldStorageStatus.HEALTHY);
        }

        // Collect healthy snapshots only
        for (LandSnapshot snap : all) {
            if (statuses.get(snap.worldId()) == WorldStorageStatus.HEALTHY) {
                healthySnapshots.add(snap);
            }
        }

        LandRegistry published;
        if (healthySnapshots.isEmpty()) {
            published = LandRegistry.empty();
        } else {
            // LandRegistry.from validates topology; any validation failure is fail-closed
            try {
                published = LandRegistry.from(healthySnapshots);
            } catch (RuntimeException failure) {
                StorageLoadFailureKind kind = classify(failure);
                GlobalStorageState globalState = policyToState(failurePolicy);
                logger.error("storage load failed to build registry: " + failure.getMessage(), failure);
                return StorageLoadOutcome.failed(globalState, failurePolicy, kind, failure,
                        statuses.isEmpty() ? Map.of() : Collections.unmodifiableMap(statuses),
                        LandRegistry.empty(), warnings);
            }
        }

        // Ensure isolated worlds are not in published registry (defensive)
        for (UUID wid : isolatedWorlds) {
            if (published.worlds().containsKey(wid)) {
                IllegalStateException invariant = new IllegalStateException("isolated world published: " + wid);
                GlobalStorageState globalState = policyToState(failurePolicy);
                logger.error("invariant violated", invariant);
                return StorageLoadOutcome.failed(globalState, failurePolicy, StorageLoadFailureKind.CORRUPTION, invariant,
                        Map.copyOf(statuses), LandRegistry.empty(), warnings);
            }
        }

        return StorageLoadOutcome.ready(Map.copyOf(statuses), published, List.copyOf(warnings));
    }

    private static GlobalStorageState policyToState(StorageFailurePolicy policy) {
        return switch (policy) {
            case STOP_SERVER -> GlobalStorageState.STOPPED;
            case LOCKDOWN -> GlobalStorageState.LOCKDOWN;
        };
    }

    static StorageLoadFailureKind classify(Throwable failure) {
        String msg = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        String causeMsg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
        String combined = msg + " " + causeMsg;
        if (combined.contains("schema") || combined.contains("schema_version") || combined.contains("migration")) {
            return StorageLoadFailureKind.SCHEMA_MISMATCH;
        }
        if (combined.contains("malformed") || combined.contains("malformed row")) {
            return StorageLoadFailureKind.MALFORMED_ROW;
        }
        if (failure instanceof SQLException || cause instanceof SQLException) {
            // Distinguish connection vs corruption by message hint
            if (combined.contains("connection") || combined.contains("unable to open")) {
                return StorageLoadFailureKind.CONNECTION_FAILURE;
            }
            return StorageLoadFailureKind.CORRUPTION;
        }
        if (failure instanceof IllegalStateException && combined.contains("null")) {
            return StorageLoadFailureKind.MALFORMED_ROW;
        }
        // Default to CORRUPTION for generic persistence failures
        return StorageLoadFailureKind.CORRUPTION;
    }
}
