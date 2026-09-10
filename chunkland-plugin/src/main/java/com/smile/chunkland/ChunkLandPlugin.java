package com.smile.chunkland;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.chunkland.adapter.AceLibBridge;
import com.smile.chunkland.adapter.AceLibLifecycle;
import com.smile.chunkland.api.ChunkLandApi;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.capability.Capabilities;
import com.smile.chunkland.capability.M0CapabilityProbe;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.ClaimValidator;
import com.smile.chunkland.claim.SnapshotClaimValidator;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.command.ClaimCommandHandler;
import com.smile.chunkland.command.ConfirmCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.PluginManagementGateResolver;
import com.smile.chunkland.command.SubLandCommandHandler;
import com.smile.chunkland.command.VisualizationDebugCommand;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.config.WorldSettings;
import com.smile.chunkland.config.YamlFileConfigLoader;
import com.smile.chunkland.economy.UnavailableVaultBridge;
import com.smile.chunkland.economy.VaultBridge;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaHydrator;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.message.M0MessageProbe;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SubLandAtomicCommit;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.ProtectionListener;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.protection.SubjectPermissionLookup;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.api.ChunkLandReadApi;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.api.ProtectionDepthLookup;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import com.smile.chunkland.runtime.rule.LandRuleService;
import com.smile.chunkland.runtime.vertical.DepthExtendEventAdapter;
import com.smile.chunkland.runtime.vertical.DepthExtendService;
import com.smile.chunkland.runtime.vertical.DepthStore;
import com.smile.chunkland.runtime.vertical.VerticalDepths;
import com.smile.chunkland.selection.FoliaSelectionTimeoutScheduler;
import com.smile.chunkland.selection.FoliaSelectionParticleSink;
import com.smile.chunkland.selection.FoliaVisualizationTickScheduler;
import com.smile.chunkland.selection.SelectionLifecycleListener;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionVisualizationRenderer;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.DepthExtensionPort;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandDepthSource;
import com.smile.chunkland.subland.SubLandMutationRunner;
import com.smile.chunkland.wand.WandGiveHandler;
import com.smile.chunkland.wand.WandSafetyListener;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ChunkLand server plugin entry point.
 *
 * <p>Lifecycle is intentionally thin: all AceLib acquisition and fail-closed policy
 * live in {@link AceLibBridge} / {@link AceLibLifecycle}, which are testable without a
 * server. This class only wires the Bukkit callbacks to those seams. After a ready
 * AceLib API is acquired it builds two temporary M0 probes:</p>
 * <ul>
 *   <li>{@link M0MessageProbe} for the M0-08 message pipeline smoke.</li>
 *   <li>{@link M0CapabilityProbe} for the M0-07 capability (scheduler / GUI / Form /
 *       {@code cancelAll}) smoke.</li>
 * </ul>
 *
 * <p>Both probes must be fail-closed (their {@code tryBuild} returns empty when the API
 * is missing or not ready), so this class never NPEs on a missing AceLib facade.</p>
 *
 * <p>The config-system wiring owns a {@link ConfigService} that holds the parsed
 * {@code config.yml} snapshot. Reload is exposed as a programmatic API on this class
 * so a future command-tree task can wire a {@code /land reload} subcommand without
 * touching the rest of the lifecycle. The Bukkit {@code /reload} command is
 * intentionally NOT supported.</p>
 *
 * <p>The {@code chunkland} command does NOT carry a top-level Bukkit {@code permission}
 * entry (see {@code plugin.yml}); instead each subcommand is gated explicitly here:</p>
 * <ul>
 *   <li>{@code m0message} requires {@link #PERMISSION_M0_MESSAGE}.</li>
 *   <li>{@code m0test} requires {@link #PERMISSION_M0_TEST}.</li>
 * </ul>
 *
 * <p>Denied senders see a localised notice and never reach the probe.</p>
 */
public final class ChunkLandPlugin extends JavaPlugin {
    public static final String NAME = "ChunkLand";

    /** Permission key for {@code /chunkland m0message}. */
    public static final String PERMISSION_M0_MESSAGE = "chunkland.debug.m0message";
    /** Permission key for {@code /chunkland m0test}. */
    public static final String PERMISSION_M0_TEST = "chunkland.debug.m0test";

    /**
     * Persisted protection depth used until per-claim derivation from the
     * selection plane lands with vertical-mode config. Matches the depth the
     * claim tests standardise on.
     */
    static final int CLAIM_DEPTH_FALLBACK = 64;

    /** Compensation retries shared by live claims and startup recovery. */
    static final int CLAIM_COMPENSATION_RETRIES = 3;

    /**
     * Conservative world-minimum height used when a world has no registered
     * minimum in the startup snapshot. Matches the vanilla overworld floor so
     * {@code FULL_HEIGHT} reads stay protective rather than collapsing to
     * zero; a live Bukkit minimum adapter replaces this table when it lands.
     */
    static final int WORLD_MIN_HEIGHT_FALLBACK = -64;

    private final AceLibBridge bridge = new AceLibBridge();
    private Optional<M0MessageProbe> messagePipeline = Optional.empty();
    private Optional<M0CapabilityProbe> capabilityProbe = Optional.empty();
    private Optional<Capabilities> capabilities = Optional.empty();
    private Optional<ConfigService> configService = Optional.empty();
    private Optional<ChunkLandMessagePipeline> landMessagePipeline = Optional.empty();
    private LandCommand landCommand;
    private WandSafetyListener wandSafetyListener;
    private SelectionSessionManager selectionSessionManager;
    private SelectionLifecycleListener selectionLifecycleListener;
    private SelectionVisualizationTaskController visualizationController;
    private Optional<SafeScheduler> visualizationScheduler = Optional.empty();
    private SelectionStructureRevisionLookup selectionStructureRevisions;
    private LandRegistryStore protectionStore;
    private ProtectionEngine protectionEngine;
    private SnapshotProtectionDepthLookup protectionDepthLookup;
    private ProtectionListener protectionListener;
    private ClaimStartupBootstrap claimStartup;
    private ClaimSaga claimSaga;
    private OwnerQuotaService claimQuotas;
    private LogicalReservationRegistry claimReservations;
    private ExecutorService claimExecutor;
    private SubLandConfirmService subLandConfirm;
    private SubLandMutationRunner subLandRunner;
    private DepthExtendService depthExtendService;
    /**
     * Read-API lifecycle gate. Each enabled generation owns one instance;
     * holders capture it. Disable deactivates it so cached holders stay
     * empty and never revive on the next enable. Volatile reads only, so
     * the hot path performs no Bukkit, SQL or other I/O.
     */
    static final class ReadApiLifecycle {
        volatile boolean active = true;
    }

    private volatile ReadApiLifecycle readApiLifecycle;

    // Deterministic hook for the immediate-cleanup read-invalidation window.
    // Invoked at the very start of performFullCleanup before any cleanup step,
    // without holding any lock. Production leaves this null; tests use a
    // latch-based hook to pause cleanup mid-flight and prove cached reads are
    // already empty. The hook never touches domain state and never propagates
    // failures into cleanup.
    volatile Runnable cleanupStartedHookForTest;

    public ChunkLandPlugin() {
    }

    /**
     * @return the AceLib bridge (non-null after construction); its state is populated
     *         during {@link #onEnable()} and cleared during {@link #onDisable()}.
     */
    public AceLibBridge getBridge() {
        return bridge;
    }

    /**
     * @return the live config service, or empty when bootstrap failed (the failure
     *         is logged in {@link #onEnable()}). Reload is a programmatic API —
     *         a future command-tree task will gate it behind a {@code /land reload}
     *         subcommand; this class intentionally does NOT register one yet.
     */
    public Optional<ConfigService> getConfigService() {
        return configService;
    }

    @Override
    public void onEnable() {
        boolean ready = AceLibLifecycle.enable(
            bridge,
            AceLibBridge.fromServicesManager(getServer().getServicesManager()),
            getLogger(),
            () -> getServer().getPluginManager().disablePlugin(this)
        );
        if (!ready) {
            return;
        }
        // Config-system wiring: bootstrap the config service from data-folder/config.yml.
        // On a missing or invalid file we keep the plugin alive with defaults
        // and log a warning — the admin can fix the file and call reload() later.
        try {
            YamlFileConfigLoader loader = new YamlFileConfigLoader(
                    getDataFolder().toPath().resolve("config.yml"));
            this.configService = Optional.of(new ConfigService(loader));
        } catch (RuntimeException ex) {
            getLogger().warning(
                    "ChunkLand config bootstrap failed; starting with defaults. "
                            + "Reason: " + ex.getMessage());
            // Fall back to a service backed by the embedded default YAML so
            // downstream readers always see a valid snapshot.
            this.configService = Optional.of(new ConfigService(
                    new com.smile.chunkland.config.ResourceConfigLoader(
                            getClass(), "/config.yml")));
        }
        ConfigService activeConfig = this.configService.orElseThrow(
                () -> new IllegalStateException("ChunkLand config service is unavailable"));
        SelectionStructureRevisionLookup structureRevisions =
                SelectionStructureRevisionLookup.unavailable();
        this.selectionStructureRevisions = structureRevisions;
        // Selection boundary particles: a dedicated SafeScheduler built through the public
        // AceLib factory (player-scoped ticks only, never global). Empty when AceLib is not
        // ready — rendering then stays dormant while selection data keeps working.
        this.visualizationScheduler = tryBuildVisualizationScheduler(this, bridge.getApi());
        SelectionVisualizationTaskController visualization = visualizationScheduler
                .map(scheduler -> (SelectionVisualizationTaskController) new SelectionVisualizationRenderer(
                        new FoliaVisualizationTickScheduler(
                                uuid -> getServer().getPlayer(uuid), () -> visualizationScheduler),
                        new FoliaSelectionParticleSink(uuid -> getServer().getPlayer(uuid)),
                        () -> activeConfig.current().selection().visualizationBudget()))
                .orElseGet(SelectionVisualizationTaskController::noop);
        this.visualizationController = visualization;
        getLogger().info("ChunkLand selection visualization: "
                + (visualizationScheduler.isPresent() ? "scheduler ready" : "dormant (AceLib scheduler unavailable)")
                + ", budget=" + activeConfig.current().selection().visualizationBudget());
        this.selectionSessionManager = new SelectionSessionManager(
                new FoliaSelectionTimeoutScheduler(this, uuid -> getServer().getPlayer(uuid)),
                visualization,
                SelectionNotifier.noop(),
                Clock.systemUTC()::instant,
                () -> activeConfig.current().selection().sessionTimeout(),
                uuid -> Optional.ofNullable(getServer().getWorld(uuid)).map(org.bukkit.World::getName),
                structureRevisions);
        this.selectionLifecycleListener = new SelectionLifecycleListener(selectionSessionManager);
        this.configService.ifPresent(service -> service.addListener(selectionSessionManager));
        // Only build the message probe after a ready AceLib API is held. If AceLib is
        // missing/not-ready, bridge.getApi() is null and tryBuild returns empty (fail-closed,
        // no NPE). The probe command reports "not ready" instead of pretending to send.
        this.messagePipeline = M0MessageProbe.tryBuild(this, bridge.getApi());
        // M0-07 capability probe: independent of the message probe; both return empty when
        // the API is not ready. The probe owns a SafeScheduler created via the public
        // AceLibScheduler.create(...) factory and exposes four smoke paths.
        this.capabilityProbe = M0CapabilityProbe.tryBuild(this, bridge.getApi());
        // Land message pipeline for /land ReplySink (same key+vars contract). Fail-closed
        // when AceLib is not ready; the sink is fail-closed with no fallback output.
        Locale defaultLocale = configService.map(s -> s.current().messages().defaultLocale()).orElse(Locale.US);
        this.landMessagePipeline = ChunkLandMessagePipeline.tryBuild(this, bridge.getApi(), defaultLocale);
        // Protection engine skeleton: validated at construction (incomplete
        // registry throws and lands in the fail-closed path below); the store
        // starts empty so every position is wilderness (vanilla) until later
        // milestones publish real snapshots and a real context provider.
        // Built before the land command so the management gate resolver reads
        // the same live snapshots the engine enforces.
        this.protectionStore = new LandRegistryStore();
        this.readApiLifecycle = new ReadApiLifecycle();
        this.protectionEngine = buildProtectionEngine(this.protectionStore);
        // Formal per-world vertical-mode read path: the lookup resolves the
        // effective depth from the live config snapshot plus the
        // startup-injected name/minimum tables, so the hot path performs only
        // a volatile snapshot read and map lookups (never Bukkit or SQL).
        // Stored depths are never rewritten by a mode switch; worlds or
        // minima missing from the snapshot fall back explicitly.
        this.protectionDepthLookup = buildProtectionDepthLookup(
                activeConfig::current, snapshotWorldNames(), snapshotWorldMinimums());
        // Startup claim recovery: open persistence, rebuild durable domain
        // commits into the shared protection store, and scan without blocking.
        // A failed bootstrap keeps the empty fail-closed runtime; the scan
        // itself never refunds and never marks rows active on rebuild failure.
        // The bridge is resolved explicitly so recovery refunds through a real
        // provider when one is available; without one it stays fail-safe.
        try {
            this.claimStartup = ClaimStartupBootstrap.start(
                    getDataFolder().toPath().resolve(ClaimStartupBootstrap.DATABASE_FILE_NAME),
                    this.protectionStore,
                    getLogger(),
                    resolveEconomyBridge());
        } catch (RuntimeException | Error failure) {
            getLogger().warning("ChunkLand claim recovery bootstrap failed; "
                    + "runtime stays empty until the next restart: " + failure.getMessage());
            this.claimStartup = null;
        }
        // Formal claim flow: the saga shares the bootstrap ledger, Economy and
        // rebuilder so live claims and startup recovery converge on one durable
        // row and one refund cache. When recovery never started, the handler
        // replies claim.unavailable instead of pretending to run. While the
        // recovery scan is still in flight (or has failed) the handler stays
        // fail-closed on recovery_pending/recovery_failed, so a stale empty
        // runtime can never hide a collision.
        // Formal SubLand flow: shares the recovery bootstrap store and the
        // protection snapshot so chat confirmations, durable commits and the
        // runtime publish converge on one revision source. Assembly failure
        // keeps the slot fail-closed instead of running half-wired.
        SubLandCommandHandler sublandHandler = subLandHandler(activeConfig);
        this.landCommand = new LandCommand(
                buildLandHandlers(this.selectionSessionManager, claimRunner(), claimScanSupplier(),
                        structureRevisions, sublandHandler),
                null, buildManagementGateResolver(this.protectionStore));
        // Capture the underlying capabilities bundle before Wand listener registration can fail,
        // so registration failure does not leak the M0 SafeScheduler.
        this.capabilities = capabilityProbe.map(M0CapabilityProbe::capabilities);
        // Wand safety listener: native Bukkit listener for selection wand protection
        this.wandSafetyListener = new WandSafetyListener();
        this.protectionListener = new ProtectionListener(this.protectionEngine);
        try {
            registerWandListener(this.wandSafetyListener);
            registerSelectionListener(this.selectionLifecycleListener);
            registerProtectionListener(this.protectionListener);
        } catch (RuntimeException ex) {
            getLogger().warning("ChunkLand wand safety listener registration failed; disabling plugin: " + ex.getMessage());
            boolean disableThrew = false;
            RuntimeException disableCause = null;
            try {
                getServer().getPluginManager().disablePlugin(this);
            } catch (RuntimeException disableEx) {
                disableThrew = true;
                disableCause = disableEx;
            }
            boolean stillEnabled;
            try {
                stillEnabled = getServer().getPluginManager().isPluginEnabled(this);
            } catch (RuntimeException e) {
                stillEnabled = isEnabled();
            }
            // Ensure complete cleanup on every terminal path. Do not clear the listener
            // before the disable attempt; clean up only after the enabled-state check.
            // Use the single idempotent helper so message/capability/land/config/bridge
            // are not leaked when disable is a no-op or throws.
            performFullCleanup();
            if (disableThrew) {
                throw new IllegalStateException("ChunkLand wand safety listener registration failed and self-disable failed", disableCause);
            }
            if (stillEnabled) {
                throw new IllegalStateException("ChunkLand wand safety listener registration failed and plugin remains enabled after self-disable");
            }
            return;
        }
    }

    static Map<String, LandCommand.Handler> buildLandHandlers() {
        Map<String, LandCommand.Handler> base = new HashMap<>(LandCommand.defaultStubHandlers());
        base.put("wand", new WandGiveHandler());
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers: the base map with {@code claim}
     * replaced by the formal saga handler.
     *
     * <p>A null manager or runner keeps the slot fail-safe: the handler then
     * replies without touching selection or the saga, so a half-wired server
     * never pretends a claim ran. The recovery scan gate blocks claims while
     * the startup scan is still in flight or has failed; a null gate means
     * no check (test seam).
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner) {
        return buildLandHandlers(selections, runner, null);
    }

    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan) {
        return buildLandHandlers(selections, runner, recoveryScan,
                SelectionStructureRevisionLookup.unavailable());
    }

    /**
     * Production {@code /land} handlers with an explicit structure revision
     * source for chat confirmations.
     *
     * <p>The same lookup instance should back both the selection manager and
     * the confirmation handler so the pre-check and the lifecycle
     * invalidation observe one live value. Until a real source exists the
     * unavailable seam fails targeted confirmations closed while target-less
     * claims proceed on the selection token alone.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures) {
        return buildLandHandlers(selections, runner, recoveryScan, structures, null);
    }

    /**
     * Production {@code /land} handlers with the SubLand mutation flow wired.
     *
     * <p>A null SubLand handler keeps the slot fail-closed: it replies
     * {@code command.land.subland.failed} with {@code subland.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers());
        base.put("claim", selections == null
                ? (sender, args, sink) -> sink.reply("command.land.claim.failed", Map.of("reason", "claim.unavailable"))
                : new ClaimCommandHandler(selections, runner, recoveryScan));
        SelectionStructureRevisionLookup activeStructures =
                structures == null ? SelectionStructureRevisionLookup.unavailable() : structures;
        base.put("confirm", selections == null
                ? (sender, args, sink) -> sink.reply("command.land.claim.failed", Map.of("reason", "claim.unavailable"))
                : new ConfirmCommandHandler(selections, runner, recoveryScan, activeStructures));
        base.put("subland", subland == null
                ? (sender, args, sink) -> sink.reply("command.land.subland.failed", Map.of("reason", "subland.unavailable"))
                : subland);
        return Map.copyOf(base);
    }

    /**
     * Live parent structure-revision source for SubLand confirmations, read
     * from the shared runtime snapshot. A null store or an unknown land
     * yields empty, so confirmation fails closed instead of running against
     * a revision nobody can vouch for.
     */
    static SelectionStructureRevisionLookup buildSubLandStructureLookup(LandRegistryStore store) {
        if (store == null) {
            return SelectionStructureRevisionLookup.unavailable();
        }
        return landId -> {
            Objects.requireNonNull(landId, "landId");
            try {
                com.smile.chunkland.api.land.LandSnapshot snapshot = store.snapshot().land(landId);
                if (snapshot == null) {
                    return OptionalLong.empty();
                }
                return OptionalLong.of(snapshot.structureRevision());
            } catch (RuntimeException unresolved) {
                return OptionalLong.empty();
            }
        };
    }

    /**
     * Production effective-floor source for SubLand depth checks, resolved
     * from the runtime's stored per-chunk depths with the legacy fallback for
     * rows that predate depth persistence. The floor is the highest stored
     * value across the parent's chunks, so a candidate clears every chunk it
     * could cover; a null store fails closed by throwing instead of guessing.
     * Per-footprint floors and world-minimum interpretation arrive with the
     * vertical follow-up; until then this stays on stored-depth semantics.
     */
    static SubLandDepthSource buildSubLandDepthSource(LandRegistryStore store) {
        if (store == null) {
            return parent -> {
                throw new IllegalStateException("subland depth source is unavailable");
            };
        }
        return parent -> {
            Objects.requireNonNull(parent, "parent");
            com.smile.chunkland.runtime.index.LandRegistry snapshot = store.snapshot();
            int floor = CLAIM_DEPTH_FALLBACK;
            boolean seen = false;
            for (com.smile.chunkland.api.land.ChunkKey chunk : parent.chunks()) {
                int stored = snapshot.storedDepth(chunk)
                        .orElse(VerticalDepths.LEGACY_STORED_FALLBACK_Y);
                if (!seen || stored > floor) {
                    floor = stored;
                    seen = true;
                }
            }
            return floor;
        };
    }

    /**
     * Assembles the formal SubLand mutation runner from its production parts.
     * Any missing part returns null so the caller keeps {@code /land subland}
     * fail-closed instead of running half-wired. Depth extension stays
     * deny-all: explicit depth confirmation arrives with the follow-up queue,
     * so deep candidates fail closed until then.
     */
    static SubLandMutationRunner buildSubLandRunner(
            PersistenceStore persistence,
            LandRegistryStore registry,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            LimitSettings limits,
            Clock clock) {
        if (persistence == null || registry == null || selections == null
                || confirm == null || depths == null || limits == null || clock == null) {
            return null;
        }
        return new SubLandMutationRunner(
                new SqliteLandRepository(persistence),
                new SubLandAtomicCommit(persistence),
                registry,
                selections,
                confirm,
                depths,
                DepthExtensionPort.denyAll(),
                limits,
                clock);
    }

    /**
     * Assembles the production {@code /land subland} handler. Any missing
     * part returns null so the handler map keeps the slot fail-closed.
     */
    static SubLandCommandHandler buildSubLandHandler(
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandMutationRunner runner,
            SelectionStructureRevisionLookup structures) {
        if (selections == null || confirm == null || runner == null || structures == null) {
            return null;
        }
        return new SubLandCommandHandler(selections, confirm, runner, structures);
    }

    /**
     * Resolves the Economy bridge for claim recovery and live claims.
     *
     * <p>No Vault provider hookup exists on the classpath yet, so production
     * currently resolves to the explicitly unavailable bridge. Player claims
     * fail closed on {@code economy.unavailable} before any ledger row (the
     * saga checks availability ahead of all side effects); recovery refunds
     * through it stay fail-safe with unrefunded rows retryable. When a Vault
     * hookup lands, it plugs in here and both paths pick it up without
     * touching the bootstrap or the saga.
     */
    private static VaultBridge resolveEconomyBridge() {
        return new UnavailableVaultBridge();
    }

    /**
     * Interim claim pricing: no pricing shape exists in {@code config.yml}
     * yet, so every claim prices at zero in the bootstrap currency. Player
     * claims fail closed on {@code pricing.unavailable} before any ledger,
     * charge, or domain side effect, so the placeholder can never create a
     * silent free land in production; server land stays free by design.
     * Introducing real tiers later only replaces this method.
     */
    private static PricingTable claimPricing() {
        Currency currency = ClaimStartupBootstrap.CLAIM_CURRENCY;
        return PricingTable.of(List.of(PricingTier.of(PricingTier.UNBOUNDED, Money.zero(currency))));
    }

    /**
     * Assembles the formal claim saga from its production parts. Package
     * visible so integration tests drive the same assembly the server uses.
     */
    static ClaimSaga buildClaimSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor) {
        return buildClaimSaga(registryStore, selections, quotas, pricing, reservations,
                ledger, economy, rebuilder, asyncExecutor,
                SelectionStructureRevisionLookup.unavailable());
    }

    /**
     * Assembles the formal claim saga with an explicit structure revision
     * source for the saga-time confirmation revalidation. Until a real source
     * exists the unavailable seam fails targeted confirmations closed while
     * target-less claims proceed on the selection token alone.
     */
    static ClaimSaga buildClaimSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor,
            SelectionStructureRevisionLookup structures) {
        return buildClaimSaga(registryStore, selections, quotas, pricing, reservations,
                ledger, economy, rebuilder, asyncExecutor, structures,
                WorldClaimPolicy.allowAll());
    }

    /**
     * Production claim saga with the per-world {@code claim-enabled} gate.
     *
     * <p>The policy runs inside the step-one validator, ahead of every quota,
     * reservation, ledger and economy side effect. A {@code null} policy
     * fails closed to deny-all so a half-wired assembly can never treat an
     * unknown world as enabled.
     */
    static ClaimSaga buildClaimSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor,
            SelectionStructureRevisionLookup structures,
            WorldClaimPolicy worldPolicy) {
        OwnerQuotaService activeQuotas = Objects.requireNonNull(quotas, "quotas");
        SelectionStructureRevisionLookup activeStructures = structures == null
                ? SelectionStructureRevisionLookup.unavailable()
                : structures;
        WorldClaimPolicy activePolicy = worldPolicy == null
                ? WorldClaimPolicy.denyAll("world.unknown")
                : worldPolicy;
        ClaimValidator validator = new SnapshotClaimValidator(
                Objects.requireNonNull(registryStore, "registryStore"),
                actorUuid -> selections.sessionFor(actorUuid)
                        .map(session -> OptionalLong.of(session.selectionRevision()))
                        .orElseGet(OptionalLong::empty),
                actorUuid -> selections.sessionFor(actorUuid)
                        .map(session -> OptionalLong.of(session.sessionGeneration()))
                        .orElseGet(OptionalLong::empty),
                chunk -> CLAIM_DEPTH_FALLBACK,
                activeQuotas::chunkCommitted,
                targetLandId -> activeStructures.currentRevision(targetLandId),
                activePolicy);
        return new ClaimSaga(validator, activeQuotas,
                Objects.requireNonNull(pricing, "pricing"),
                Objects.requireNonNull(reservations, "reservations"),
                Objects.requireNonNull(ledger, "ledger"),
                Objects.requireNonNull(economy, "economy"),
                Objects.requireNonNull(rebuilder, "rebuilder"),
                Clock.systemUTC(),
                Objects.requireNonNull(asyncExecutor, "asyncExecutor"),
                CLAIM_COMPENSATION_RETRIES);
    }

    /**
     * Production per-world claim gate over the live config snapshot.
     *
     * <p>A {@code null} config service or resolver fails closed to deny-all
     * ({@code world.unknown}) so a half-wired server never treats an unknown
     * world as enabled. The returned policy reads
     * {@link ConfigService#current()} on every call, so reloads apply without
     * rebuilding the saga.
     */
    static WorldClaimPolicy buildWorldClaimPolicy(ConfigService config,
            Function<UUID, Optional<String>> worldNames) {
        if (config == null || worldNames == null) {
            return WorldClaimPolicy.denyAll("world.unknown");
        }
        return WorldClaimPolicy.fromConfig(config::current, worldNames);
    }

    /**
     * Formal production depth lookup over the live config snapshot.
     *
     * <p>Per-world {@code vertical-mode} resolves from
     * {@code configs.get()} (one volatile read) through the
     * startup-injected UUID-to-name table; world minima resolve from the
     * startup-injected minimum table. The hot path therefore performs no
     * Bukkit, SQL or network access. A {@code null} input fails closed to
     * the permissive-shape default ({@code PER_CHUNK_DEPTH} with the explicit
     * minimum fallback) without throwing, and every seam failure inside the
     * lambdas degrades the same way. Stored depths are never rewritten by a
     * mode switch — that guarantee lives in
     * {@link SnapshotProtectionDepthLookup}.
     *
     * @param configs live snapshot source (typically {@code ConfigService::current});
     *        {@code null} or throwing means every world reads stored depths
     * @param uuidToNameSnapshot world UUID to config-name table captured at
     *        startup; worlds absent from it read stored depths
     * @param worldMinSnapshot world UUID to minimum block Y captured at
     *        startup; worlds absent from it use {@link #WORLD_MIN_HEIGHT_FALLBACK}
     */
    static SnapshotProtectionDepthLookup buildProtectionDepthLookup(
            Supplier<ChunkLandConfig> configs,
            Map<UUID, String> uuidToNameSnapshot,
            Map<UUID, Integer> worldMinSnapshot) {
        Map<UUID, String> names = uuidToNameSnapshot == null ? Map.of() : uuidToNameSnapshot;
        Map<UUID, Integer> mins = worldMinSnapshot == null ? Map.of() : worldMinSnapshot;
        return new SnapshotProtectionDepthLookup(
                worldId -> modeForWorld(configs, names, worldId),
                worldId -> minForWorld(mins, worldId));
    }

    private static VerticalMode modeForWorld(Supplier<ChunkLandConfig> configs,
            Map<UUID, String> names, UUID worldId) {
        try {
            if (configs == null || worldId == null) {
                return VerticalMode.defaultMode();
            }
            ChunkLandConfig config = configs.get();
            if (config == null) {
                return VerticalMode.defaultMode();
            }
            String name = names.get(worldId);
            if (name == null) {
                return VerticalMode.defaultMode();
            }
            WorldSettings settings = config.worlds().get(name);
            if (settings == null || settings.verticalMode() == null) {
                return VerticalMode.defaultMode();
            }
            return settings.verticalMode();
        } catch (RuntimeException failure) {
            return VerticalMode.defaultMode();
        }
    }

    private static int minForWorld(Map<UUID, Integer> mins, UUID worldId) {
        try {
            Integer min = mins == null ? null : mins.get(worldId);
            return min == null ? WORLD_MIN_HEIGHT_FALLBACK : min.intValue();
        } catch (RuntimeException failure) {
            return WORLD_MIN_HEIGHT_FALLBACK;
        }
    }

    /**
     * Startup snapshot of world UUID to config name, read once at enable.
     * Later world loads are absent from the table and read stored depths
     * (fail-closed); a live minimum adapter replaces this table when it lands.
     */
    private Map<UUID, String> snapshotWorldNames() {
        Map<UUID, String> names = new HashMap<>();
        try {
            for (org.bukkit.World world : getServer().getWorlds()) {
                if (world == null) {
                    continue;
                }
                try {
                    names.put(world.getUID(), world.getName());
                } catch (RuntimeException ignored) {
                    // One unreadable world must not poison the rest of the table.
                }
            }
        } catch (RuntimeException ignored) {
            // No world list at all: every lookup falls back to stored depths.
        }
        return Map.copyOf(names);
    }

    /**
     * Startup snapshot of world UUID to minimum block Y, read once at enable.
     * Worlds absent from the table use {@link #WORLD_MIN_HEIGHT_FALLBACK}
     * under {@code FULL_HEIGHT}; a live Bukkit minimum adapter replaces this
     * table when it lands.
     */
    private Map<UUID, Integer> snapshotWorldMinimums() {
        Map<UUID, Integer> mins = new HashMap<>();
        try {
            for (org.bukkit.World world : getServer().getWorlds()) {
                if (world == null) {
                    continue;
                }
                try {
                    mins.put(world.getUID(), world.getMinHeight());
                } catch (RuntimeException ignored) {
                    // One unreadable world must not poison the rest of the table.
                }
            }
        } catch (RuntimeException ignored) {
            // No world list at all: every FULL_HEIGHT read uses the fallback.
        }
        return Map.copyOf(mins);
    }

    /**
     * Live claim runner sharing the bootstrap ledger, Economy and rebuilder.
     * Null when recovery never started: the handler then replies
     * {@code claim.unavailable}. Assembly failure degrades the same way and
     * is logged, so claiming can never run half-wired.
     *
     * <p>The returned runner does not gate on the recovery scan itself: the
     * scan gate lives in the command handler (see {@link #claimScanSupplier}),
     * so the saga stays a pure priced execution path shared by tests.
     */
    private ClaimCommandHandler.ClaimRunner claimRunner() {
        ClaimStartupBootstrap bootstrap = this.claimStartup;
        SelectionSessionManager selections = this.selectionSessionManager;
        if (bootstrap == null || selections == null) {
            return null;
        }
        try {
            ConfigService config = this.configService.orElseThrow(
                    () -> new IllegalStateException("ChunkLand config service is unavailable"));
            this.claimQuotas = new OwnerQuotaService(new LimitResolver(config.current()));
            // Restart hydration: the quota service is process-local and starts
            // from zero, so restore committed counts from the authoritative
            // durable lands before serving any claim. A failed hydration keeps
            // claiming unavailable instead of enforcing limits from zero and
            // letting owners breach them.
            try {
                OwnerQuotaHydrator.hydrateBlocking(
                        this.claimQuotas, new SqliteLandRepository(bootstrap.store()));
            } catch (RuntimeException | Error failure) {
                getLogger().warning("ChunkLand quota hydration failed; "
                        + "/land claim stays unavailable: " + failure.getMessage());
                this.claimQuotas = null;
                return null;
            }
            this.claimReservations = new LogicalReservationRegistry();
            this.claimExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "chunkland-claim");
                thread.setDaemon(true);
                return thread;
            });
            this.claimSaga = buildClaimSaga(this.protectionStore, selections,
                    this.claimQuotas, claimPricing(), this.claimReservations,
                    bootstrap.ledger(), bootstrap.economy(), bootstrap.rebuilder(), this.claimExecutor,
                    this.selectionStructureRevisions,
                    buildWorldClaimPolicy(config,
                            uuid -> Optional.ofNullable(getServer().getWorld(uuid))
                                    .map(org.bukkit.World::getName)));
            return this.claimSaga::claim;
        } catch (RuntimeException failure) {
            getLogger().warning("ChunkLand claim flow assembly failed; "
                    + "/land claim stays unavailable: " + failure.getMessage());
            this.claimSaga = null;
            this.claimQuotas = null;
            this.claimReservations = null;
            if (this.claimExecutor != null) {
                try {
                    this.claimExecutor.shutdownNow();
                } catch (RuntimeException ignored) {
                }
                this.claimExecutor = null;
            }
            return null;
        }
    }

    /**
     * Live SubLand handler sharing the recovery bootstrap store and the
     * protection snapshot. Null when any part is missing or assembly fails:
     * the handler map then keeps {@code /land subland} fail-closed. Assembly
     * failure is logged, so a half-wired mutation can never run silently.
     */
    private SubLandCommandHandler subLandHandler(ConfigService activeConfig) {
        ClaimStartupBootstrap bootstrap = this.claimStartup;
        SelectionSessionManager selections = this.selectionSessionManager;
        LandRegistryStore registry = this.protectionStore;
        if (bootstrap == null || selections == null || registry == null || activeConfig == null) {
            getLogger().warning("ChunkLand subland flow assembly skipped; "
                    + "/land subland stays fail-closed: missing bootstrap, selections, registry or config");
            return null;
        }
        try {
            SubLandConfirmService confirm = new SubLandConfirmService();
            SubLandDepthSource depths = buildSubLandDepthSource(registry);
            SelectionStructureRevisionLookup lookup = buildSubLandStructureLookup(registry);
            SubLandMutationRunner runner = buildSubLandRunner(
                    bootstrap.store(), registry, selections, confirm, depths,
                    activeConfig.current().limits(), Clock.systemUTC());
            if (runner == null) {
                getLogger().warning("ChunkLand subland flow assembly failed; "
                        + "/land subland stays fail-closed: runner unavailable");
                return null;
            }
            this.subLandConfirm = confirm;
            this.subLandRunner = runner;
            return new SubLandCommandHandler(selections, confirm, runner, lookup);
        } catch (RuntimeException failure) {
            getLogger().warning("ChunkLand subland flow assembly failed; "
                    + "/land subland stays fail-closed: " + failure.getMessage());
            this.subLandConfirm = null;
            this.subLandRunner = null;
            return null;
        }
    }

    /**
     * Recovery-scan gate for the live claim handler: null when recovery never
     * started (the handler then already replies {@code claim.unavailable} on
     * the missing runner), otherwise the bootstrap scan future. The handler
     * inspects it without blocking and stays fail-closed while the scan is
     * in flight or has failed.
     */
    private Supplier<java.util.concurrent.CompletionStage<?>> claimScanSupplier() {
        ClaimStartupBootstrap bootstrap = this.claimStartup;
        if (bootstrap == null) {
            return null;
        }
        return bootstrap::scanFuture;
    }

    /**
     * Production management gate resolver for {@code /land}.
     *
     * <p>Actor comes from the sender's player UUID, the snapshot from the
     * shared protection store, the steward flag from the serverland node, and
     * the context provider from the same rule/grant sources as the engine.
     * The target land resolves from the sender's current location against the
     * same immutable snapshot: wilderness, unknown worlds, non-player senders
     * and unresolvable positions stay unresolved, so management attempts fail
     * closed. There is no named-land argument yet — management subcommands
     * always act on the land under the sender. Admin bypass has no dedicated
     * state yet and resolves to {@code false}.
     */
    static ManagementGateResolver buildManagementGateResolver(LandRegistryStore store) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return new PluginManagementGateResolver(
                active::snapshot,
                () -> new SnapshotPermissionContextProvider(LandRuleService.defaults(), null),
                PluginManagementGateResolver.TargetLandResolver.currentLocation());
    }

    /**
     * Production resolver with explicit sources, so tests can inject a
     * snapshot, provider and target without starting a server.
     */
    static ManagementGateResolver buildManagementGateResolver(
            LandRegistryStore store,
            java.util.function.Supplier<PermissionContextProvider> providers,
            PluginManagementGateResolver.TargetLandResolver targets) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return new PluginManagementGateResolver(
                active::snapshot,
                providers == null ? () -> new SnapshotPermissionContextProvider(null, null) : providers,
                targets);
    }

    /**
     * Build the visualization scheduler through the public AceLib factory.
     *
     * <p>Narrow readiness on purpose: rendering only needs a ready API with a
     * platform and capability, independent of the GUI/Bedrock services the M0
     * capability probe requires. Only the supported public contract is used —
     * {@code isReady()}, {@code getPlatform()}, {@code getPlatformCapability()},
     * then {@code AceLibScheduler.create(...)}. Empty (never throws) when the
     * API is missing, not ready, or the factory fails; rendering then stays
     * dormant while selection data keeps working.
     */
    static Optional<SafeScheduler> tryBuildVisualizationScheduler(JavaPlugin plugin, AceLibApi api) {
        if (plugin == null || api == null) {
            return Optional.empty();
        }
        boolean ready;
        try {
            ready = api.isReady();
        } catch (RuntimeException readinessFailure) {
            return Optional.empty();
        }
        if (!ready) {
            return Optional.empty();
        }
        Platform platform;
        PlatformCapability capability;
        try {
            platform = api.getPlatform();
            capability = api.getPlatformCapability();
        } catch (RuntimeException serviceFailure) {
            return Optional.empty();
        }
        if (platform == null || capability == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(AceLibScheduler.create(plugin, platform, capability));
        } catch (RuntimeException factoryFailure) {
            return Optional.empty();
        }
    }

    /**
     * Builds the protection engine for startup wiring. Construction validates
     * that every action has a decision source, so an incomplete registry
     * throws here and the caller must refuse to start. Ownership comes from
     * the published land snapshot; environment rules resolve through the
     * built-in {@link LandRuleService#defaults()}, so the owner is bound by
     * the same rule as anyone else. Subject bindings/defaults still arrive
     * through the injected lookup: {@code null} there means no grant source
     * is wired yet and subject layers stay {@code INHERIT} (deny in lands).
     */
    static ProtectionEngine buildProtectionEngine(LandRegistryStore store) {
        return buildProtectionEngine(store, LandRuleService.defaults(), null);
    }

    /**
     * Builds the protection engine with explicit authorisation sources. Rule
     * lookups stay behind the {@link LandRuleLookup} interface so the rule
     * implementation can be supplied later without touching this wiring.
     */
    static ProtectionEngine buildProtectionEngine(LandRegistryStore store,
                                                  LandRuleLookup ruleLookup,
                                                  SubjectPermissionLookup subjectLookup) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return new ProtectionEngine(active::snapshot,
                new SnapshotPermissionContextProvider(ruleLookup, subjectLookup));
    }

    void registerWandListener(WandSafetyListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    void registerSelectionListener(SelectionLifecycleListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    void registerProtectionListener(ProtectionListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    void performFullCleanup() {
        // Immediate read invalidation comes before every cleanup step and
        // before the test start hook: cached holders gate on this volatile
        // flag only, so any read racing cleanup already observes empty.
        ReadApiLifecycle lifecycle = this.readApiLifecycle;
        if (lifecycle != null) {
            lifecycle.active = false;
        }
        Runnable startedHook = this.cleanupStartedHookForTest;
        if (startedHook != null) {
            try {
                startedHook.run();
            } catch (RuntimeException ignored) {
            }
        }
        if (selectionSessionManager != null) {
            try {
                selectionSessionManager.disable();
            } catch (RuntimeException ignored) {
            }
        }
        if (selectionSessionManager != null && configService != null && configService.isPresent()) {
            try {
                configService.get().removeListener(selectionSessionManager);
            } catch (RuntimeException ignored) {
            }
        }
        if (selectionLifecycleListener != null) {
            try {
                HandlerList.unregisterAll(selectionLifecycleListener);
            } catch (RuntimeException ignored) {
            }
            selectionLifecycleListener = null;
        }
        selectionSessionManager = null;
        selectionStructureRevisions = null;
        visualizationController = null;
        if (visualizationScheduler != null && visualizationScheduler.isPresent()) {
            try {
                visualizationScheduler.get().cancelAll();
            } catch (RuntimeException ignored) {
            }
        }
        visualizationScheduler = Optional.empty();
        if (wandSafetyListener != null) {
            try {
                HandlerList.unregisterAll(wandSafetyListener);
            } catch (RuntimeException ignored) {
            }
            wandSafetyListener = null;
        }
        if (protectionListener != null) {
            try {
                HandlerList.unregisterAll(protectionListener);
            } catch (RuntimeException ignored) {
            }
            protectionListener = null;
        }
        protectionEngine = null;
        protectionStore = null;
        protectionDepthLookup = null;
        // Depth extends stop before persistence closes: the service first
        // stops accepting new proposals, flushes every accepted write, and
        // only then closes the persistence handle, so no accepted extend is
        // lost on disable. Null when the Folia trigger wiring has not
        // started the service yet; closing the bootstrap store afterwards is
        // then a no-op-safe single close.
        if (depthExtendService != null) {
            try {
                depthExtendService.close();
            } catch (RuntimeException ignored) {
            }
            depthExtendService = null;
        }
        if (claimStartup != null) {
            try {
                claimStartup.close();
            } catch (RuntimeException ignored) {
            }
            claimStartup = null;
        }
        claimSaga = null;
        claimQuotas = null;
        claimReservations = null;
        subLandConfirm = null;
        subLandRunner = null;        if (claimExecutor != null) {
            try {
                claimExecutor.shutdownNow();
            } catch (RuntimeException ignored) {
            }
            claimExecutor = null;
        }
        this.messagePipeline = Optional.empty();
        this.capabilityProbe = Optional.empty();
        this.landMessagePipeline = Optional.empty();
        this.landCommand = null;
        if (capabilities.isPresent()) {
            try {
                capabilities.get().release();
            } catch (RuntimeException ignored) {
            }
        }
        this.capabilities = Optional.empty();
        this.configService = Optional.empty();
        bridge.release();
    }

    @Override
    public void onDisable() {
        performFullCleanup();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("chunkland")) {
            if (args != null && args.length >= 1 && args[0].equalsIgnoreCase("viz")) {
                return VisualizationDebugCommand.handle(
                        sender, args, this.selectionSessionManager,
                        () -> this.visualizationScheduler, this.visualizationController);
            }
            return dispatch(sender, args, () -> this.messagePipeline, () -> this.capabilityProbe);
        }
        if (command.getName().equalsIgnoreCase("land")) {
            LandCommand cmd = this.landCommand;
            if (cmd == null) {
                // Fail-closed fallback: no cached command, so rebuild the
                // production dispatch with a production resolver when the store
                // survived, otherwise with no resolver (management denies
                // either way). The claim slot stays fail-safe without a saga.
                LandRegistryStore store = this.protectionStore;
                ManagementGateResolver resolver =
                        store == null ? null : buildManagementGateResolver(store);
                SelectionSessionManager selections = this.selectionSessionManager;
                Map<String, LandCommand.Handler> handlers = selections == null
                        ? LandCommand.defaultStubHandlers()
                        : buildLandHandlers(selections, null);
                cmd = new LandCommand(handlers, null, resolver);
            }
            ChunkLandMessagePipeline pipeline = this.landMessagePipeline.orElse(null);
            return cmd.dispatch(sender, args, pipeline);
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("land")) {
            return LandCommand.tabComplete(sender, args);
        }
        return super.onTabComplete(sender, command, alias, args);
    }

    // Visible for tests: inject a custom land pipeline / command
    void setLandMessagePipelineForTest(ChunkLandMessagePipeline pipeline) {
        this.landMessagePipeline = Optional.ofNullable(pipeline);
    }

    void setLandCommandForTest(LandCommand command) {
        this.landCommand = command;
    }

    void setWandSafetyListenerForTest(WandSafetyListener listener) {
        this.wandSafetyListener = listener;
    }

    WandSafetyListener getWandSafetyListener() {
        return wandSafetyListener;
    }

    SelectionSessionManager getSelectionSessionManager() {
        return selectionSessionManager;
    }

    SelectionLifecycleListener getSelectionLifecycleListener() {
        return selectionLifecycleListener;
    }

    /**
     * @return the startup recovery bootstrap owned by this plugin, or null
     *         when recovery was never started or already cleaned up.
     */
    ClaimStartupBootstrap getClaimStartup() {
        return claimStartup;
    }

    /**
     * Production assembly for the depth-extend service: the trigger adapter
     * classifies over the immutable snapshot seams, the queue drains on the
     * owner-provided executor, and disable order is stop accepting, flush the
     * queue, then close persistence. Package visible so integration tests
     * drive the same assembly the server uses.
     */
    static DepthExtendService buildDepthExtendService(
            DepthStore depthStore,
            PersistenceStore persistence,
            Executor drainExecutor,
            int capacity,
            DepthExtendEventAdapter adapter) {
        return DepthExtendService.start(
                Objects.requireNonNull(depthStore, "depthStore"),
                Objects.requireNonNull(drainExecutor, "drainExecutor"),
                capacity,
                Objects.requireNonNull(adapter, "adapter"),
                Objects.requireNonNull(persistence, "persistence"));
    }

    /**
     * @return the depth-extend service owned by this plugin, or null when the
     *         Folia trigger wiring has not started it yet.
     */
    DepthExtendService getDepthExtendService() {
        return depthExtendService;
    }

    // Visible for tests: inject the production depth-extend service
    void setDepthExtendServiceForTest(DepthExtendService service) {
        this.depthExtendService = service;
    }

    LandRegistryStore getProtectionStore() {
        return protectionStore;
    }

    /**
     * @return the formal production depth lookup built from the live config
     *         snapshot at enable, or null before enable / after disable.
     */
    SnapshotProtectionDepthLookup getProtectionDepthLookup() {
        return protectionDepthLookup;
    }

    /**
     * Production assembly for the formal read path.
     *
     * <p>The returned API shares the given store's volatile snapshot with the
     * given depth lookup: every read takes exactly one snapshot and passes
     * that same immutable instance to the lookup, so existence checks and
     * depth answers can never mix publications. The lookup itself resolves
     * per-world modes and minima from in-memory maps only, so the hot path
     * performs no Bukkit, SQL, network or chunk access. A {@code null} store
     * falls back to an empty registry and a {@code null} lookup to an empty
     * answer, matching the fail-closed shape of the store-only constructor.
     * Permission context and rule sources stay at their fail-closed defaults;
     * this seam only wires depth.
     */
    static ChunkLandReadApi buildReadApi(LandRegistryStore store,
            ProtectionDepthLookup depthLookup) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return buildReadApi(active::snapshot, depthLookup);
    }

    /**
     * Production assembly over an explicit snapshot supplier.
     *
     * <p>Null-tolerant variant of the store seam above: a {@code null}
     * supplier reads an empty registry and a {@code null} lookup answers
     * empty. Existing constructors and their tests are untouched.
     */
    static ChunkLandReadApi buildReadApi(Supplier<com.smile.chunkland.runtime.index.LandRegistry> registrySupplier,
            ProtectionDepthLookup depthLookup) {
        Supplier<com.smile.chunkland.runtime.index.LandRegistry> activeSupplier =
                registrySupplier == null
                        ? com.smile.chunkland.runtime.index.LandRegistry::empty : registrySupplier;
        ProtectionDepthLookup activeLookup =
                depthLookup == null ? (landId, snapshot) -> Optional.empty() : depthLookup;
        return new ChunkLandReadApi(activeSupplier,
                (actor, landId, action, snapshot) -> null,
                (landId, rule, snapshot) -> Optional.empty(),
                activeLookup);
    }

    /**
     * @return the production read API bound to the shared protection store
     *         and the formal depth lookup built at enable. Each call returns
     *         a lightweight holder over the live volatile snapshot, so mode
     *         reloads apply without rebuilding; before enable or after
     *         disable the holder stays fail-closed on an empty view.
     *
     * <p>Public so external consumers in other packages can read through
     * the stable {@link ChunkLandApi} contract. The returned holder shares
     * the same immutable snapshot with the depth lookup on every read and
     * exposes only immutable snapshots and value objects. Each holder
     * captures its enable generation: cleanup deactivates that generation
     * so a cached holder keeps returning empty even after the fields are
     * cleared, and a later enable starts a new generation without reviving
     * the old handle. The gate is a volatile flag check only.
     */
    public ChunkLandApi getReadApi() {
        ReadApiLifecycle lifecycle = this.readApiLifecycle;
        if (lifecycle == null) {
            synchronized (this) {
                lifecycle = this.readApiLifecycle;
                if (lifecycle == null) {
                    lifecycle = new ReadApiLifecycle();
                    this.readApiLifecycle = lifecycle;
                }
            }
        }
        final ReadApiLifecycle captured = lifecycle;
        LandRegistryStore store = this.protectionStore;
        ProtectionDepthLookup lookup = this.protectionDepthLookup;
        Supplier<com.smile.chunkland.runtime.index.LandRegistry> base =
                store == null
                        ? com.smile.chunkland.runtime.index.LandRegistry::empty
                        : store::snapshot;
        Supplier<com.smile.chunkland.runtime.index.LandRegistry> gated =
                () -> captured.active
                        ? base.get()
                        : com.smile.chunkland.runtime.index.LandRegistry.empty();
        return buildReadApi(gated, lookup);
    }

    static Command commandForTest(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return false;
            }
        };
    }

    Optional<ChunkLandMessagePipeline> getLandMessagePipeline() {
        return landMessagePipeline;
    }

    /**
     * Pure dispatcher extracted from {@link #onCommand} so tests can exercise the permission
     * and routing logic without standing up a Bukkit {@link Command} or server.
     *
     * @param sender        the command sender
     * @param args          full command args (including the leading subcommand token)
     * @param messageProbe  lazy lookup for the M0 message probe (must already be built by
     *                      {@code onEnable}); tests pass {@code () -> probe} to inject a stub
     * @param capabilityProbe lazy lookup for the M0 capability probe
     * @return {@code true} when the entry-point is recognised (so Bukkit stops scanning
     *         aliases); {@code false} for unknown commands
     */
    static boolean dispatch(CommandSender sender,
                            String[] args,
                            Supplier<Optional<M0MessageProbe>> messageProbe,
                            Supplier<Optional<M0CapabilityProbe>> capabilityProbe) {
        if (args == null || args.length < 1) {
            return false;
        }
        if (args[0].equalsIgnoreCase("m0message")) {
            if (!sender.hasPermission(PERMISSION_M0_MESSAGE)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_MESSAGE + "，拒絕執行 m0message。");
                return true;
            }
            return M0MessageProbe.handleCommand(sender, args, messageProbe.get());
        }
        if (args[0].equalsIgnoreCase("m0test")) {
            if (!sender.hasPermission(PERMISSION_M0_TEST)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_TEST + "，拒絕執行 m0test。");
                return true;
            }
            return M0CapabilityProbe.handleCommand(sender, args, capabilityProbe.get());
        }
        return false;
    }

    /**
     * Server-independent dispatch seam used by tests. Mirrors {@link #dispatch} but takes a
     * {@link PermissionProbedProbeGate} so tests can observe whether the probe was actually
     * invoked (vs. short-circuited by a permission deny).
     */
    @FunctionalInterface
    public interface PermissionProbedProbeGate {
        /**
         * @return {@code true} if the gate observed that the probe was reached (the caller
         *         can use this to assert "denied routes must not call the probe").
         */
        boolean invoke(CommandSender sender, String[] args, String subcommand);
    }

    /**
     * Server-independent dispatch seam that drives the supplied gate in place of the real
     * probe call. Used by {@code M0CapabilityRedContractTest} to assert deny/allow paths.
     */
    public static boolean dispatchForTest(CommandSender sender,
                                          Command command,
                                          String label,
                                          String[] args,
                                          Supplier<Optional<M0MessageProbe>> messageProbe,
                                          PermissionProbedProbeGate gate) {
        if (!command.getName().equalsIgnoreCase("chunkland")) {
            return false;
        }
        if (args == null || args.length < 1) {
            return false;
        }
        if (args[0].equalsIgnoreCase("m0message")) {
            if (!sender.hasPermission(PERMISSION_M0_MESSAGE)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_MESSAGE + "，拒絕執行 m0message。");
                return true;
            }
            gate.invoke(sender, args, "m0message");
            return true;
        }
        if (args[0].equalsIgnoreCase("m0test")) {
            if (!sender.hasPermission(PERMISSION_M0_TEST)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_TEST + "，拒絕執行 m0test。");
                return true;
            }
            gate.invoke(sender, args, "m0test");
            return true;
        }
        return false;
    }
}
