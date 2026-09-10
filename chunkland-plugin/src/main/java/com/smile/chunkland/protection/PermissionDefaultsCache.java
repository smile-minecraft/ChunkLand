package com.smile.chunkland.protection;

import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigReloadListener;
import com.smile.chunkland.config.ReloadDiff;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reload-aware holder for the config permission defaults.
 *
 * <p>The cache owns one volatile {@link PermissionDefaultsSnapshot} plus the
 * {@link LandRuleService} derived from its rule namespace. World names are
 * resolved to UUIDs only inside {@link #refresh()} — at bootstrap and on
 * every config reload — through the injected lookup; the lookups exposed
 * below never touch Bukkit, SQL or the network. Readers observe a single
 * volatile view, so a reload publishes new defaults atomically and
 * previously captured snapshot references keep answering with their own
 * values instead of observing the publish. The durable land layers
 * (bindings and land defaults) arrive through an attached
 * {@link LandAuthorisationSnapshot} source and are read per decision.
 *
 * <p>Refresh never throws: a failing config read or resolver degrades to an
 * empty snapshot (fail-closed) and a warning, so a misbehaving reload
 * listener can never break {@code ConfigService#reload()}.
 */
public final class PermissionDefaultsCache implements ConfigReloadListener {

    private record View(PermissionDefaultsSnapshot snapshot, LandRuleService rules) {
        View {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(rules, "rules");
        }
    }

    private final Supplier<ChunkLandConfig> configs;
    private final Function<String, Optional<UUID>> worldIds;
    private final Consumer<String> warnings;
    private volatile View current;
    private volatile Supplier<LandAuthorisationSnapshot> landAuthorisations =
            LandAuthorisationSnapshot::empty;

    /**
     * @param configs  live config source (typically {@code ConfigService::current})
     * @param worldIds world name to world UUID, consulted only on
     *                 bootstrap/refresh; unknown names warn and are ignored
     * @param warnings sink for unknown/orphan entries and refresh failures;
     *                 {@code null} drops them
     */
    public PermissionDefaultsCache(Supplier<ChunkLandConfig> configs,
                                   Function<String, Optional<UUID>> worldIds,
                                   Consumer<String> warnings) {
        this.configs = configs != null ? configs : ChunkLandConfig::defaults;
        this.worldIds = worldIds;
        this.warnings = warnings != null ? warnings : ignored -> {
        };
        this.current = resolve();
    }

    /** Current immutable snapshot. Lock-free volatile read. */
    public PermissionDefaultsSnapshot snapshot() {
        return current.snapshot();
    }

    /**
     * Attach the durable land-layer source. Lookups created afterwards — and
     * every decision they answer — observe the attached source per call, so
     * attaching late (for example after the protection engine is built) still
     * applies to new decisions without rebuilding consumers. A
     * {@code null} source detaches back to empty (fail-closed).
     */
    public void attachLandAuthorisation(Supplier<LandAuthorisationSnapshot> source) {
        this.landAuthorisations =
                source != null ? source : LandAuthorisationSnapshot::empty;
    }

    /** Subject lookup over the live snapshot plus the durable land layers. */
    public SubjectPermissionLookup subjectLookup() {
        return new ConfigSubjectPermissionLookup(this::snapshot, this::currentLandAuthorisation);
    }

    private LandAuthorisationSnapshot currentLandAuthorisation() {
        try {
            LandAuthorisationSnapshot snapshot = landAuthorisations.get();
            return snapshot != null ? snapshot : LandAuthorisationSnapshot.empty();
        } catch (RuntimeException failure) {
            return LandAuthorisationSnapshot.empty();
        }
    }

    /**
     * Rule lookup over the live rule service. The service reference is
     * re-read per call so reloads apply without rebuilding this cache's
     * consumers; a failing read degrades to empty (fail-closed).
     */
    public LandRuleLookup ruleLookup() {
        return (landId, rule, snapshot) -> {
            try {
                return current.rules().getRule(landId, rule, snapshot);
            } catch (RuntimeException failure) {
                return Optional.empty();
            }
        };
    }

    /** Re-resolve from the live config; invoked on bootstrap and every reload. */
    public void refresh() {
        current = resolve();
    }

    @Override
    public void onConfigReload(ReloadDiff diff) {
        Objects.requireNonNull(diff, "diff");
        refresh();
    }

    private View resolve() {
        try {
            ChunkLandConfig config = configs.get();
            PermissionDefaultsSnapshot snapshot =
                    PermissionDefaultsSnapshot.resolve(config, worldIds, warnings);
            return new View(snapshot, LandRuleService.fromRuleSnapshot(snapshot));
        } catch (RuntimeException failure) {
            warnings.accept("ChunkLand permission defaults refresh failed (fail-closed): "
                    + failure.getMessage());
            PermissionDefaultsSnapshot empty = PermissionDefaultsSnapshot.empty();
            return new View(empty, LandRuleService.fromRuleSnapshot(empty));
        }
    }
}
