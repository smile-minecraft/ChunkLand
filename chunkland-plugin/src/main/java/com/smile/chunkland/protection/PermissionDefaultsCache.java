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
 * <p>The cache owns one volatile {@link ConfigView} pairing the
 * {@link PermissionDefaultsSnapshot} with the {@link LandRuleService}
 * derived from its rule namespace. World names are
 * resolved to UUIDs only inside {@link #refresh()} — at bootstrap and on
 * every config reload — through the injected lookup; the lookups exposed
 * below never touch Bukkit, SQL or the network. Readers observe a single
 * volatile view, so a reload publishes new defaults atomically and
 * previously captured snapshot references keep answering with their own
 * values instead of observing the publish. The durable land layers
 * (bindings and land defaults) arrive through an attached
 * {@link LandAuthorisationSnapshot} source and are read per decision.
 *
 * <p>One decision must use one {@link ConfigView}: production enforcement
 * goes through {@link #provider()}, which captures the volatile view once
 * per {@code provide()} call and derives both the subject and rule halves
 * from it. The older {@link #subjectLookup()} and {@link #ruleLookup()}
 * each read the volatile view separately, so a reload landing between the
 * two reads can mix generations; they stay only for existing callers and
 * tests and must not back new production wiring.
 *
 * <p>Refresh never throws: a failing config read or resolver degrades to an
 * empty snapshot (fail-closed) and a warning, so a misbehaving reload
 * listener can never break {@code ConfigService#reload()}.
 */
public final class PermissionDefaultsCache implements ConfigReloadListener {

    /**
     * Immutable pair of the config subject/rule namespaces for one generation.
     * Published through a single volatile reference so one decision can hold
     * both halves without mixing versions across a reload.
     */
    public record ConfigView(PermissionDefaultsSnapshot snapshot, LandRuleService rules) {
        public ConfigView {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(rules, "rules");
        }
    }

    private final Supplier<ChunkLandConfig> configs;
    private final Function<String, Optional<UUID>> worldIds;
    private final Consumer<String> warnings;
    private volatile ConfigView current;
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
     * Current immutable subject/rule pair. One volatile read captures both
     * halves, so callers that derive a whole decision from the returned
     * value can never mix generations across a reload.
     */
    public ConfigView view() {
        return current;
    }

    /**
     * Atomic production provider: each {@code provide()} call captures one
     * {@link ConfigView} and derives both the subject and rule layers from
     * it, paired with one durable {@link LandAuthorisationSnapshot} read.
     * Reloads apply to new decisions; in-flight decisions finish on the
     * view they captured. Hot path stays memory-only.
     */
    public SnapshotPermissionContextProvider provider() {
        return SnapshotPermissionContextProvider.atomic(this::view, this::currentLandAuthorisation);
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

    /** Subject lookup over the live snapshot plus the durable land layers.
     *
     * <p>Legacy split path: each decision reads the volatile view again
     * through this lookup, separately from {@link #ruleLookup()}. Kept for
     * existing callers and tests; new production wiring must use
     * {@link #provider()} so one decision never spans two generations.
     */
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
     *
     * <p>Legacy split path: each decision reads the volatile view again
     * through this lookup, separately from {@link #subjectLookup()}. Kept
     * for existing callers and tests; new production wiring must use
     * {@link #provider()}.
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

    private ConfigView resolve() {
        try {
            ChunkLandConfig config = configs.get();
            PermissionDefaultsSnapshot snapshot =
                    PermissionDefaultsSnapshot.resolve(config, worldIds, warnings);
            return new ConfigView(snapshot, LandRuleService.fromRuleSnapshot(snapshot));
        } catch (RuntimeException failure) {
            warnings.accept("ChunkLand permission defaults refresh failed (fail-closed): "
                    + failure.getMessage());
            PermissionDefaultsSnapshot empty = PermissionDefaultsSnapshot.empty();
            return new ConfigView(empty, LandRuleService.fromRuleSnapshot(empty));
        }
    }
}
