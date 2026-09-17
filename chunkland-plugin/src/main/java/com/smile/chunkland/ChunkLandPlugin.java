package com.smile.chunkland;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormService;
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
import com.smile.chunkland.claim.DeleteSaga;
import com.smile.chunkland.claim.DeleteValidator;
import com.smile.chunkland.claim.ExpandSaga;
import com.smile.chunkland.claim.ExpandValidator;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.claim.ShrinkSaga;
import com.smile.chunkland.claim.ShrinkValidator;
import com.smile.chunkland.claim.SnapshotClaimValidator;
import com.smile.chunkland.claim.SnapshotDeleteValidator;
import com.smile.chunkland.claim.SnapshotExpandValidator;
import com.smile.chunkland.claim.SnapshotShrinkValidator;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.limit.LimitType;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.binding.LandBindingService;
import com.smile.chunkland.command.AuditLogCommandHandler;
import com.smile.chunkland.command.BedrockClaimFormHandler;import com.smile.chunkland.command.BedrockManageFormHandler;import com.smile.chunkland.command.BindingCommandHandler;
import com.smile.chunkland.command.ClaimCommandHandler;
import com.smile.chunkland.command.ClaimFormTexts;
import com.smile.chunkland.command.ConfirmCommandHandler;
import com.smile.chunkland.command.DirectTrustCommandHandler;
import com.smile.chunkland.command.EntryBanCommandHandler;
import com.smile.chunkland.command.ExpandCommandHandler;
import com.smile.chunkland.command.ExplainCommandHandler;
import com.smile.chunkland.command.GroupCommandHandler;
import com.smile.chunkland.command.AdminCommandRouter;
import com.smile.chunkland.command.InspectCommandHandler;
import com.smile.chunkland.command.LandDeleteCommandHandler;
import com.smile.chunkland.command.AdminBypassCommandHandler;
import com.smile.chunkland.command.AdminBypassRecovery;
import com.smile.chunkland.command.AdminBypassState;
import com.smile.chunkland.command.LedgerAdminCommandHandler;
import com.smile.chunkland.command.OrphanAdminCommandHandler;
import com.smile.chunkland.command.ProfileCommandHandler;
import com.smile.chunkland.command.ShrinkCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandDefaultCommandHandler;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.ManageGuiCommandHandler;
import com.smile.chunkland.command.OfflinePlayerResolver;
import com.smile.chunkland.command.PluginManagementGateResolver;
import com.smile.chunkland.command.RenameCommandHandler;
import com.smile.chunkland.command.SubLandCommandHandler;
import com.smile.chunkland.command.VisualizationDebugCommand;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.ConfigReloadListener;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.config.WorldSettings;
import com.smile.chunkland.config.YamlFileConfigLoader;
import com.smile.chunkland.economy.UnavailableVaultBridge;
import com.smile.chunkland.economy.VaultBridge;
import com.smile.chunkland.economy.VaultServiceDiscovery;
import com.smile.chunkland.gui.GuiNavigator;
import com.smile.chunkland.gui.BedrockFormNavigator;
import com.smile.chunkland.gui.GuiClickContext;
import com.smile.chunkland.gui.ManagementGuiActions;
import com.smile.chunkland.gui.ManagementGuiModel;
import com.smile.chunkland.gui.ManagementGuiPages;
import com.smile.chunkland.api.limit.ExternalLimitProvider;
import com.smile.chunkland.api.limit.LimitResult;
import com.smile.chunkland.api.history.WorldHistoryProvider;
import com.smile.chunkland.history.CoreProtectDiscovery;
import com.smile.chunkland.history.HistoryCommandHandler;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.LuckPermsDiscovery;
import com.smile.chunkland.limit.OwnerQuotaHydrator;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.message.M0MessageProbe;
import com.smile.chunkland.message.PlayerPreferredLocaleService;
import com.smile.chunkland.message.PlayerSettingsLocaleListener;
import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.enterleave.EnterLeaveListener;
import com.smile.chunkland.enterleave.EnterLeaveNotifier;
import com.smile.chunkland.enterleave.EnterLeavePreferenceService;
import com.smile.chunkland.enterleave.EnterLeaveTracker;
import com.smile.chunkland.event.BukkitEventCaller;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.LandRenameRepository;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OrphanPurgeRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.runtime.storage.OrphanWorldGuard;
import com.smile.chunkland.persistence.PlayerSettingsRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SubLandAtomicCommit;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import com.smile.chunkland.protection.EntryBanLookup;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainService;
import com.smile.chunkland.group.SubjectGroupService;
import com.smile.chunkland.profile.PermissionProfileService;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.ProtectionListener;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.protection.PermissionDecisionCache;
import com.smile.chunkland.protection.PermissionDecisionEpochSource;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.protection.SubjectPermissionLookup;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.api.ChunkLandReadApi;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.api.ProtectionDepthLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.RegistryReadiness;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.index.SubLandIndex;
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
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionEditService;
import com.smile.chunkland.selection.SelectionLandIndex;
import com.smile.chunkland.selection.SelectionLandBoundaryLookup;
import com.smile.chunkland.selection.SelectionLandContext;
import com.smile.chunkland.selection.SelectionLandLookup;
import com.smile.chunkland.selection.SelectionLifecycleListener;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.OccupiedPreviewController;
import com.smile.chunkland.selection.OccupiedPreviewRenderer;
import com.smile.chunkland.selection.SelectionVisualizationRenderer;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.DepthExtensionPort;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandDepthSource;
import com.smile.chunkland.subland.SubLandMutationRunner;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.trust.LandAuthorisationService;
import com.smile.chunkland.wand.WandGiveHandler;
import com.smile.chunkland.wand.WandFeedback;
import com.smile.chunkland.wand.WandSafetyListener;
import com.smile.chunkland.wand.WandSelectionNotifier;
import com.smile.chunkland.wand.SelectionWandClickHandler;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
    private GuiNavigator guiNavigator;
    private BedrockFormNavigator bedrockFormNavigator;
    private Optional<ConfigService> configService = Optional.empty();
    private Optional<ChunkLandMessagePipeline> landMessagePipeline = Optional.empty();
    private PlayerPreferredLocaleService playerLocaleService;
    private PlayerSettingsRepository playerSettingsRepository;
    private PlayerSettingsLocaleListener playerSettingsLocaleListener;
    private EnterLeavePreferenceService enterLeavePreferences;
    private EnterLeaveTracker enterLeaveTracker;
    private EnterLeaveListener enterLeaveListener;
    private LandCommand landCommand;
    private WandSafetyListener wandSafetyListener;
    /**
     * Per-enable player-scoped admin-bypass memory. A fresh instance is
     * installed on every enable (default off) and cleared on disable, so no
     * bypass survives a restart or a reload. Every management gate seam —
     * the command resolver, the GUI open, explain/inspect and the Bedrock
     * branch — reads this same instance; holding the bypass permission node
     * alone never flips it.
     */
    private volatile AdminBypassState adminBypassStates;
    /**
     * Single shared bypass generation per enable: the production handler,
     * its process id, recovery gate and close token. Disable closes the
     * token before clearing the state so late callbacks stop before any
     * further audit or state mutation; the fallback slot reuses the stored
     * handler instead of building an ungated one.
     */
    private volatile AdminBypassCommandHandler bypassHandler;
    private volatile java.util.UUID bypassProcess;
    private volatile java.util.concurrent.CompletableFuture<Void> bypassRecoveryGate;
    private volatile com.smile.chunkland.command.AdminBypassLifecycle bypassLifecycle;
    private SelectionSessionManager selectionSessionManager;
    private SelectionLifecycleListener selectionLifecycleListener;
    private SelectionVisualizationTaskController visualizationController;
    private OccupiedPreviewController occupiedPreviewController;
    private Optional<SafeScheduler> visualizationScheduler = Optional.empty();
    private SelectionStructureRevisionLookup selectionStructureRevisions;
    private LandRegistryStore protectionStore;
    private ProtectionEngine protectionEngine;
    private PermissionDefaultsCache permissionDefaults;
    private PermissionDecisionCache decisionCache;
    private ConfigReloadListener decisionCacheBudgetSync;
    private SnapshotProtectionDepthLookup protectionDepthLookup;
    private ProtectionListener protectionListener;
    private ClaimStartupBootstrap claimStartup;
    private OrphanWorldGuard orphanWorldGuard;
    private OrphanWorldCatalogListener orphanCatalogListener;
    private ClaimSaga claimSaga;
    private ExpandSaga expandSaga;
    private ShrinkSaga shrinkSaga;
    private DeleteSaga deleteSaga;
    private OwnerQuotaService claimQuotas;
    /**
     * Optional external limit provider (LuckPerms when present). Discovered
     * once per enable generation and shared by the claim/expand quotas and
     * inspect; absent stays empty and every limit falls back to config.
     */
    private volatile Optional<ExternalLimitProvider> limitProvider = Optional.empty();
    /**
     * Optional world block-history provider (CoreProtect when present).
     * Discovered once per enable generation and shared by
     * {@code /land history} only; absent stays unavailable and the slot
     * replies the generic unavailable message. Never consulted by the
     * protection hot path.
     */
    private volatile WorldHistoryProvider historyProvider = WorldHistoryProvider.empty();
    private LogicalReservationRegistry claimReservations;
    private ExecutorService claimExecutor;
    private ExecutorService resolveExecutor;
    private SubLandConfirmService subLandConfirm;
    private SubLandMutationRunner subLandRunner;
    private LandAuthorisationCache landAuthorisationCache;
    private LandAuthorisationService landAuthorisationService;
    /**
     * Shared public-event dispatch owned by this enable generation: one
     * {@link PublicEventBus} plus the Bukkit bridge, passed to every
     * mutation seam that emits public Pre/Post events (claim, expand,
     * shrink, delete, SubLand, trust/default/ban, bindings, enter/leave).
     * External plugins listen through the Bukkit event views or the bus
     * below; the bus never performs I/O and never blocks.
     */
    private PublicEventBus publicEventBus;
    private PublicEvents publicEvents;
    private LandRenameService landRenameService;
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

    /**
     * @return the GUI navigator built from the live {@code GuiService}, or
     *     empty when capabilities are unavailable. The navigator tracks only
     *     sessions opened through it; disable closes exactly those sessions.
     */
    public Optional<GuiNavigator> guiNavigator() {
        return Optional.ofNullable(guiNavigator);
    }

    /**
     * Management GUI actions shown on the second layer, in display order.
     * The five management actions come first, then the most-checked
     * subject-permission actions so the detail page stays on one screen.
     */
    static final List<ProtectionActionType> MANAGEMENT_GUI_ACTIONS = List.of(
            ProtectionActionType.MANAGE_MEMBER,
            ProtectionActionType.MANAGE_PERMISSION,
            ProtectionActionType.MANAGE_SUBLAND,
            ProtectionActionType.EXPAND_LAND,
            ProtectionActionType.DELETE_LAND,
            ProtectionActionType.BLOCK_BREAK,
            ProtectionActionType.BLOCK_PLACE,
            ProtectionActionType.CONTAINER_OPEN,
            ProtectionActionType.ENTRY);

    /**
     * Open the first-layer land-management GUI for {@code playerUuid} on
     * {@code landId}.
     *
     * <p>The caller is re-checked against the shared
     * {@link ManagementPermissionGate} for {@code MANAGE_PERMISSION} before
     * anything opens; without {@code ALLOW} (unknown land, missing snapshot
     * or provider, denied gate, resolver failure) the call returns empty and
     * reveals nothing. The detail rows are read-only views over the same
     * immutable snapshot the enforcement path reads. Row clicks only reach
     * the caller-owned seam: this milestone keeps them observational, so
     * every mutation stays on the existing {@code /land} handler/service
     * path behind the same gate — the GUI never mutates domain state and
     * never offers an {@code EVERYONE} entry.
     *
     * @return the authoritative upstream generation, or empty when the GUI
     *     must stay closed
     */
    public Optional<Long> openManagementGui(UUID playerUuid, LandId landId) {
        GuiNavigator navigator = this.guiNavigator;
        if (playerUuid == null || landId == null || navigator == null
                || protectionStore == null || permissionDefaults == null) {
            return Optional.empty();
        }
        LandRegistry snapshot;
        try {
            snapshot = protectionStore.snapshot();
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
        if (snapshot == null) {
            return Optional.empty();
        }
        try {
            if (snapshot.land(landId) == null) {
                return Optional.empty();
            }
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
        PermissionContextProvider contexts = permissionDefaults.provider();
        if (contexts == null) {
            return Optional.empty();
        }
        PermissionDecision gate;
        try {
            gate = ManagementPermissionGate.check(playerUuid, landId,
                    ProtectionActionType.MANAGE_PERMISSION, snapshot, readAdminBypass(playerUuid),
                    false, contexts);
        } catch (RuntimeException denied) {
            return Optional.empty();
        }
        if (gate == null || gate.outcome() != PermissionState.ALLOW) {
            return Optional.empty();
        }
        ManagementGuiModel model =
                buildManagementGuiModel(playerUuid, landId, snapshot, contexts);
        ManagementGuiModel shared = model.available() ? model : ManagementGuiModel.unavailable();
        ManagementGuiActions actions = new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
                try {
                    navigator.push(click.playerUuid(),
                            ManagementGuiPages.detailsPage(shared, this));
                } catch (RuntimeException ignored) {
                    // Navigation stays fail-closed; the current page is kept.
                }
            }

            @Override
            public void back(GuiClickContext click) {
                try {
                    navigator.back(click.playerUuid());
                } catch (RuntimeException ignored) {
                    // Navigation stays fail-closed; the current page is kept.
                }
            }

            @Override
            public void requestChange(GuiClickContext click, ProtectionActionType action) {
                // Read-only in this milestone: row clicks are observed only.
                // Mutations stay on the existing /land handler/service path
                // behind the same management gate; the GUI never mutates
                // domain state directly.
                Objects.requireNonNull(click, "click");
                Objects.requireNonNull(action, "action");
            }
        };
        try {
            return navigator.open(playerUuid, ManagementGuiPages.rootPage(actions));
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    /**
     * Read-only detail model over one snapshot: one context per listed
     * action, resolved and explained through the shared path. Actions whose
     * context, decision or explanation is missing or fails are skipped
     * fail-closed; when nothing survives the model is unavailable.
     */
    static ManagementGuiModel buildManagementGuiModel(UUID actor, LandId landId,
            LandRegistry snapshot, PermissionContextProvider contexts) {
        if (actor == null || landId == null || snapshot == null || contexts == null) {
            return ManagementGuiModel.unavailable();
        }
        boolean serverLand;
        try {
            serverLand = ManagementPermissionGate.isServerLand(
                    Objects.requireNonNull(snapshot.land(landId), "land"));
        } catch (RuntimeException unresolved) {
            return ManagementGuiModel.unavailable();
        }
        List<PermissionExplain> explains = new ArrayList<>();
        Map<ProtectionActionType, PermissionContext> byAction = new HashMap<>();
        for (ProtectionActionType action : MANAGEMENT_GUI_ACTIONS) {
            try {
                PermissionContext ctx = contexts.provide(actor, landId, action, snapshot);
                if (ctx == null) {
                    continue;
                }
                PermissionDecision decision = PermissionResolver.resolve(ctx);
                if (decision == null || decision.outcome() == PermissionState.INHERIT) {
                    continue;
                }
                PermissionExplain explained = PermissionExplainService.explain(
                        ctx, decision, null, false, serverLand);
                if (explained == null) {
                    continue;
                }
                explains.add(explained);
                byAction.put(action, ctx);
            } catch (RuntimeException skipped) {
                // Per-action fail-closed: skip without poisoning the rest.
            }
        }
        return ManagementGuiModel.fromExplains(explains, byAction);
    }

    /**
     * disable 時的 Bedrock 導航清理本體：null 安全、可重複呼叫。
     * performFullCleanup 先清欄位引用再呼叫這裡，所以重複 disable 不殘留。
     */
    static void closeBedrockForms(BedrockFormNavigator navigator) {
        if (navigator == null) {
            return;
        }
        try {
            navigator.closeAll();
        } catch (RuntimeException ignored) {
            // 清理保持 best-effort；追蹤已經在呼叫端丟掉引用。
        }
    }

    /**
     * Bedrock 管理表單的唯讀模型來源：與 Java 管理 GUI 讀同一份不可變快照、
     * 同一個 provider，記憶體讀取 only。快照讀取失敗一律回傳 unavailable，
     * 表單呈現 fail-closed 通知頁，絕不猜測資料。
     */
    static ManagementGuiModel buildBedrockManageModel(LandRegistryStore store,
            java.util.function.Supplier<PermissionContextProvider> providers,
            UUID actor, LandId landId) {
        if (store == null || providers == null || actor == null || landId == null) {
            return ManagementGuiModel.unavailable();
        }
        final LandRegistry snapshot;
        try {
            snapshot = store.snapshot();
        } catch (RuntimeException unresolved) {
            return ManagementGuiModel.unavailable();
        }
        if (snapshot == null) {
            return ManagementGuiModel.unavailable();
        }
        final PermissionContextProvider contexts;
        try {
            contexts = providers.get();
        } catch (RuntimeException unresolved) {
            return ManagementGuiModel.unavailable();
        }
        if (contexts == null) {
            return ManagementGuiModel.unavailable();
        }
        try {
            return buildManagementGuiModel(actor, landId, snapshot, contexts);
        } catch (RuntimeException unresolved) {
            return ManagementGuiModel.unavailable();
        }
    }

    /**
     * 線上玩家名稱：只在玩家 region 任務內呼叫，只讀名稱不碰其他狀態；
     * 讀取失敗回傳空名單，選擇頁 fail-closed。
     */
    List<String> listOnlinePlayerNames() {
        try {
            var online = getServer().getOnlinePlayers();
            if (online == null) {
                return List.of();
            }
            return online.stream()
                    .map(candidate -> {
                        try {
                            return candidate == null ? null : candidate.getName();
                        } catch (RuntimeException unresolved) {
                            return null;
                        }
                    })
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        } catch (RuntimeException unresolved) {
            return List.of();
        }
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
        // The registry store is created before the selection manager so the
        // manager's structure-revision source reads the same live snapshot the
        // /land claim, expand and shrink flows publish and validate against.
        this.protectionStore = new LandRegistryStore();
        // Fresh bypass memory per enable generation: every actor defaults to
        // off, so no toggle survives a restart or a reload.
        this.adminBypassStates = new AdminBypassState();
        SelectionStructureRevisionLookup structureRevisions =
                buildSubLandStructureLookup(this.protectionStore);
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
        // Occupied-land preview: a second, independent player-scoped loop with a
        // distinct red-orange sink, so it never replaces the active selection
        // renderer and never writes selection/claim state.
        OccupiedPreviewController occupiedPreview = visualizationScheduler
                .map(scheduler -> (OccupiedPreviewController) new OccupiedPreviewRenderer(
                        new FoliaVisualizationTickScheduler(
                                uuid -> getServer().getPlayer(uuid), () -> visualizationScheduler),
                        new FoliaSelectionParticleSink(
                                uuid -> getServer().getPlayer(uuid),
                                FoliaSelectionParticleSink.OCCUPIED_DUST_COLOR),
                        () -> activeConfig.current().selection().visualizationBudget()))
                .orElseGet(OccupiedPreviewController::noop);
        this.occupiedPreviewController = occupiedPreview;
        getLogger().info("ChunkLand selection visualization: "
                + (visualizationScheduler.isPresent() ? "scheduler ready" : "dormant (AceLib scheduler unavailable)")
                + ", budget=" + activeConfig.current().selection().visualizationBudget());
        this.selectionSessionManager = new SelectionSessionManager(
                new FoliaSelectionTimeoutScheduler(this, uuid -> getServer().getPlayer(uuid)),
                visualization,
                new WandSelectionNotifier(uuid -> getServer().getPlayer(uuid),
                        (player, key) -> sendWandSelectionMessage(player, key, java.util.Map.of())),
                Clock.systemUTC()::instant,
                () -> activeConfig.current().selection().sessionTimeout(),
                uuid -> Optional.ofNullable(getServer().getWorld(uuid)).map(org.bukkit.World::getName),
                structureRevisions);
        this.selectionLifecycleListener = new SelectionLifecycleListener(selectionSessionManager, occupiedPreview);
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
        this.readApiLifecycle = new ReadApiLifecycle();
        // Config permission defaults: world names resolve to UUIDs once here
        // (and on every config reload) against the startup-captured table, so
        // the enforcement hot path only reads immutable UUID-keyed maps. Land
        // bindings/defaults still have no durable source and stay empty/
        // INHERIT — strangers fall through to the configured world/global
        // defaults, then to implicit DENY.
        this.permissionDefaults = buildPermissionDefaults(
                activeConfig, snapshotWorldIdsByName(), getLogger()::warning);
        this.configService.ifPresent(service -> service.addListener(this.permissionDefaults));
        // Atomic config view: one decision captures one immutable
        // subject/rule pair, so a reload can never mix generations inside a
        // single provide() call. The shared instance stays live: new
        // decisions observe later reloads without rebuilding consumers.
        SnapshotPermissionContextProvider atomicContexts = this.permissionDefaults.provider();
        // Decision cache: bounded, memory-only reuse keyed on the four
        // epochs plus structure and owner context. The budget follows the
        // live config; reloads apply it without rebuilding the engine.
        this.decisionCache =
                new PermissionDecisionCache(activeConfig.current().decisionCacheMaxEntries());
        PermissionDecisionCache budgeted = this.decisionCache;
        this.decisionCacheBudgetSync = diff -> {
            try {
                budgeted.setMaxEntries(activeConfig.current().decisionCacheMaxEntries());
            } catch (RuntimeException ignored) {
                // A budget sync must never break a committed reload.
            }
        };
        this.configService.ifPresent(service -> service.addListener(this.decisionCacheBudgetSync));
        this.protectionEngine = buildProtectionEngine(
                this.protectionStore, atomicContexts, this.decisionCache, atomicContexts);
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
                    resolveEconomyBridge(),
                    economyCurrency(activeConfig.current()));
        } catch (RuntimeException | Error failure) {
            getLogger().warning("ChunkLand claim recovery bootstrap failed; "
                    + "runtime stays empty until the next restart: " + failure.getMessage());
            this.claimStartup = null;
        }
        // Startup hydration gate shared by the wand guard, the selection edit
        // collision index and the expand delta adapter: until the durable
        // registry rebuild publishes and marks readiness, occupancy reads fail
        // closed instead of treating unhydrated durable land as wilderness.
        RegistryReadiness registryReadiness =
                this.claimStartup == null ? null : this.claimStartup.readiness();
        // Public events: one shared synchronous bus plus the Bukkit bridge
        // for this enable generation. Every mutation seam below receives the
        // same facade, so external listeners observe each Pre/Post exactly
        // once. The Bukkit call runs synchronously on the publishing thread;
        // Pre calls happen before any Economy or durable side effect, Post
        // calls after the durable commit plus the runtime publish.
        PublicEventBus sharedBus = new PublicEventBus();
        this.publicEventBus = sharedBus;
        this.publicEvents = PublicEvents.create(sharedBus,
                bridgeCaller(getServer().getPluginManager()));
        // Durable land authorisation: direct trust bindings and land defaults
        // share the recovery bootstrap store, publish into one volatile
        // snapshot the enforcement path already reads, and load the existing
        // rows once at startup so restarts keep resolving them. When recovery
        // never started there is no service and the handlers stay fail-closed
        // without side effects.
        PersistenceStore authorisationStore = null;
        if (this.claimStartup != null) {
            try {
                authorisationStore = this.claimStartup.store();
            } catch (RuntimeException unresolved) {
                authorisationStore = null;
            }
        }
        LandAuthorisationService authorisations =
                authorisationStore == null ? null
                        : buildLandAuthorisations(authorisationStore, this.publicEvents);
        this.landAuthorisationCache =
                authorisations == null ? new LandAuthorisationCache() : authorisations.cache();
        this.landAuthorisationService = authorisations;
        this.permissionDefaults.attachLandAuthorisation(this.landAuthorisationCache::snapshot);
        if (authorisations != null) {
            try {
                authorisations.refresh().whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        getLogger().warning("ChunkLand land authorisation preload failed; "
                                + "trust and defaults stay empty until the next restart: " + failure);
                    }
                });
            } catch (RuntimeException failure) {
                getLogger().warning("ChunkLand land authorisation preload failed; "
                        + "trust and defaults stay empty until the next restart: " + failure);
            }
        }
        // Player preferred-locale: the repository shares the recovery
        // bootstrap store (no new connection, no schema change) and the
        // service keeps a bounded in-memory snapshot. Join loads run on the
        // persistence executor; rendering only reads the snapshot, so the
        // region/render path never blocks on SQL. Without a store every
        // lookup stays empty and messaging keeps the legacy chain.
        PlayerPreferredLocaleService localeService = new PlayerPreferredLocaleService();
        this.playerLocaleService = localeService;
        PlayerSettingsRepository settingsRepository =
                authorisationStore == null ? null : new PlayerSettingsRepository(authorisationStore);
        this.playerSettingsRepository = settingsRepository;
        this.landMessagePipeline.ifPresent(pipeline ->
                pipeline.setPreferredLocaleLookup(
                        uuid -> localeService.preferred(uuid).orElse(null)));
        if (settingsRepository != null) {
            this.playerSettingsLocaleListener =
                    new PlayerSettingsLocaleListener(localeService, settingsRepository);
        } else {
            this.playerSettingsLocaleListener = null;
        }
        // Enter-leave prompts: the tracker and the prompt switch share the
        // recovery bootstrap store through the same repository (no new
        // connection, no schema change). Movement only reads the bounded
        // memory snapshot; join loads run on the persistence executor.
        EnterLeavePreferenceService enterLeaveService = new EnterLeavePreferenceService();
        this.enterLeavePreferences = enterLeaveService;
        EnterLeaveTracker enterLeaveMemory = new EnterLeaveTracker();
        this.enterLeaveTracker = enterLeaveMemory;
        ChunkLandMessagePipeline enterLeavePipeline = this.landMessagePipeline.orElse(null);
        EnterLeaveNotifier enterLeaveNotifier = new EnterLeaveNotifier(
                enterLeavePipeline, enterLeaveService, buildPlayerScheduler(this));
        this.enterLeaveListener = new EnterLeaveListener(
                this.protectionStore::snapshot, enterLeaveMemory, enterLeaveService,
                settingsRepository, enterLeaveNotifier, this.publicEvents);
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
        // Capture the underlying capabilities bundle before the land command
        // is built so the Bedrock claim form resolves its services on demand.
        // Capturing here (still ahead of the listener registration below)
        // keeps the no-leak guarantee on registration failure.
        this.capabilities = capabilityProbe.map(M0CapabilityProbe::capabilities);
        // Java GUI framework: navigate per-player pages over the shared
        // GuiService. The navigator is empty when capabilities (or the GUI
        // service itself) are unavailable, so all GUI entry points fail
        // closed without touching the provider.
        this.guiNavigator = capabilities
                .map(Capabilities::guiService)
                .map(service -> service == null ? null : new GuiNavigator(service))
                .orElse(null);
        DirectTrustCommandHandler.LandResolver trustLands =
                currentLocationLandResolver(this.protectionStore);
        Function<String, Optional<UUID>> trustPlayers =
                name -> resolveOnlinePlayerUuid(getServer(), name);
        DirectTrustCommandHandler trustHandler = new DirectTrustCommandHandler(
                DirectTrustCommandHandler.Mode.TRUST, trustLands, trustPlayers,
                authorisations == null ? null : authorisations::trust);
        DirectTrustCommandHandler untrustHandler = new DirectTrustCommandHandler(
                DirectTrustCommandHandler.Mode.UNTRUST, trustLands, trustPlayers,
                authorisations == null ? null : authorisations::untrust);
        LandDefaultCommandHandler defaultHandler = new LandDefaultCommandHandler(
                trustLands::resolve,
                authorisations == null ? null : authorisations::setDefault);
        EntryBanCommandHandler.LandView banViews = landId -> {
            try {
                LandRegistry snapshot = this.protectionStore.snapshot();
                if (snapshot == null || landId == null) {
                    return Optional.empty();
                }
                return Optional.ofNullable(snapshot.land(landId));
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
        };
        EntryBanCommandHandler banHandler = new EntryBanCommandHandler(
                EntryBanCommandHandler.Mode.BAN, trustLands::resolve, trustPlayers, banViews,
                authorisations == null ? null : authorisations::ban);
        EntryBanCommandHandler unbanHandler = new EntryBanCommandHandler(
                EntryBanCommandHandler.Mode.UNBAN, trustLands::resolve, trustPlayers, banViews,
                authorisations == null ? null : authorisations::unban);
        // Formal rename flow: shares the recovery bootstrap store and the
        // shared runtime rebuilder so durable renames and the live map
        // converge on one revision source. Assembly failure keeps the slot
        // fail-closed instead of running half-wired.
        this.landRenameService = buildLandRename(authorisationStore, this.protectionStore,
                this.claimStartup == null ? null : this.claimStartup.rebuilder());
        LandRenameService renames = this.landRenameService;
        Function<CommandSender, Boolean> renameStewards = sender -> {
            try {
                return sender != null
                        && sender.hasPermission(
                                PluginManagementGateResolver.SERVER_LAND_STEWARD_NODE);
            } catch (RuntimeException denied) {
                return false;
            }
        };
        RenameCommandHandler renameHandler = renames == null ? null
                : new RenameCommandHandler(trustLands::resolve, renames::rename, renameStewards);
        // Generic bindings: one owner-scoped flow on the shared store and
        // the shared authorisation cache, wired behind /land binding. The
        // service publishes into the same snapshot the trust path publishes
        // into, so binding mutations are visible to the next decision.
        // Without a store the slot stays fail-closed with
        // binding.unavailable instead of running half-wired.
        LandBindingService bindingService = authorisationStore == null ? null
                : new LandBindingService(new LandBindingRepository(authorisationStore),
                        this.landAuthorisationCache, Clock.systemUTC(), this.publicEvents);
        java.util.function.Supplier<java.util.concurrent.CompletionStage<Void>> bindingRefresh =
                bindingService == null
                        ? () -> java.util.concurrent.CompletableFuture.completedFuture(null)
                        : bindingService::refresh;
        // Global Groups: one owner-scoped namespace on the shared store, wired
        // behind /land group. Without a store the slot stays fail-closed with
        // group.unavailable instead of running half-wired. Mutations rebuild
        // the generic binding snapshot through the binding refresh hook so
        // membership changes reach live decisions without a restart.
        SubjectGroupService groupService = authorisationStore == null ? null
                : new SubjectGroupService(
                        new SubjectGroupRepository(authorisationStore), bindingRefresh);
        GroupCommandHandler groupHandler = groupService == null ? null
                : new GroupCommandHandler(
                        GroupCommandHandler.serviceGroups(groupService),
                        name -> resolveOnlinePlayerUuid(getServer(), name));
        // Permission Profiles: one owner-scoped namespace on the shared
        // store, wired behind /land profile. Without a store the slot stays
        // fail-closed with profile.unavailable instead of running half-wired.
        // The handler holds no closeable, so disable needs no extra cleanup.
        // Entry mutations share the same binding refresh hook as groups.
        PermissionProfileService profileService = authorisationStore == null ? null
                : new PermissionProfileService(
                        new PermissionProfileRepository(authorisationStore), bindingRefresh);
        ProfileCommandHandler profileHandler = profileService == null ? null
                : new ProfileCommandHandler(
                        ProfileCommandHandler.serviceProfiles(profileService));
        // Offline name resolution runs on a dedicated daemon thread, never on
        // a region thread: the binding handler and the inspect handler only
        // schedule through it and reply on completion. Created ahead of the
        // command wiring so both handlers share one executor; a failed build
        // leaves it null and every offline name fails closed.
        try {
            this.resolveExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "chunkland-resolve");
                thread.setDaemon(true);
                return thread;
            });
        } catch (RuntimeException failure) {
            getLogger().warning("ChunkLand offline resolution executor failed; "
                    + "offline names stay fail-closed: " + failure.getMessage());
            this.resolveExecutor = null;
        }
        BindingCommandHandler bindingHandler = bindingService == null
                || groupService == null || profileService == null ? null
                : new BindingCommandHandler(
                        BindingCommandHandler.serviceBindings(bindingService),
                        BindingCommandHandler.serviceGroups(groupService),
                        BindingCommandHandler.serviceProfiles(profileService),
                        trustLands::resolve,
                        currentLocationSublandResolver(this.protectionStore),
                        name -> resolveOnlinePlayerUuid(getServer(), name),
                        buildOfflinePlayerResolver(getServer(), this.resolveExecutor),
                        buildPlayerScheduler(this));
        // Audit history reads: the shared recovery store holds audit_log, so
        // /land log searches the same durable rows the sagas wrote, newest
        // first with paging. Without a store the slot stays fail-closed with
        // log.unavailable instead of running half-wired.
        AuditRepository auditReads = authorisationStore == null ? null
                : new SqliteAuditRepository(authorisationStore);
        AuditLogCommandHandler logHandler = auditReads == null ? null
                : new AuditLogCommandHandler(() -> auditReads, Clock.systemUTC()::instant);
        // Ledger administration: the operator verdict flow reads the claim
        // bootstrap ledger and writes LEDGER_RESOLVE into the same audit
        // history /land log searches. Without either side the admin slot
        // stays fail-closed instead of running half-wired.
        LedgerAdminCommandHandler ledgerAdminHandler =
                buildLedgerAdminHandler(this.claimStartup, auditReads, buildPlayerScheduler(this));
        // Orphan-world administration: irreversible per-world purges behind
        // the independent orphan node, sharing the claim bootstrap store and
        // rebuilder so the durable delete and the runtime refresh converge.
        // The guard carries the versioned world catalog the purge generation
        // check binds against. The initial publish reads the live catalog
        // once; when that read fails the guard stays unverified and every
        // orphan verb fails closed until a world load republishes it.
        // Without the store, the guard, the rebuilder or the scheduler the
        // orphan branch stays fail-closed instead of running half-wired.
        OrphanWorldGuard orphanGuard = new OrphanWorldGuard();
        Set<UUID> initialWorlds = snapshotLoadedWorldIds();
        if (initialWorlds == null) {
            getLogger().warning("ChunkLand world catalog snapshot failed; "
                    + "orphan administration stays unavailable until a world loads.");
        } else {
            orphanGuard.publish(initialWorlds);
        }
        this.orphanWorldGuard = orphanGuard;
        OrphanAdminCommandHandler orphanAdminHandler =
                buildOrphanAdminHandler(this.claimStartup, orphanGuard,
                        buildPlayerScheduler(this));
        // Generic binding preload: read the existing binding rows once at
        // startup so restarts keep resolving them. The publish merges over
        // the direct layers the trust preload publishes into the same
        // snapshot, and either order converges; a failed preload publishes
        // the unloaded marker so decisions fail closed until the next
        // restart instead of trusting a partial view.
        if (bindingService != null) {
            try {
                bindingService.refresh().whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        getLogger().warning("ChunkLand generic binding preload failed; "
                                + "bindings stay empty until the next restart: " + failure);
                    }
                });
            } catch (RuntimeException failure) {
                getLogger().warning("ChunkLand generic binding preload failed; "
                        + "bindings stay empty until the next restart: " + failure);
            }
        }
        // Optional external limits (LuckPerms when present): discovered once
        // per enable generation and shared by the claim/expand quotas and
        // inspect. Absent stays empty and every limit falls back to config.
        this.limitProvider = discoverLimitProvider();
        // Optional world block-history (CoreProtect when present):
        // discovered once per enable generation and shared by /land history
        // only. Absent stays unavailable and never touches protection.
        this.historyProvider =
                discoverHistoryProvider().orElse(WorldHistoryProvider.empty());
        Supplier<ChunkLandConfig> inspectConfigs = this.configService
                .map(service -> (Supplier<ChunkLandConfig>) service::current)
                .orElse(null);
        Supplier<Optional<ExternalLimitProvider>> inspectProviders = () -> this.limitProvider;
        Map<String, LandCommand.Handler> landHandlers = new HashMap<>(buildLandHandlers(
                this.selectionSessionManager, claimRunner(), claimScanSupplier(),
                        structureRevisions, sublandHandler, this.capabilities.orElse(null),
                         trustHandler, untrustHandler, defaultHandler, banHandler, unbanHandler,
                          expandRunner(), expandCurrentLand(this.protectionStore),
                           shrinkRunner(), expandCurrentLand(this.protectionStore),
                           shrinkTargetOwner(this.protectionStore), renameHandler, groupHandler,
                            profileHandler, bindingHandler,
                             buildExplainHandler(this.protectionStore, () -> atomicContexts,
                                     adminBypassLookup()),
                            expandTargetChunks(this.protectionStore, registryReadiness),
                            economyCurrency(activeConfig.current()),
                            deleteHandler(), logHandler, buildManageHandler(() -> atomicContexts),
                             buildInspectHandler(this.protectionStore, () -> atomicContexts,
                                     inspectConfigs, inspectProviders,
                                     buildOfflinePlayerResolver(getServer(),
                                             this.resolveExecutor),
                                     buildPlayerScheduler(this),
                                     adminBypassLookup()),
                            ledgerAdminHandler,
                            orphanAdminHandler,
                            buildHistoryHandler(() -> this.historyProvider,
                                    buildPlayerScheduler(this))));
        ManagementGateResolver landGateResolver = buildManagementGateResolver(this.protectionStore,
                () -> atomicContexts,
                PluginManagementGateResolver.TargetLandResolver.currentLocation(),
                adminBypassLookup());
        // Bedrock 管理表單分支：與 Java 共用 gate resolver 與不可變快照模型，
        // 轉交的 handlers 直接引用正式 map 的其他槽位（絕不轉交 manage 自己，
        // 所以不會遞迴）。半接線時沒有分支，原本的 Java 路徑原樣保留。
        BedrockManageFormHandler bedrockManage = buildBedrockManageForms(
                this.capabilities.orElse(null),
                landGateResolver,
                (actor, landId) -> buildBedrockManageModel(
                        this.protectionStore, () -> atomicContexts, actor, landId),
                landHandlers,
                this::listOnlinePlayerNames);
        this.bedrockFormNavigator = bedrockManage == null ? null : bedrockManage.navigator();
        landHandlers.put("manage", wrapManageWithBedrock(landHandlers.get("manage"), bedrockManage));
        // Admin bypass toggle: the node only allows attempting the switch;
        // every attempt is audited before the actor-scoped memory flips,
        // then closed by a committed or aborted terminal with the same
        // attempt id. A missing audit source or memory keeps the slot
        // fail-closed instead of toggling without a trail. Toggles wait
        // for the recovery gate: while the full history scan is pending or
        // has failed, every toggle fail-closes for a retryable next enable.
        java.util.UUID bypassProcess = java.util.UUID.randomUUID();
        java.util.concurrent.CompletableFuture<Void> bypassRecoveryGate =
                new java.util.concurrent.CompletableFuture<>();
        com.smile.chunkland.command.AdminBypassLifecycle bypassLifecycle =
                new com.smile.chunkland.command.AdminBypassLifecycle();
        AdminBypassCommandHandler bypassHandler = buildGatedBypassHandler(
                auditReads, this.adminBypassStates, buildPlayerScheduler(this),
                bypassProcess, bypassRecoveryGate, bypassLifecycle);
        this.bypassHandler = bypassHandler;
        this.bypassProcess = bypassProcess;
        this.bypassRecoveryGate = bypassRecoveryGate;
        this.bypassLifecycle = bypassLifecycle;
        landHandlers.put("bypass", bypassHandler == null
                ? (sender, args, sink) -> sink.reply("command.land.bypass.failed",
                        Map.of("reason", "bypass.unavailable"))
                : bypassHandler);
        // Bypass audit recovery: each enable starts with a fresh off memory
        // and a unique process id, so open attempts from crashed generations
        // are aborted without ever touching current-generation writes. The
        // full paged scan runs async; the gate opens only on success and
        // stays closed (retryable next enable) on failure.
        triggerBypassRecovery(auditReads, bypassProcess, bypassRecoveryGate, bypassLifecycle);
        this.landCommand = new LandCommand(
                landHandlers, null, landGateResolver);
        // Wand safety listener: native Bukkit listener for selection wand protection.
        // The selecting handler turns wand hits into selection corners on the
        // same session /land claim reads; safety cancelling stays in the
        // listener so a handler failure can never break or interact blocks.
        // Startup hydration gate: until the durable registry rebuild publishes
        // and marks readiness, the wand occupancy/collision lookups fail closed
        // instead of treating unhydrated durable land as wilderness.
        this.wandSafetyListener = buildWandSafetyListener(
                this.selectionSessionManager,
                () -> new SelectionEditService(
                        activeConfig.current().limits(),
                        wandCollisionIndex(this.protectionStore, registryReadiness)),
                Clock.systemUTC()::instant,
                (player, kind, vars) -> sendWandSelectionMessage(player, kind.messageKey(), vars),
                wandOccupancyLookup(this.protectionStore, registryReadiness),
                wandBoundaryLookup(this.protectionStore, registryReadiness),
                occupiedPreview,
                this.selectionLifecycleListener);
        // ENTRY enforcement reads banned-inside stops from the immutable ban
        // snapshot through a memory-only lookup: no SQL, Bukkit, chunk load
        // or network on the event thread. Unknown or failing answers fail
        // closed inside the adapter.
        EntryBanLookup banLookup = new EntryBanLookup(
                this.protectionStore::snapshot, this.landAuthorisationCache::snapshot);
        this.protectionListener = new ProtectionListener(this.protectionEngine, null, banLookup);
        try {
            registerWandListener(this.wandSafetyListener);
            registerSelectionListener(this.selectionLifecycleListener);
            registerProtectionListener(this.protectionListener);
            if (this.playerSettingsLocaleListener != null) {
                try {
                    registerPlayerSettingsListener(this.playerSettingsLocaleListener);
                } catch (RuntimeException localeListenerFailure) {
                    getLogger().warning("ChunkLand player locale listener registration failed; "
                            + "preferred-locale stays empty: " + localeListenerFailure.getMessage());
                    this.playerSettingsLocaleListener = null;
                }
            }
            if (this.enterLeaveListener != null) {
                try {
                    registerEnterLeaveListener(this.enterLeaveListener);
                } catch (RuntimeException enterLeaveFailure) {
                    getLogger().warning("ChunkLand enter-leave listener registration failed; "
                            + "boundary prompts stay silent: " + enterLeaveFailure.getMessage());
                    this.enterLeaveListener = null;
                }
            }
            try {
                this.orphanCatalogListener =
                        new OrphanWorldCatalogListener(orphanGuard, this::snapshotServerWorlds);
                registerOrphanCatalogListener(this.orphanCatalogListener);
            } catch (RuntimeException catalogListenerFailure) {
                getLogger().warning("ChunkLand orphan catalog listener registration failed; "
                        + "orphan administration stays unavailable: "
                        + catalogListenerFailure.getMessage());
                orphanGuard.invalidate();
                this.orphanCatalogListener = null;
            }
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
        // Expand left the legacy not-yet pool: without a runner the slot stays
        // fail-closed with expand.unavailable, so half-wired maps never pretend
        // the flow is coming soon. The expand-aware overload overwrites this
        // entry with the real handler when a runner exists.
        base.put("expand", (sender, args, sink) ->
                sink.reply("command.land.expand.failed", Map.of("reason", "expand.unavailable")));
        // Shrink and its unclaim alias share one flow: without a runner both
        // slots stay fail-closed with shrink.unavailable instead of the
        // not-yet stub.
        LandCommand.Handler shrinkUnavailable = (sender, args, sink) ->
                sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.unavailable"));
        base.put("shrink", shrinkUnavailable);
        base.put("unclaim", shrinkUnavailable);
        // Delete left the legacy not-yet pool: without a runner the slot stays
        // fail-closed with delete.unavailable, so half-wired maps never pretend
        // the flow is coming soon. The delete-aware overload overwrites this
        // entry with the real handler when a runner exists.
        base.put("delete", (sender, args, sink) ->
                sink.reply("command.land.delete.failed", Map.of("reason", "delete.unavailable")));
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
        return buildLandHandlers(selections, runner, recoveryScan, structures, subland, null);
    }

    /**
     * Production {@code /land} handlers with the Bedrock Modal Form branch
     * wired behind {@code /land claim}.
     *
     * <p>The {@code confirm} slot and the form gateway share one
     * {@link ConfirmCommandHandler} instance, so form confirmations and chat
     * confirmations observe the same token revalidation and the same
     * single-use replay guard. A null capabilities bundle keeps the legacy
     * direct claim path for every sender (AceLib-less wiring and tests).
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers());
        LandCommand.Handler confirm = selections == null
                ? (sender, args, sink) -> sink.reply("command.land.claim.failed", Map.of("reason", "claim.unavailable"))
                : new ConfirmCommandHandler(selections, runner, recoveryScan,
                        structures == null ? SelectionStructureRevisionLookup.unavailable() : structures);
        BedrockClaimFormHandler bedrockForms = buildBedrockClaimForms(capabilities, confirm);
        base.put("claim", selections == null
                ? (sender, args, sink) -> sink.reply("command.land.claim.failed", Map.of("reason", "claim.unavailable"))
                : new ClaimCommandHandler(selections, runner, recoveryScan, bedrockForms));
        base.put("confirm", confirm);
        base.put("subland", subland == null
                ? (sender, args, sink) -> sink.reply("command.land.subland.failed", Map.of("reason", "subland.unavailable"))
                : subland);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with direct trust and land defaults
     * wired.
     *
     * <p>A null trust, untrust or default handler keeps its slot fail-closed:
     * it replies with the matching {@code unavailable} reason instead of the
     * not-yet stub, so an unwired server never pretends the flow is coming
     * soon and never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults) {
        return buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, null, null);
    }

    /**
     * Production {@code /land} handlers with ENTRY bans wired.
     *
     * <p>A null ban or unban handler keeps its slot fail-closed: it replies
     * with the matching {@code unavailable} reason instead of the not-yet
     * stub, so an unwired server never pretends the flow is coming soon and
     * never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban) {
        return buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, null, null);
    }

    /**
     * Production {@code /land} handlers with the land expansion flow wired.
     *
     * <p>A null expand runner keeps the slot fail-closed: it replies
     * {@code command.land.expand.failed} with {@code expand.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired mutation. The current
     * land view resolves the land under the sender; a null view skips the
     * position check (tests that drive the saga tokens directly).
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand) {
        return buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand, null);
    }

    /**
     * Production {@code /land} handlers with the land rename flow wired.
     *
     * <p>A null rename handler keeps the slot fail-closed: it replies
     * {@code command.land.rename.failed} with {@code rename.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            RenameCommandHandler rename) {
        return buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                null, null, rename);
    }

    /**
     * Production {@code /land} handlers with the land shrink flow wired.
     *
     * <p>{@code shrink} and {@code unclaim} share one handler instance and
     * one saga runner: both aliases remove the current selection from the
     * current-location land. A null shrink runner keeps both slots
     * fail-closed with {@code shrink.unavailable} instead of the not-yet
     * stub, so an unwired server never pretends the flow is coming soon and
     * never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            RenameCommandHandler rename) {
        return buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, null, rename);
    }

    /**
     * Production {@code /land} handlers with the land shrink flow wired.
     *
     * <p>{@code shrink} and {@code unclaim} share one handler instance and
     * one saga runner: both aliases remove the current selection from the
     * current-location land. A null shrink runner keeps both slots
     * fail-closed with {@code shrink.unavailable} instead of the not-yet
     * stub, so an unwired server never pretends the flow is coming soon and
     * never runs a half-wired mutation. The target owner view resolves the
     * target land's owner from the already-published snapshot (no I/O), so a
     * Server Land steward passes validation with a zero refund while a
     * player land keeps its owner-only check; a null view keeps the legacy
     * player-only path.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename) {
        Map<String, LandCommand.Handler> base = new HashMap<>(
                buildLandHandlers(selections, runner, recoveryScan, structures, subland, capabilities));
        base.put("trust", trust == null
                ? (sender, args, sink) -> sink.reply("command.land.trust.failed", Map.of("reason", "trust.unavailable"))
                : trust);
        base.put("untrust", untrust == null
                ? (sender, args, sink) -> sink.reply("command.land.untrust.failed", Map.of("reason", "untrust.unavailable"))
                : untrust);
        base.put("default", defaults == null
                ? (sender, args, sink) -> sink.reply("command.land.default.failed", Map.of("reason", "default.unavailable"))
                : defaults);
        base.put("ban", ban == null
                ? (sender, args, sink) -> sink.reply("command.land.ban.failed", Map.of("reason", "ban.unavailable"))
                : ban);
        base.put("unban", unban == null
                ? (sender, args, sink) -> sink.reply("command.land.unban.failed", Map.of("reason", "unban.unavailable"))
                : unban);
        base.put("expand", expand == null || selections == null
                ? (sender, args, sink) -> sink.reply("command.land.expand.failed", Map.of("reason", "expand.unavailable"))
                : new ExpandCommandHandler(selections, expand, recoveryScan, currentLand, null,
                        shrinkTargetOwner));
        LandCommand.Handler shrinkHandler = shrink == null || selections == null
                ? (sender, args, sink) -> sink.reply("command.land.shrink.failed", Map.of("reason", "shrink.unavailable"))
                : new ShrinkCommandHandler(selections, shrink, recoveryScan, shrinkCurrentLand,
                        shrinkTargetOwner);
        base.put("shrink", shrinkHandler);
        base.put("unclaim", shrinkHandler);
        base.put("rename", rename == null
                ? (sender, args, sink) -> sink.reply("command.land.rename.failed", Map.of("reason", "rename.unavailable"))
                : rename);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the Global Group flow wired.
     *
     * <p>A null group handler keeps the slot fail-closed: it replies
     * {@code command.land.group.failed} with {@code group.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group) {
        Map<String, LandCommand.Handler> base = new HashMap<>(
                buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                        capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                        shrink, shrinkCurrentLand, shrinkTargetOwner, rename));
        base.put("group", group == null
                ? (sender, args, sink) -> sink.reply("command.land.group.failed", Map.of("reason", "group.unavailable"))
                : group);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the Permission Profile flow wired.
     *
     * <p>A null profile handler keeps the slot fail-closed: it replies
     * {@code command.land.profile.failed} with {@code profile.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile) {
        Map<String, LandCommand.Handler> base = new HashMap<>(
                buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                        capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                        shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group));
        base.put("profile", profile == null
                ? (sender, args, sink) -> sink.reply("command.land.profile.failed", Map.of("reason", "profile.unavailable"))
                : profile);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the generic binding flow wired.
     *
     * <p>A null binding handler keeps the slot fail-closed: it replies
     * {@code command.land.binding.failed} with {@code binding.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired mutation.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding) {
        Map<String, LandCommand.Handler> base = new HashMap<>(
                buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                        capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                        shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                        profile));
        base.put("binding", binding == null
                ? (sender, args, sink) -> sink.reply("command.land.binding.failed", Map.of("reason", "binding.unavailable"))
                : binding);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the read-only permission explain
     * flow wired.
     *
     * <p>A null explain handler keeps the slot fail-closed: it replies
     * {@code command.land.explain.failed} with {@code explain.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired decision read.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain) {
        Map<String, LandCommand.Handler> base = new HashMap<>(
                buildLandHandlers(selections, runner, recoveryScan, structures, subland,
                        capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                        shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                        profile, binding));
        base.put("explain", explain == null
                ? (sender, args, sink) -> sink.reply("command.land.explain.failed", Map.of("reason", "explain.unavailable"))
                : explain);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the wand-aware expand delta
     * adapter installed. The wiring keeps every other overload untouched so
     * the focused assembly tests stay valid, and only replaces the expand slot
     * with a handler that subtracts the target's immutable chunk set.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain));
        if (expand != null && selections != null) {
            base.put("expand", new ExpandCommandHandler(
                    selections, expand, recoveryScan, currentLand, targetChunks,
                    shrinkTargetOwner));
        }
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the wand-aware expand delta
     * adapter installed and the configured refund currency passed to the
     * shrink reply.
     *
     * <p>The currency is the same typed value the shrink saga charges and
     * refunds with, so the success and compensation-pending replies render the
     * refund in major units ({@code "0.50 EMC"}) instead of the raw
     * minor-unit long. The shrink slot is rebuilt here over the delta-aware map
     * so the currency reaches the handler without disturbing the overloads the
     * focused assembly tests pin. A null currency keeps the legacy raw fallback.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks,
            Currency shrinkRefundCurrency) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain, targetChunks));
        if (shrink != null && selections != null) {
            LandCommand.Handler shrinkHandler = new ShrinkCommandHandler(selections, shrink,
                    recoveryScan, shrinkCurrentLand, shrinkTargetOwner, "shrink",
                    shrinkRefundCurrency);
            base.put("shrink", shrinkHandler);
            base.put("unclaim", shrinkHandler);
        }
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the whole-land delete flow wired.
     *
     * <p>A null delete handler keeps the slot fail-closed: it replies
     * {@code command.land.delete.failed} with {@code delete.unavailable}
     * instead of the not-yet stub, so an unwired server never pretends the
     * flow is coming soon and never runs a half-wired mutation. Every other
     * overload stays untouched so the focused assembly tests pinning them
     * stay valid.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks,
            Currency shrinkRefundCurrency,
            LandDeleteCommandHandler delete,
            AuditLogCommandHandler logs) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain, targetChunks, shrinkRefundCurrency));
        base.put("delete", delete == null
                ? (sender, args, sink) -> sink.reply("command.land.delete.failed",
                        Map.of("reason", "delete.unavailable"))
                : delete);
        base.put("log", logs == null
                ? (sender, args, sink) -> sink.reply("command.land.log.failed",
                        Map.of("reason", "log.unavailable"))
                : logs);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the player-reachable management
     * GUI entry wired behind {@code /land manage}.
     *
     * <p>A null manage handler keeps the slot fail-closed on the generic
     * manage denial instead of the not-yet stub, so an unwired server never
     * pretends the GUI is coming soon. Every other overload stays untouched
     * so the focused assembly tests pinning them stay valid. The opener seam
     * hops to the player's region thread before touching the navigator, so
     * the command thread never opens an inventory directly.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks,
            Currency shrinkRefundCurrency,
            LandDeleteCommandHandler delete,
            AuditLogCommandHandler logs,
            ManageGuiCommandHandler manage) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain, targetChunks, shrinkRefundCurrency,
                delete, logs));
        base.put("manage", manage == null
                ? (sender, args, sink) -> sink.reply("command.land.manage.denied", Map.of())
                : manage);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the read-only land inspect flow
     * wired.
     *
     * <p>A null inspect handler keeps the slot fail-closed on the generic
     * inspect denial instead of the not-yet stub, so an unwired server never
     * pretends the query is coming soon. Every other overload stays untouched
     * so the focused assembly tests pinning them stay valid.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks,
            Currency shrinkRefundCurrency,
            LandDeleteCommandHandler delete,
            AuditLogCommandHandler logs,
            ManageGuiCommandHandler manage,
            InspectCommandHandler inspect) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain, targetChunks, shrinkRefundCurrency,
                delete, logs, manage));
        base.put("inspect", inspect == null
                ? (sender, args, sink) -> sink.reply("command.land.inspect.denied", Map.of())
                : inspect);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the operator ledger flow wired
     * behind {@code /land admin ledger} and the orphan branch fail-closed.
     *
     * <p>A null admin handler keeps the ledger slot fail-closed on the ledger
     * usage reply instead of the not-yet stub, so an unwired server never
     * pretends operator verdicts are coming soon. The orphan branch has no
     * handler here and fails closed on its own unavailable reply. Every other
     * overload stays untouched so the focused assembly tests pinning them stay
     * valid.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks,
            Currency shrinkRefundCurrency,
            LandDeleteCommandHandler delete,
            AuditLogCommandHandler logs,
            ManageGuiCommandHandler manage,
            InspectCommandHandler inspect,
            LedgerAdminCommandHandler admin) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain, targetChunks, shrinkRefundCurrency,
                delete, logs, manage, inspect));
        base.put("admin", new AdminCommandRouter(admin, null));
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the read-only optional
     * block-history flow wired behind {@code /land history}.
     *
     * <p>A null history handler keeps the slot fail-closed on the generic
     * unavailable reply instead of the not-yet stub, so an unwired server
     * never pretends the lookup is coming soon. Every other overload stays
     * untouched so the focused assembly tests pinning them stay valid. The
     * {@code admin} slot keeps the ledger-or-fail-closed router from the
     * previous overload; the orphan branch needs the longer overload below.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks,
            Currency shrinkRefundCurrency,
            LandDeleteCommandHandler delete,
            AuditLogCommandHandler logs,
            ManageGuiCommandHandler manage,
            InspectCommandHandler inspect,
            LedgerAdminCommandHandler admin,
            HistoryCommandHandler history) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain, targetChunks, shrinkRefundCurrency,
                delete, logs, manage, inspect, admin));
        base.put("history", history == null
                ? (sender, args, sink) -> sink.reply("command.land.history.unavailable", Map.of())
                : history);
        return Map.copyOf(base);
    }

    /**
     * Production {@code /land} handlers with the orphan-world administration
     * wired behind {@code /land admin orphan} next to the ledger branch.
     *
     * <p>A null orphan handler keeps only the orphan branch fail-closed on
     * its own unavailable reply; the ledger branch is untouched. Every other
     * overload stays untouched so the focused assembly tests pinning them
     * stay valid.
     */
    static Map<String, LandCommand.Handler> buildLandHandlers(
            SelectionSessionManager selections, ClaimCommandHandler.ClaimRunner runner,
            Supplier<java.util.concurrent.CompletionStage<?>> recoveryScan,
            SelectionStructureRevisionLookup structures,
            SubLandCommandHandler subland, Capabilities capabilities,
            DirectTrustCommandHandler trust, DirectTrustCommandHandler untrust,
            LandDefaultCommandHandler defaults,
            EntryBanCommandHandler ban, EntryBanCommandHandler unban,
            ExpandCommandHandler.ExpandRunner expand,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> currentLand,
            ShrinkCommandHandler.ShrinkRunner shrink,
            java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> shrinkCurrentLand,
            java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner,
            RenameCommandHandler rename,
            GroupCommandHandler group,
            ProfileCommandHandler profile,
            BindingCommandHandler binding,
            ExplainCommandHandler explain,
            ExpandCommandHandler.TargetChunkLookup targetChunks,
            Currency shrinkRefundCurrency,
            LandDeleteCommandHandler delete,
            AuditLogCommandHandler logs,
            ManageGuiCommandHandler manage,
            InspectCommandHandler inspect,
            LedgerAdminCommandHandler admin,
            OrphanAdminCommandHandler orphan,
            HistoryCommandHandler history) {
        Map<String, LandCommand.Handler> base = new HashMap<>(buildLandHandlers(
                selections, runner, recoveryScan, structures, subland,
                capabilities, trust, untrust, defaults, ban, unban, expand, currentLand,
                shrink, shrinkCurrentLand, shrinkTargetOwner, rename, group,
                profile, binding, explain, targetChunks, shrinkRefundCurrency,
                delete, logs, manage, inspect, admin, history));
        base.put("admin", new AdminCommandRouter(admin, orphan));
        return Map.copyOf(base);
    }

    /**
     * Builds the operator ledger handler over the claim bootstrap ledger and
     * the shared audit history. A null bootstrap, ledger, audit source or
     * scheduler keeps the admin slot fail-closed instead of running
     * half-wired; assembly failures degrade to null the same way.
     */
    static LedgerAdminCommandHandler buildLedgerAdminHandler(ClaimStartupBootstrap bootstrap,
            com.smile.chunkland.persistence.AuditRepository audits,
            com.smile.chunkland.command.PlayerScheduler scheduler) {
        if (bootstrap == null || audits == null || scheduler == null) {
            return null;
        }
        try {
            OperationLedger ledger = bootstrap.ledger();
            if (ledger == null) {
                return null;
            }
            return new LedgerAdminCommandHandler(() -> bootstrap.ledger(), () -> audits,
                    Clock.systemUTC()::instant, scheduler);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Builds the {@code /land bypass} toggle over the shared audit history
     * and the enable-generation bypass memory. Every attempt appends one
     * row before flipping the actor-scoped state, then one terminal with
     * the same attempt id; a null audit source, memory or scheduler keeps
     * the slot fail-closed instead of running half-wired. Assembly failures
     * degrade to null the same way.
     */
    static AdminBypassCommandHandler buildBypassHandler(
            com.smile.chunkland.persistence.AuditRepository audits,
            AdminBypassState states,
            com.smile.chunkland.command.PlayerScheduler scheduler) {
        if (audits == null || states == null || scheduler == null) {
            return null;
        }
        try {
            return new AdminBypassCommandHandler(() -> audits,
                    Clock.systemUTC()::instant, states, scheduler);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Builds the gated bypass toggle for one enable generation: toggles
     * stay fail-closed until the recovery gate completes normally. Late
     * callbacks additionally stop once the shared lifecycle closes.
     */
    static AdminBypassCommandHandler buildGatedBypassHandler(
            com.smile.chunkland.persistence.AuditRepository audits,
            AdminBypassState states,
            com.smile.chunkland.command.PlayerScheduler scheduler,
            java.util.UUID processGeneration,
            java.util.concurrent.CompletableFuture<Void> recoveryGate,
            com.smile.chunkland.command.AdminBypassLifecycle lifecycle) {
        if (audits == null || states == null || scheduler == null
                || processGeneration == null || recoveryGate == null) {
            return null;
        }
        try {
            return new AdminBypassCommandHandler(() -> audits,
                    Clock.systemUTC()::instant, states, scheduler,
                    processGeneration, recoveryGate, lifecycle);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Builds the gated bypass toggle for one enable generation: toggles
     * stay fail-closed until the recovery gate completes normally.
     */
    static AdminBypassCommandHandler buildGatedBypassHandler(
            com.smile.chunkland.persistence.AuditRepository audits,
            AdminBypassState states,
            com.smile.chunkland.command.PlayerScheduler scheduler,
            java.util.UUID processGeneration,
            java.util.concurrent.CompletableFuture<Void> recoveryGate) {
        return buildGatedBypassHandler(
                audits, states, scheduler, processGeneration, recoveryGate, null);
    }

    /**
     * Fires the full paged bypass audit recovery without blocking enable.
     * The gate opens only on success; on failure it completes
     * exceptionally so toggles stay fail-closed and open attempts stay
     * retryable on the next enable.
     */
    private void triggerBypassRecovery(com.smile.chunkland.persistence.AuditRepository audits,
            java.util.UUID processGeneration,
            java.util.concurrent.CompletableFuture<Void> gate,
            com.smile.chunkland.command.AdminBypassLifecycle lifecycle) {
        final com.smile.chunkland.persistence.AuditRepository snapshot = audits;
        final java.util.UUID process = processGeneration;
        final java.util.concurrent.CompletableFuture<Void> recoveryGate = gate;
        final com.smile.chunkland.command.AdminBypassLifecycle token = lifecycle;
        try {
            AdminBypassRecovery.recover(() -> snapshot, Clock.systemUTC()::instant,
                    AdminBypassRecovery.PAGE_SIZE, process, token).whenComplete((result, failure) -> {
                try {
                    if (failure != null) {
                        getLogger().warning("ChunkLand bypass audit recovery failed; "
                                + "toggles stay fail-closed and open attempts stay retryable "
                                + "on the next enable: " + failure);
                        recoveryGate.completeExceptionally(failure instanceof RuntimeException runtime
                                ? runtime
                                : new IllegalStateException(failure));
                    } else if (result.failedAttemptIds().isEmpty()) {
                        if (result.openFound() > 0) {
                            getLogger().info("ChunkLand bypass audit recovery aborted "
                                    + result.abortedWritten() + "/" + result.openFound()
                                    + " open attempts.");
                        }
                        recoveryGate.complete(null);
                    } else {
                        getLogger().warning("ChunkLand bypass audit recovery partially failed; "
                                + "toggles stay fail-closed and "
                                + result.failedAttemptIds().size()
                                + " open attempts stay retryable on the next enable.");
                        recoveryGate.completeExceptionally(new IllegalStateException(
                                "bypass recovery left " + result.failedAttemptIds().size()
                                        + " open attempts unwritten"));
                    }
                } catch (RuntimeException ignored) {
                    try {
                        recoveryGate.completeExceptionally(ignored);
                    } catch (RuntimeException nested) {
                    }
                }
            });
        } catch (RuntimeException failure) {
            getLogger().warning("ChunkLand bypass audit recovery failed to start; "
                    + "toggles stay fail-closed and open attempts stay retryable "
                    + "on the next enable: " + failure);
            try {
                recoveryGate.completeExceptionally(failure);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Builds the orphan-world handler over the claim bootstrap store,
     * rebuilder and versioned catalog guard. The guard snapshot is taken on
     * the command thread only, and the rebuilder refreshes the runtime after
     * a successful purge so the deleted world stops publishing. A null
     * bootstrap, store, guard, rebuilder or scheduler keeps the orphan branch
     * fail-closed instead of running half-wired; assembly failures degrade
     * to null the same way.
     */
    static OrphanAdminCommandHandler buildOrphanAdminHandler(ClaimStartupBootstrap bootstrap,
            OrphanWorldGuard guard,
            com.smile.chunkland.command.PlayerScheduler scheduler) {
        if (bootstrap == null || guard == null || scheduler == null) {
            return null;
        }
        try {
            PersistenceStore store = bootstrap.store();
            RuntimeRegistryRebuilder rebuilder = bootstrap.rebuilder();
            if (store == null || rebuilder == null) {
                return null;
            }
            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            return new OrphanAdminCommandHandler(() -> repos, guard,
                    Clock.systemUTC()::instant, scheduler, rebuilder::rebuild);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Snapshot of the currently loaded world UUIDs from the given world
     * source. Any failure — a throwing or null supplier, a null or empty
     * list, a null entry, or an unreadable world UUID — returns {@code null}
     * so the orphan guard stays unverified and every orphan verb fails
     * closed. It never returns an empty set as success: that would
     * misclassify every healthy world as an orphan.
     */
    static Set<UUID> snapshotLoadedWorldIds(Supplier<List<World>> worlds) {
        try {
            return OrphanWorldCatalogListener.copyWorldIds(
                    worlds == null ? null : worlds.get());
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Snapshot of the currently loaded world UUIDs, read from the server on
     * the calling thread. A {@code null} return fails the orphan guard
     * closed; see {@link #snapshotLoadedWorldIds(Supplier)}.
     */
    private Set<UUID> snapshotLoadedWorldIds() {
        return snapshotLoadedWorldIds(this::snapshotServerWorlds);
    }

    /**
     * Live server world list for the catalog snapshot, read on the calling
     * thread. {@code null} on any failure so callers fail closed.
     */
    private List<World> snapshotServerWorlds() {
        try {
            return getServer().getWorlds();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Builds the read-only inspect handler over the shared protection
     * snapshot and the same atomic context provider the enforcement path
     * reads, so every inspect observes the generation it classifies with.
     * Limits resolve per call from the live config snapshot through
     * {@link LimitResolver} (memory only, never I/O), and the optional
     * player argument resolves through the given async resolver. A null
     * store or provider source keeps the slot fail-closed instead of
     * running half-wired.
     */
    static InspectCommandHandler buildInspectHandler(LandRegistryStore store,
            Supplier<PermissionContextProvider> providers,
            Supplier<ChunkLandConfig> configs,
            OfflinePlayerResolver players,
            com.smile.chunkland.command.PlayerScheduler scheduler) {
        return buildInspectHandler(store, providers, configs, Optional::empty,
                players, scheduler);
    }

    static InspectCommandHandler buildInspectHandler(LandRegistryStore store,
            Supplier<PermissionContextProvider> providers,
            Supplier<ChunkLandConfig> configs,
            Supplier<Optional<ExternalLimitProvider>> limitProviders,
            OfflinePlayerResolver players,
            com.smile.chunkland.command.PlayerScheduler scheduler) {
        return buildInspectHandler(store, providers, configs, limitProviders,
                players, scheduler, null);
    }

    static InspectCommandHandler buildInspectHandler(LandRegistryStore store,
            Supplier<PermissionContextProvider> providers,
            Supplier<ChunkLandConfig> configs,
            Supplier<Optional<ExternalLimitProvider>> limitProviders,
            OfflinePlayerResolver players,
            com.smile.chunkland.command.PlayerScheduler scheduler,
            java.util.function.Function<UUID, Boolean> bypassStates) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        Supplier<PermissionContextProvider> activeProviders = providers == null
                ? () -> new SnapshotPermissionContextProvider(null, null) : providers;
        return new InspectCommandHandler(active::snapshot, activeProviders,
                buildInspectLimits(configs, limitProviders), players, scheduler, bypassStates);
    }

    /**
     * Presence-gated discovery for the optional LuckPerms limit provider.
     * Absent, disabled or failing LuckPerms yields empty and every limit
     * falls back to config. Never throws: every failure degrades to empty.
     */
    private Optional<ExternalLimitProvider> discoverLimitProvider() {
        try {
            return LuckPermsDiscovery.discover(() -> {
                try {
                    return getServer().getPluginManager().getPlugin("LuckPerms") != null;
                } catch (RuntimeException unavailable) {
                    return false;
                }
            });
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Presence-gated discovery for the optional CoreProtect history provider.
     * Absent, disabled or failing CoreProtect yields empty and
     * {@code /land history} stays on the generic unavailable reply. Never
     * throws: every failure degrades to empty. Backend lookups share the
     * offline-resolution executor, so they run off every command and region
     * thread; a missing executor keeps history unavailable instead of
     * running a blocking lookup inline.
     */
    private Optional<WorldHistoryProvider> discoverHistoryProvider() {
        try {
            Executor executor = this.resolveExecutor;
            return CoreProtectDiscovery.discover(() -> {
                try {
                    return getServer().getPluginManager().getPlugin("CoreProtect") != null;
                } catch (RuntimeException unavailable) {
                    return false;
                }
            }, executor, this::coreProtectApiHandle, getServer());
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the optional backend API handle by reflective name only, so
     * this class never links against backend types. Runs once at discovery;
     * any failure degrades to an empty provider.
     */
    private Object coreProtectApiHandle() {
        try {
            org.bukkit.plugin.Plugin backend =
                    getServer().getPluginManager().getPlugin("CoreProtect");
            if (backend == null) {
                throw new IllegalStateException("backend plugin absent");
            }
            Object api = backend.getClass().getMethod("getAPI").invoke(backend);
            if (api == null) {
                throw new IllegalStateException("backend handle unavailable");
            }
            return api;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("backend handle unavailable", failure);
        }
    }

    /**
     * Builds the read-only history handler over the shared optional
     * provider. A null source keeps the slot fail-closed on the generic
     * unavailable reply instead of running half-wired.
     */
    static HistoryCommandHandler buildHistoryHandler(
            Supplier<WorldHistoryProvider> providers) {
        return buildHistoryHandler(providers,
                com.smile.chunkland.command.PlayerScheduler.direct());
    }

    static HistoryCommandHandler buildHistoryHandler(
            Supplier<WorldHistoryProvider> providers,
            com.smile.chunkland.command.PlayerScheduler scheduler) {
        return new HistoryCommandHandler(
                providers == null ? WorldHistoryProvider::empty : providers, scheduler);
    }

    /**
     * Memory-only limit observables for inspect: the owner's
     * per-land chunk and subland caps from the live config snapshot. A
     * {@code null} config source falls back to the bundled defaults (the
     * same posture as the permission defaults); a failing read yields an
     * empty map and the land summary is still reported without limit
     * entries, so limits can never block the read path.
     */
    static InspectCommandHandler.Limits buildInspectLimits(Supplier<ChunkLandConfig> configs) {
        return buildInspectLimits(configs, Optional::empty);
    }

    /**
     * Memory-only limit observables for inspect: the owner's
     * per-land chunk and subland caps from the live config snapshot, with an
     * optional external provider (LuckPerms) consulted first per call. Each
     * limit reports its value plus a {@code CONFIG}/{@code PROVIDER} source
     * key; legacy value keys are unchanged. A {@code null} config source
     * falls back to the bundled defaults (the same posture as the permission
     * defaults); a failing read yields an empty map and the land summary is
     * still reported without limit entries, so limits can never block the
     * read path.
     */
    static InspectCommandHandler.Limits buildInspectLimits(Supplier<ChunkLandConfig> configs,
            Supplier<Optional<ExternalLimitProvider>> limitProviders) {
        Supplier<ChunkLandConfig> active =
                configs == null ? ChunkLandConfig::defaults : configs;
        Supplier<Optional<ExternalLimitProvider>> activeProviders =
                limitProviders == null ? Optional::empty : limitProviders;
        return owner -> {
            try {
                if (owner == null) {
                    return Map.of();
                }
                ChunkLandConfig config = active.get();
                if (config == null) {
                    return Map.of();
                }
                Optional<ExternalLimitProvider> provider = activeProviders.get();
                LimitResolver resolver = new LimitResolver(config,
                        provider == null ? Optional.empty() : provider);
                LimitResult maxChunks =
                        resolver.resolve(owner, LimitType.MAX_CHUNKS_PER_LAND);
                LimitResult maxSublands =
                        resolver.resolve(owner, LimitType.MAX_SUBLANDS_PER_LAND);
                return Map.of("limitMaxChunksPerLand", maxChunks.limit(),
                        "limitMaxChunksPerLandSource", maxChunks.source().name(),
                        "limitMaxSublandsPerLand", maxSublands.limit(),
                        "limitMaxSublandsPerLandSource", maxSublands.source().name());
            } catch (RuntimeException failure) {
                return Map.of();
            }
        };
    }

    /**
     * Builds the caller-owned hop back to a player's Folia thread for async
     * command continuations. The returned seam only bridges through
     * {@code player.getScheduler().run} — the same pattern as
     * {@link #openManagementGuiOnRegion} — and drops the continuation
     * fail-closed when the scheduler is retired or the player is gone, so a
     * resolver-executor thread never touches a {@code Player}, a reply sink
     * or any other Bukkit sender API directly.
     */
    static com.smile.chunkland.command.PlayerScheduler buildPlayerScheduler(
            org.bukkit.plugin.java.JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return (player, task) -> {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(task, "task");
            try {
                player.getScheduler().run(plugin, scheduled -> {
                    try {
                        task.run();
                    } catch (RuntimeException ignored) {
                        // Player-thread continuation must never escape.
                    }
                }, null);
            } catch (RuntimeException retired) {
                // Retired scheduler or departed player: drop fail-closed.
            }
        };
    }

    /**
     * Builds the async offline-player resolver: online exact names stay on
     * the region-safe lookup while every other name runs only on the given
     * executor. A {@code null} executor keeps UUID text and online names
     * working and fails every other name closed.
     */
    static OfflinePlayerResolver buildOfflinePlayerResolver(org.bukkit.Server server,
            Executor async) {
        return new OfflinePlayerResolver(
                name -> resolveOnlinePlayerUuid(server, name),
                name -> resolveOfflinePlayerUuid(server, name),
                async);
    }

    /**
     * Blocking offline-player lookup for the async executor only. Never call
     * this on a region thread: {@code getOfflinePlayer(String)} may block on
     * profile lookup. Names that never played stay empty so callers fail
     * closed instead of treating every name as a UUID owner.
     */
    static Optional<UUID> resolveOfflinePlayerUuid(org.bukkit.Server server, String raw) {
        if (server == null || raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = raw.strip();
        try {
            OfflinePlayer offline = server.getOfflinePlayer(stripped);
            if (offline == null) {
                return Optional.empty();
            }
            boolean known;
            try {
                known = offline.hasPlayedBefore() || offline.isOnline();
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
            if (!known) {
                return Optional.empty();
            }
            UUID uuid;
            try {
                uuid = offline.getUniqueId();
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
            return uuid == null ? Optional.empty() : Optional.of(uuid);
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    /**
     * Builds the read-only explain handler over the shared protection
     * snapshot and the same atomic context provider the enforcement path
     * reads, so every explain observes the generation it classifies with.
     * A null store or provider source keeps the slot fail-closed instead of
     * running half-wired.
     */
    static ExplainCommandHandler buildExplainHandler(LandRegistryStore store,
            Supplier<PermissionContextProvider> providers) {
        return buildExplainHandler(store, providers, null);
    }

    static ExplainCommandHandler buildExplainHandler(LandRegistryStore store,
            Supplier<PermissionContextProvider> providers,
            java.util.function.Function<UUID, Boolean> bypassStates) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        Supplier<PermissionContextProvider> activeProviders = providers == null
                ? () -> new SnapshotPermissionContextProvider(null, null) : providers;
        return new ExplainCommandHandler(active::snapshot, activeProviders, bypassStates);
    }

    /**
     * Builds the {@code /land manage} entry over the shared protection
     * snapshot and the same atomic context provider the enforcement path
     * reads. The opener seam hops to the player's region thread before
     * touching the navigator, so the command thread never opens an
     * inventory directly.
     */
    ManageGuiCommandHandler buildManageHandler(
            Supplier<PermissionContextProvider> providers) {
        Supplier<PermissionContextProvider> activeProviders = providers == null
                ? () -> new SnapshotPermissionContextProvider(null, null) : providers;
        LandRegistryStore store = this.protectionStore == null
                ? new LandRegistryStore() : this.protectionStore;
        ManagementGateResolver resolver = buildManagementGateResolver(
                store, activeProviders,
                PluginManagementGateResolver.TargetLandResolver.currentLocation(),
                adminBypassLookup());
        return new ManageGuiCommandHandler(resolver, this::openManagementGuiOnRegion);
    }

    /**
     * Shared per-enable bypass lookup for every management gate seam. Reads
     * the enable-generation memory for one actor UUID; a missing memory, a
     * null actor or any failure resolves to {@code false} (fail-closed).
     * The bypass permission node is never consulted here: only an explicit
     * audited toggle flips the flag, so holding the node alone authorises
     * nothing.
     */
    java.util.function.Function<UUID, Boolean> adminBypassLookup() {
        return uuid -> readAdminBypass(uuid);
    }

    private boolean readAdminBypass(UUID actor) {
        try {
            AdminBypassState states = this.adminBypassStates;
            return states != null && actor != null && states.isOn(actor);
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    /**
     * Region-safe GUI open for one player: hops to the player's region thread
     * (Folia entity scheduler) before reaching {@link #openManagementGui},
     * which re-checks the shared gate over the live snapshot. Best-effort and
     * silent — a retired scheduler or failed open simply leaves the current
     * screen untouched.
     */
    void openManagementGuiOnRegion(Player player, LandId landId) {
        if (player == null || landId == null) {
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException unresolved) {
            return;
        }
        if (actor == null) {
            return;
        }
        try {
            player.getScheduler().run(this, task -> {
                try {
                    openManagementGui(actor, landId);
                } catch (RuntimeException ignored) {
                    // Best-effort open: a failed GUI must not break the command.
                }
            }, null);
        } catch (RuntimeException ignored) {
            // A retired scheduler stays silent; the screen is simply not opened.
        }
    }

    /**
     * Build the durable land authorisation service on the given store. The
     * caller passes null instead when no store is available; handlers then
     * stay fail-closed without side effects and no substitute store is
     * opened to mask the gap.
     */
    /**
     * The shared public-event bus of this enable generation, for external
     * plugins that prefer the API channel over the Bukkit event views.
     * Dispatch is synchronous on the publishing thread; listeners must obey
     * the no-blocking, no-I/O, Folia-thread rules documented on the API
     * events. Never {@code null} after enable; {@code null} before the
     * first enable or after disable.
     */
    public ChunkLandEventBus publicEventBus() {
        return publicEventBus;
    }

    /**
     * Production Bukkit bridge: dispatches one Bukkit view through the given
     * plugin manager, synchronously on the publishing thread. Pre bridges run
     * on the mutation caller thread before any Economy or durable side
     * effect; Post bridges run on the async continuation after the durable
     * commit plus the runtime publish, so Post views carry the async flag
     * and their listeners must hop to the matching Folia thread before
     * touching world state. Package visible so bridge dispatch is covered by
     * an executable test with a fake manager.
     */
    static BukkitEventCaller bridgeCaller(org.bukkit.plugin.PluginManager pluginManager) {
        Objects.requireNonNull(pluginManager, "pluginManager");
        return pluginManager::callEvent;
    }

    static LandAuthorisationService buildLandAuthorisations(PersistenceStore store) {
        return buildLandAuthorisations(store, null);
    }

    static LandAuthorisationService buildLandAuthorisations(PersistenceStore store,
            PublicEvents events) {
        Objects.requireNonNull(store, "store");
        return new LandAuthorisationService(
                new LandAuthorisationRepository(store), new LandAuthorisationCache(),
                Clock.systemUTC(), events);
    }

    /**
     * Build the durable land rename service on the given store. Any missing
     * input yields null instead so the handler slot stays fail-closed
     * without side effects and no substitute store is opened to mask the
     * gap. The shared recovery rebuilder publishes the renamed snapshot,
     * so the live map converges with every other mutation path.
     */
    static LandRenameService buildLandRename(PersistenceStore store, LandRegistryStore registry,
            RuntimeRegistryRebuilder rebuilder) {
        if (store == null || registry == null || rebuilder == null) {
            return null;
        }
        return new LandRenameService(
                new LandRenameRepository(store), registry::snapshot, rebuilder);
    }

    /**
     * Resolves the affected land for a trust or default attempt from the
     * sender's current location against the live immutable snapshot.
     * Wilderness, non-player senders and unresolvable positions stay empty
     * so the caller fails closed.
     */
    static DirectTrustCommandHandler.LandResolver currentLocationLandResolver(LandRegistryStore store) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        PluginManagementGateResolver.TargetLandResolver targets =
                PluginManagementGateResolver.TargetLandResolver.currentLocation();
        return sender -> {
            try {
                return targets.resolveTarget(
                        sender, com.smile.chunkland.api.permission.ProtectionActionType.MANAGE_MEMBER,
                        new String[0], active.snapshot());
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
        };
    }

    /**
     * Resolves the affected subland for a binding attempt from the sender's
     * current block position against the live immutable snapshot. Only the
     * subland covering the position within the given land counts; wilderness
     * positions, positions outside every subland, non-player senders and any
     * unresolvable position stay empty so the caller fails closed.
     */
    static BindingCommandHandler.SublandResolver currentLocationSublandResolver(
            LandRegistryStore store) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return (sender, landId) -> {
            try {
                if (!(sender instanceof Player player) || landId == null) {
                    return Optional.empty();
                }
                Location location = player.getLocation();
                if (location == null) {
                    return Optional.empty();
                }
                World world = location.getWorld();
                if (world == null) {
                    return Optional.empty();
                }
                LandRegistry snapshot = active.snapshot();
                if (snapshot == null || snapshot.land(landId) == null) {
                    return Optional.empty();
                }
                SubLandIndex index = snapshot.subLandIndex(landId);
                if (index == null) {
                    return Optional.empty();
                }
                SubLandSnapshot covering = index.findAtBlock(location.getBlockX(),
                        location.getBlockY(), location.getBlockZ());
                if (covering == null || !landId.equals(covering.parentLandId())) {
                    return Optional.empty();
                }
                return Optional.of(covering.id());
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
        };
    }

    /**
     * Resolves a trust target without any network query: a UUID string
     * parses directly, otherwise only an online exact (case-sensitive) name
     * match counts. Unknown, offline, console and failing lookups stay
     * empty so the caller fails closed.
     */
    static Optional<UUID> resolveOnlinePlayerUuid(org.bukkit.Server server, String raw) {        if (server == null || raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = raw.strip();
        try {
            return Optional.of(UUID.fromString(stripped));
        } catch (IllegalArgumentException notUuid) {
            // Fall through to the online exact-name lookup below.
        }
        try {
            Player online = server.getPlayerExact(stripped);
            if (online == null) {
                return Optional.empty();
            }
            UUID uuid = online.getUniqueId();
            return uuid == null ? Optional.empty() : Optional.of(uuid);
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    /**
     * Build the Bedrock claim form branch for one shared confirmation entry.
     * Null capabilities or a null confirm entry yields null so the caller
     * keeps the legacy direct claim path.
     *
     * <p>Only public AceLib services are touched, and only on demand: the
     * player-kind check reads the live {@link BedrockService} per call (a
     * half-wired bundle without one reports non-Bedrock, keeping the Java
     * path working), and every send re-resolves the form service instead of
     * caching it, so a reloaded or failed AceLib facade is never retained.
     * The form callback is re-dispatched through the bundle's public
     * {@code SafeScheduler.runForPlayer(Player, Runnable)} before it may
     * touch the player, the live selection or the confirmation entry; a
     * bundle without a scheduler fails every callback closed without
     * reaching the saga.
     */
    static BedrockClaimFormHandler buildBedrockClaimForms(
            Capabilities capabilities, LandCommand.Handler confirm) {
        if (capabilities == null || confirm == null) {
            return null;
        }
        return new BedrockClaimFormHandler(
                playerId -> {
                    BedrockService bedrock;
                    try {
                        bedrock = capabilities.bedrockService();
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("BedrockService unavailable", failure);
                    }
                    return bedrock != null && bedrock.isBedrockPlayer(playerId);
                },
                (playerId, spec, callback) -> {
                    FormService forms;
                    try {
                        forms = capabilities.formService();
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("FormService unavailable", failure);
                    }
                    if (forms == null) {
                        throw new IllegalStateException("FormService unavailable");
                    }
                    return forms.sendForm(playerId, spec, callback);
                },
                confirm,
                ClaimFormTexts::forLocale,
                capabilities.scheduler());
    }

    /**
     * Bedrock 表單導航的正式接線：服務按需解析，不快取失效中的 facade。
     * 缺少 capabilities、BedrockService、FormService 或 scheduler 時回傳
     * null，讓 {@code /land manage} 保留原本的 Java 路徑。
     * scheduler 本體導航用不到，但沒有它任何回應都派不出去，
     * 所以半接線一樣視為不可用。
     */
    static BedrockFormNavigator buildBedrockFormNavigator(Capabilities capabilities) {
        if (capabilities == null) {
            return null;
        }
        final BedrockService bedrock;
        try {
            bedrock = capabilities.bedrockService();
        } catch (RuntimeException failure) {
            return null;
        }
        if (bedrock == null) {
            return null;
        }
        final FormService forms;
        try {
            forms = capabilities.formService();
        } catch (RuntimeException failure) {
            return null;
        }
        if (forms == null) {
            return null;
        }
        if (capabilities.scheduler() == null) {
            return null;
        }
        return new BedrockFormNavigator(
                (playerId, spec, callback) -> {
                    FormService live;
                    try {
                        live = capabilities.formService();
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("FormService unavailable", failure);
                    }
                    if (live == null) {
                        throw new IllegalStateException("FormService unavailable");
                    }
                    return live.sendForm(playerId, spec, callback);
                });
    }

    /**
     * {@code /land manage} Bedrock 分支的正式接線：共用 gate resolver 與
     * 不可變快照模型，轉交的 handlers 直接引用正式 map 的其他槽位。
     * handler 只轉交非 manage 槽位，不會遞迴呼叫自己；任一接縫缺失回傳
     * null，保留 Java 路徑。
     */
    static BedrockManageFormHandler buildBedrockManageForms(
            Capabilities capabilities,
            ManagementGateResolver gateResolver,
            BedrockManageFormHandler.ModelSource models,
            Map<String, LandCommand.Handler> handlers,
            BedrockManageFormHandler.OnlineNames onlineNames) {
        if (capabilities == null || gateResolver == null || models == null
                || handlers == null || onlineNames == null) {
            return null;
        }
        BedrockFormNavigator navigator = buildBedrockFormNavigator(capabilities);
        if (navigator == null) {
            return null;
        }
        SafeScheduler scheduler = capabilities.scheduler();
        if (scheduler == null) {
            return null;
        }
        return new BedrockManageFormHandler(
                playerId -> {
                    BedrockService bedrock;
                    try {
                        bedrock = capabilities.bedrockService();
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("BedrockService unavailable", failure);
                    }
                    return bedrock != null && bedrock.isBedrockPlayer(playerId);
                },
                gateResolver,
                models,
                handlers,
                navigator,
                onlineNames,
                scheduler);
    }

    /**
     * {@code manage} 槽位的 Bedrock 包裝：Bedrock 玩家且分支接管時走表單，
     * 其他情況（Java 玩家、半接線、分支回傳 false）一律走原本的 Java 分支。
     * 分支本身永不拋出；萬一拋出也落回 Java，仍受同一個 gate 保護。
     */
    static LandCommand.Handler wrapManageWithBedrock(LandCommand.Handler javaManage,
            BedrockManageFormHandler bedrockManage) {
        if (bedrockManage == null) {
            return javaManage;
        }
        return (sender, args, sink) -> {
            if (sender instanceof Player player) {
                try {
                    if (bedrockManage.handle(player, args, sink)) {
                        return;
                    }
                } catch (RuntimeException ignored) {
                    // 落回 Java 分支；gate 會再檢查一次。
                }
            }
            if (javaManage != null) {
                javaManage.handle(sender, args, sink);
            } else {
                sink.reply("command.land.manage.denied", Map.of());
            }
        };
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
        return buildSubLandRunner(persistence, registry, selections, confirm, depths, limits,
                clock, null);
    }

    static SubLandMutationRunner buildSubLandRunner(
            PersistenceStore persistence,
            LandRegistryStore registry,
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandDepthSource depths,
            LimitSettings limits,
            Clock clock,
            PublicEvents events) {
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
                clock,
                events);
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
     * Resolves the Economy bridge for claim recovery and live claims through
     * the Vault Legacy registration (live: AceEconomy). Absent, unready, or
     * broken providers resolve to the explicitly unavailable bridge: player
     * claims fail closed on {@code economy.unavailable} before any ledger row
     * (the saga checks availability ahead of all side effects), and recovery
     * refunds through it stay fail-safe with unrefunded rows retryable.
     *
     * <p>Package-visible seam so production wiring tests drive the same
     * lookup the server uses without a running Vault plugin.
     */
    static VaultBridge resolveEconomyBridge(org.bukkit.plugin.ServicesManager services,
            java.util.function.Function<java.util.UUID, org.bukkit.OfflinePlayer> players) {
        return VaultServiceDiscovery.resolve(services, players);
    }

    private VaultBridge resolveEconomyBridge() {
        try {
            return resolveEconomyBridge(getServer().getServicesManager(),
                    uuid -> getServer().getOfflinePlayer(uuid));
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("ChunkLand economy lookup failed; "
                    + "claims stay fail-closed: " + e.getMessage());
            return new UnavailableVaultBridge();
        }
    }

    /**
     * Claim pricing from the typed {@code economy} section: owner-total
     * price-per-chunk tiers in the configured currency, shared by claim and
     * expand. Refunds never reprice — shrink always refunds from the durable
     * per-chunk cost basis times the shrink ratio.
     *
     * <p>Fail-closed sentinel: a config without an {@code economy} section
     * (pre-economy file) prices every chunk at zero in the fallback currency.
     * That sentinel can never create a silent free land — a player claim
     * through it is rejected on {@code economy.unavailable} when Vault is
     * down, or on {@code pricing.unavailable} when a provider is up but no
     * tiers are configured — while server land stays free by design without
     * touching Economy.
     */
    static PricingTable claimPricing(ChunkLandConfig config) {
        com.smile.chunkland.config.EconomySettings economy =
                config == null ? null : config.economy();
        if (economy != null) {
            return economy.pricing();
        }
        Currency currency = ClaimStartupBootstrap.CLAIM_CURRENCY;
        return PricingTable.of(List.of(PricingTier.of(PricingTier.UNBOUNDED, Money.zero(currency))));
    }

    /**
     * Claim currency from the typed {@code economy} section, or the fallback
     * currency for pre-economy configs. Recovery refunds and shrink refunds
     * rebuild amounts from durable minor units through this currency, so it
     * must agree with charge time — operators must not change the currency
     * scale while a ledger holds live rows.
     */
    static Currency economyCurrency(ChunkLandConfig config) {
        com.smile.chunkland.config.EconomySettings economy =
                config == null ? null : config.economy();
        return economy == null ? ClaimStartupBootstrap.CLAIM_CURRENCY : economy.currency();
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
        return buildClaimSaga(registryStore, selections, quotas, pricing, reservations,
                ledger, economy, rebuilder, asyncExecutor, structures, worldPolicy, null);
    }

    /**
     * Production claim saga with public Pre/Post events on the shared bus.
     *
     * @param events shared public-event facade; {@code null} disables public
     *         events while the saga still commits exactly as before
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
            WorldClaimPolicy worldPolicy,
            PublicEvents events) {
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
                CLAIM_COMPENSATION_RETRIES,
                events);
    }

    /**
     * Assembles the formal expansion saga from its production parts. Package
     * visible so integration tests drive the same assembly the server uses.
     */
    static ExpandSaga buildExpandSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            PricingTable pricing,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor) {
        return buildExpandSaga(registryStore, selections, quotas, pricing, reservations,
                ledger, economy, rebuilder, asyncExecutor,
                SelectionStructureRevisionLookup.unavailable());
    }

    static ExpandSaga buildExpandSaga(
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
        return buildExpandSaga(registryStore, selections, quotas, pricing, reservations,
                ledger, economy, rebuilder, asyncExecutor, structures,
                WorldClaimPolicy.allowAll());
    }

    /**
     * Production expansion saga with the per-world {@code claim-enabled} gate.
     *
     * <p>The policy runs inside the step-one validator, ahead of every quota,
     * reservation, ledger and economy side effect. A {@code null} policy
     * fails closed to deny-all so a half-wired assembly can never treat an
     * unknown world as enabled.
     */
    static ExpandSaga buildExpandSaga(
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
        return buildExpandSaga(registryStore, selections, quotas, pricing, reservations,
                ledger, economy, rebuilder, asyncExecutor, structures, worldPolicy, null);
    }

    /**
     * Production expansion saga with public Pre/Post events on the shared bus.
     *
     * @param events shared public-event facade; {@code null} disables public
     *         events while the saga still commits exactly as before
     */
    static ExpandSaga buildExpandSaga(
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
            WorldClaimPolicy worldPolicy,
            PublicEvents events) {
        OwnerQuotaService activeQuotas = Objects.requireNonNull(quotas, "quotas");
        SelectionStructureRevisionLookup activeStructures = structures == null
                ? SelectionStructureRevisionLookup.unavailable()
                : structures;
        WorldClaimPolicy activePolicy = worldPolicy == null
                ? WorldClaimPolicy.denyAll("world.unknown")
                : worldPolicy;
        ExpandValidator validator = new SnapshotExpandValidator(
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
        return new ExpandSaga(validator, activeQuotas,
                Objects.requireNonNull(pricing, "pricing"),
                Objects.requireNonNull(reservations, "reservations"),
                Objects.requireNonNull(ledger, "ledger"),
                Objects.requireNonNull(economy, "economy"),
                Objects.requireNonNull(rebuilder, "rebuilder"),
                Clock.systemUTC(),
                Objects.requireNonNull(asyncExecutor, "asyncExecutor"),
                CLAIM_COMPENSATION_RETRIES,
                events);
    }

    /**
     * Current-land view for {@code /land expand}: the land under the sender's
     * position against the immutable snapshot. Wilderness, non-player senders
     * and unresolvable positions stay empty so the handler fails closed. A
     * null store yields a view that always stays empty.
     */
    static java.util.function.Function<org.bukkit.command.CommandSender, Optional<LandId>> expandCurrentLand(
            LandRegistryStore store) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        PluginManagementGateResolver.TargetLandResolver targets =
                PluginManagementGateResolver.TargetLandResolver.currentLocation();
        return sender -> {
            try {
                return targets.resolveTarget(sender,
                        com.smile.chunkland.api.permission.ProtectionActionType.EXPAND_LAND,
                        new String[0], active.snapshot());
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
        };
    }

    /**
     * Target-owner view for {@code /land shrink} and {@code /land unclaim}:
     * the owner of the target land from the already-published immutable
     * snapshot. One volatile snapshot read, no SQL, no Economy, no chunk
     * load. Unknown lands, missing snapshots and lookup failures stay empty
     * so the handler fails closed without touching the saga. A null store
     * yields a view that always stays empty.
     */
    static java.util.function.Function<LandId, Optional<OwnerRef>> shrinkTargetOwner(
            LandRegistryStore store) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return landId -> {
            try {
                if (landId == null) {
                    return Optional.empty();
                }
                var snapshot = active.snapshot();
                if (snapshot == null) {
                    return Optional.empty();
                }
                var land = snapshot.land(landId);
                if (land == null || land.ownerRef() == null) {
                    return Optional.empty();
                }
                return Optional.of(land.ownerRef());
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
        };
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
     * Production permission-defaults cache over the live config snapshot.
     *
     * <p>World names resolve through the startup-captured name table (no
     * Bukkit on the hot path or on reload); unknown entries warn once per
     * resolve and are ignored. A {@code null} config or table fails closed
     * to empty defaults. Worlds created after startup that carry config
     * entries stay {@code INHERIT} until the next restart — the same posture
     * as the protection depth lookup's startup table.
     */
    static PermissionDefaultsCache buildPermissionDefaults(ConfigService config,
                                                           Map<String, UUID> worldIdsByName,
                                                           java.util.function.Consumer<String> warnings) {
        Map<String, UUID> table = worldIdsByName == null ? Map.of() : Map.copyOf(worldIdsByName);
        java.util.function.Supplier<ChunkLandConfig> configs =
                config == null ? ChunkLandConfig::defaults : config::current;
        return new PermissionDefaultsCache(configs,
                name -> Optional.ofNullable(name == null ? null : table.get(name)),
                warnings);
    }

    /**
     * Startup snapshot of world config name to world UUID, read once at enable.
     * Config world entries absent from this table warn and stay {@code INHERIT}
     * (fail-closed) until the world is known at a later restart.
     */
    private Map<String, UUID> snapshotWorldIdsByName() {
        Map<String, UUID> ids = new HashMap<>();
        try {
            for (org.bukkit.World world : getServer().getWorlds()) {
                if (world == null) {
                    continue;
                }
                try {
                    ids.putIfAbsent(world.getName(), world.getUID());
                } catch (RuntimeException ignored) {
                    // One unreadable world must not poison the rest of the table.
                }
            }
        } catch (RuntimeException ignored) {
            // No world list at all: every world default stays INHERIT.
        }
        return Map.copyOf(ids);
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
            this.claimQuotas = new OwnerQuotaService(
                    new LimitResolver(config.current(), this.limitProvider));
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
                    this.claimQuotas, claimPricing(config.current()), this.claimReservations,
                    bootstrap.ledger(), bootstrap.economy(), bootstrap.rebuilder(), this.claimExecutor,
                    this.selectionStructureRevisions,
                    buildWorldClaimPolicy(config,
                            uuid -> Optional.ofNullable(getServer().getWorld(uuid))
                                    .map(org.bukkit.World::getName)),
                    this.publicEvents);
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
     * Live expansion runner sharing the claim flow's quotas, reservations,
     * executor, ledger, Economy and rebuilder, so live expansions and claims
     * converge on one durable row and one refund cache. Null when the claim
     * flow never assembled (quotas, reservations or executor missing) or the
     * recovery bootstrap is absent: the handler then replies
     * {@code expand.unavailable} instead of running half-wired.
     *
     * <p>Must run after {@link #claimRunner()}: the shared quota, reservation
     * and executor instances are created there.
     */
    private ExpandCommandHandler.ExpandRunner expandRunner() {
        ClaimStartupBootstrap bootstrap = this.claimStartup;
        SelectionSessionManager selections = this.selectionSessionManager;
        OwnerQuotaService quotas = this.claimQuotas;
        LogicalReservationRegistry reservations = this.claimReservations;
        Executor async = this.claimExecutor;
        if (bootstrap == null || selections == null || quotas == null
                || reservations == null || async == null) {
            return null;
        }
        try {
            ConfigService config = this.configService.orElseThrow(
                    () -> new IllegalStateException("ChunkLand config service is unavailable"));
            this.expandSaga = buildExpandSaga(this.protectionStore, selections,
                    quotas, claimPricing(config.current()), reservations,
                    bootstrap.ledger(), bootstrap.economy(), bootstrap.rebuilder(), async,
                    this.selectionStructureRevisions,
                    buildWorldClaimPolicy(config,
                            uuid -> Optional.ofNullable(getServer().getWorld(uuid))
                                    .map(org.bukkit.World::getName)),
                    this.publicEvents);
            return this.expandSaga::expand;
        } catch (RuntimeException failure) {
            getLogger().warning("ChunkLand expand flow assembly failed; "
                    + "/land expand stays unavailable: " + failure.getMessage());
            this.expandSaga = null;
            return null;
        }
    }

    /**
     * Assembles the formal shrink saga from its production parts. Package
     * visible so integration tests drive the same assembly the server uses.
     *
     * <p>Unlike expansion there is no world-claim gate: a disabled world
     * forbids new claims but existing lands may still shrink with a refund.
     * The refund amount always comes from the durable per-chunk cost basis
     * times the shrink ratio, never from the current pricing table.
     */
    static ShrinkSaga buildShrinkSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor,
            SelectionStructureRevisionLookup structures,
            com.smile.chunkland.persistence.ChunkRepository chunkRepository,
            com.smile.chunkland.api.money.Currency currency) {
        return buildShrinkSaga(registryStore, selections, quotas, reservations, ledger,
                economy, rebuilder, asyncExecutor, structures, chunkRepository, currency, null);
    }

    /**
     * Shrink saga with public Pre/Post events on the shared bus.
     *
     * @param events shared public-event facade; {@code null} disables public
     *         events while the saga still commits exactly as before
     */
    static ShrinkSaga buildShrinkSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor,
            SelectionStructureRevisionLookup structures,
            com.smile.chunkland.persistence.ChunkRepository chunkRepository,
            com.smile.chunkland.api.money.Currency currency,
            PublicEvents events) {
        ShrinkValidator validator = new SnapshotShrinkValidator(
                Objects.requireNonNull(registryStore, "registryStore"),
                actorUuid -> selections.sessionFor(actorUuid)
                        .map(session -> OptionalLong.of(session.selectionRevision()))
                        .orElseGet(OptionalLong::empty),
                actorUuid -> selections.sessionFor(actorUuid)
                        .map(session -> OptionalLong.of(session.sessionGeneration()))
                        .orElseGet(OptionalLong::empty),
                targetLandId -> (structures == null
                        ? SelectionStructureRevisionLookup.unavailable() : structures)
                        .currentRevision(targetLandId));
        return new ShrinkSaga(validator,
                Objects.requireNonNull(chunkRepository, "chunkRepository"),
                Objects.requireNonNull(currency, "currency"),
                Objects.requireNonNull(reservations, "reservations"),
                Objects.requireNonNull(ledger, "ledger"),
                Objects.requireNonNull(economy, "economy"),
                Objects.requireNonNull(rebuilder, "rebuilder"),
                Objects.requireNonNull(quotas, "quotas"),
                Clock.systemUTC(),
                Objects.requireNonNull(asyncExecutor, "asyncExecutor"),
                CLAIM_COMPENSATION_RETRIES,
                events);
    }

    /**
     * Live shrink runner sharing the claim flow's quotas, reservations,
     * executor, ledger, Economy and rebuilder, so live shrinks serialize with
     * claims and expansions on one durable row and one reservation registry.
     * Null when the claim flow never assembled (quotas, reservations or
     * executor missing) or the recovery bootstrap is absent: both aliases
     * then reply {@code shrink.unavailable} instead of running half-wired.
     *
     * <p>Must run after {@link #claimRunner()}: the shared quota, reservation
     * and executor instances are created there.
     */
    private ShrinkCommandHandler.ShrinkRunner shrinkRunner() {
        ClaimStartupBootstrap bootstrap = this.claimStartup;
        SelectionSessionManager selections = this.selectionSessionManager;
        OwnerQuotaService quotas = this.claimQuotas;
        LogicalReservationRegistry reservations = this.claimReservations;
        Executor async = this.claimExecutor;
        if (bootstrap == null || selections == null || quotas == null
                || reservations == null || async == null) {
            return null;
        }
        try {
            // Shrink refunds rebuild amounts from durable minor units, so the
            // currency must be the same typed one the bootstrap charges and
            // refunds with — never a hardcoded constant that could drift.
            Currency shrinkCurrency = economyCurrency(
                    this.configService.map(ConfigService::current).orElse(null));
            this.shrinkSaga = buildShrinkSaga(this.protectionStore, selections,
                    quotas, reservations,
                    bootstrap.ledger(), bootstrap.economy(), bootstrap.rebuilder(), async,
                    this.selectionStructureRevisions,
                    new com.smile.chunkland.persistence.SqliteChunkRepository(bootstrap.store()),
                    shrinkCurrency, this.publicEvents);
            return this.shrinkSaga::shrink;
        } catch (RuntimeException failure) {
            getLogger().warning("ChunkLand shrink flow assembly failed; "
                    + "/land shrink stays unavailable: " + failure.getMessage());
            this.shrinkSaga = null;
            return null;
        }
    }

    /**
     * Assembles the formal whole-land delete saga from its production parts.
     * Package visible so integration tests drive the same assembly the server
     * uses.
     *
     * <p>Unlike shrink there is no selection token: the delete validator only
     * checks the structure-revision token against the live source. The refund
     * is always the full durable per-chunk cost basis, never the current
     * pricing table and never the shrink half-ratio; Server Land refunds zero
     * without touching Economy.
     */
    static DeleteSaga buildDeleteSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor,
            SelectionStructureRevisionLookup structures,
            com.smile.chunkland.persistence.ChunkRepository chunkRepository) {
        return buildDeleteSaga(registryStore, selections, quotas, reservations, ledger,
                economy, rebuilder, asyncExecutor, structures, chunkRepository, null);
    }

    /**
     * Delete saga with public Pre/Post events on the shared bus.
     *
     * @param events shared public-event facade; {@code null} disables public
     *         events while the saga still commits exactly as before
     */
    static DeleteSaga buildDeleteSaga(
            LandRegistryStore registryStore,
            SelectionSessionManager selections,
            OwnerQuotaService quotas,
            LogicalReservationRegistry reservations,
            OperationLedger ledger,
            ClaimEconomy economy,
            com.smile.chunkland.claim.RuntimeRegistryRebuilder rebuilder,
            Executor asyncExecutor,
            SelectionStructureRevisionLookup structures,
            com.smile.chunkland.persistence.ChunkRepository chunkRepository,
            PublicEvents events) {
        DeleteValidator validator = new SnapshotDeleteValidator(
                Objects.requireNonNull(registryStore, "registryStore"),
                targetLandId -> (structures == null
                        ? SelectionStructureRevisionLookup.unavailable() : structures)
                        .currentRevision(targetLandId));
        return new DeleteSaga(validator,
                Objects.requireNonNull(chunkRepository, "chunkRepository"),
                Objects.requireNonNull(reservations, "reservations"),
                Objects.requireNonNull(ledger, "ledger"),
                Objects.requireNonNull(economy, "economy"),
                Objects.requireNonNull(rebuilder, "rebuilder"),
                Objects.requireNonNull(quotas, "quotas"),
                Objects.requireNonNull(selections, "selections"),
                Clock.systemUTC(),
                Objects.requireNonNull(asyncExecutor, "asyncExecutor"),
                CLAIM_COMPENSATION_RETRIES,
                events);
    }

    /**
     * Live delete runner sharing the claim flow's quotas, reservations,
     * executor, ledger, Economy and rebuilder, so live deletes serialize with
     * claims, expansions and shrinks on one durable row and one reservation
     * registry. Null when the claim flow never assembled (quotas,
     * reservations or executor missing) or the recovery bootstrap is absent:
     * the slot then replies {@code delete.unavailable} instead of running
     * half-wired.
     *
     * <p>Must run after {@link #claimRunner()}: the shared quota, reservation
     * and executor instances are created there.
     */
    private LandDeleteCommandHandler.DeleteRunner deleteRunner() {
        ClaimStartupBootstrap bootstrap = this.claimStartup;
        SelectionSessionManager selections = this.selectionSessionManager;
        OwnerQuotaService quotas = this.claimQuotas;
        LogicalReservationRegistry reservations = this.claimReservations;
        Executor async = this.claimExecutor;
        if (bootstrap == null || selections == null || quotas == null
                || reservations == null || async == null) {
            return null;
        }
        try {
            this.deleteSaga = buildDeleteSaga(this.protectionStore, selections,
                    quotas, reservations,
                    bootstrap.ledger(), bootstrap.economy(), bootstrap.rebuilder(), async,
                    this.selectionStructureRevisions,
                    new com.smile.chunkland.persistence.SqliteChunkRepository(bootstrap.store()),
                    this.publicEvents);
            return this.deleteSaga::delete;
        } catch (RuntimeException failure) {
            getLogger().warning("ChunkLand delete flow assembly failed; "
                    + "/land delete stays unavailable: " + failure.getMessage());
            this.deleteSaga = null;
            return null;
        }
    }

    /**
     * Target display view for {@code /land delete}: the name, world and chunk
     * count of the target land from the already-published immutable snapshot.
     * One volatile snapshot read, no SQL, no Economy, no chunk load. Unknown
     * lands, missing snapshots and lookup failures stay empty so the handler
     * fails closed without touching the saga. A null store yields a view that
     * always stays empty.
     */
    static java.util.function.Function<LandId, Optional<LandDeleteCommandHandler.TargetView>> deleteTargetView(
            LandRegistryStore store) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return landId -> {
            try {
                if (landId == null) {
                    return Optional.empty();
                }
                var snapshot = active.snapshot();
                if (snapshot == null) {
                    return Optional.empty();
                }
                var land = snapshot.land(landId);
                if (land == null || land.displayName() == null || land.displayName().isBlank()
                        || land.worldId() == null || land.chunks().isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new LandDeleteCommandHandler.TargetView(
                        land.displayName(), land.worldId(), land.chunks().size()));
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
        };
    }

    /**
     * Live delete handler sharing the current-land view, the target owner
     * view and the target display view over the published snapshot. Null when
     * the runner or the selection registry is missing: the handler map then
     * keeps {@code /land delete} fail-closed.
     */
    private LandDeleteCommandHandler deleteHandler() {
        LandDeleteCommandHandler.DeleteRunner runner = deleteRunner();
        SelectionSessionManager selections = this.selectionSessionManager;
        if (runner == null || selections == null) {
            return null;
        }
        return new LandDeleteCommandHandler(runner, claimScanSupplier(),
                expandCurrentLand(this.protectionStore), shrinkTargetOwner(this.protectionStore),
                deleteTargetView(this.protectionStore), this.selectionStructureRevisions,
                economyCurrency(this.configService.map(ConfigService::current).orElse(null)));
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
                    activeConfig.current().limits(), Clock.systemUTC(), this.publicEvents);
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
        return buildManagementGateResolver(store, null);
    }

    /**
     * Production resolver with an explicit bypass source, so the command
     * dispatcher, the GUI entry, the Bedrock branch and the fail-closed
     * fallback all read the same per-enable bypass memory. A {@code null}
     * source keeps the legacy fail-closed behaviour.
     */
    static ManagementGateResolver buildManagementGateResolver(LandRegistryStore store,
            java.util.function.Function<UUID, Boolean> bypassStates) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return new PluginManagementGateResolver(
                active::snapshot,
                () -> new SnapshotPermissionContextProvider(LandRuleService.defaults(), null),
                PluginManagementGateResolver.TargetLandResolver.currentLocation(),
                bypassStates);
    }

    /**
     * Production resolver with explicit sources, so tests can inject a
     * snapshot, provider and target without starting a server.
     */
    static ManagementGateResolver buildManagementGateResolver(
            LandRegistryStore store,
            java.util.function.Supplier<PermissionContextProvider> providers,
            PluginManagementGateResolver.TargetLandResolver targets) {
        return buildManagementGateResolver(store, providers, targets, null);
    }

    /**
     * Production resolver with explicit sources and an explicit bypass
     * source, so tests can inject a snapshot, provider, target and bypass
     * memory without starting a server.
     */
    static ManagementGateResolver buildManagementGateResolver(
            LandRegistryStore store,
            java.util.function.Supplier<PermissionContextProvider> providers,
            PluginManagementGateResolver.TargetLandResolver targets,
            java.util.function.Function<UUID, Boolean> bypassStates) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return new PluginManagementGateResolver(
                active::snapshot,
                providers == null ? () -> new SnapshotPermissionContextProvider(null, null) : providers,
                targets,
                bypassStates);
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
     * Kept for existing callers and tests; new production wiring uses the
     * atomic provider overload below.
     */
    static ProtectionEngine buildProtectionEngine(LandRegistryStore store,
                                                  LandRuleLookup ruleLookup,
                                                  SubjectPermissionLookup subjectLookup) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        return new ProtectionEngine(active::snapshot,
                new SnapshotPermissionContextProvider(ruleLookup, subjectLookup));
    }

    /**
     * Builds the protection engine over one atomic config view per decision.
     * A {@code null} provider stays fail-closed with inherit-only layers.
     */
    static ProtectionEngine buildProtectionEngine(LandRegistryStore store,
                                                  PermissionContextProvider provider) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        PermissionContextProvider activeProvider = provider == null
                ? ProtectionEngine.inheritOnlyProvider() : provider;
        return new ProtectionEngine(active::snapshot, activeProvider);
    }

    /**
     * Builds the protection engine with decision-cache reuse. A
     * {@code null} cache or {@code null} epoch source keeps the exact
     * behaviour of the uncached overload above.
     */
    static ProtectionEngine buildProtectionEngine(LandRegistryStore store,
                                                  PermissionContextProvider provider,
                                                  PermissionDecisionCache cache,
                                                  PermissionDecisionEpochSource epochs) {
        LandRegistryStore active = store == null ? new LandRegistryStore() : store;
        PermissionContextProvider activeProvider = provider == null
                ? ProtectionEngine.inheritOnlyProvider() : provider;
        return new ProtectionEngine(active::snapshot, activeProvider, cache, epochs);
    }

    void registerWandListener(WandSafetyListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    void registerOrphanCatalogListener(OrphanWorldCatalogListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    /**
     * Production wand listener: safety cancelling plus corner selection on
     * the live session registry. Public so tests drive the same assembly
     * the server uses and prove it is not the no-op seam.
     */
    public static WandSafetyListener buildWandSafetyListener(
            SelectionSessionManager manager,
            java.util.function.Supplier<SelectionEditService> editServices,
            SelectionClock clock) {
        return buildWandSafetyListener(manager, editServices, clock, WandFeedback.none());
    }

    /**
     * Production assembly with the guidance-prompt seam. The feedback sink
     * names each step's lang key; the listener/handler never render text.
     */
    public static WandSafetyListener buildWandSafetyListener(
            SelectionSessionManager manager,
            java.util.function.Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback) {
        return buildWandSafetyListener(manager, editServices, clock, feedback, SelectionLandLookup.none());
    }

    /**
     * Production assembly with the ownership-aware first-point guard. The
     * lookup only reads the immutable registry snapshot, so the wand path
     * still never touches a World, Chunk, SQL or the network.
     */
    public static WandSafetyListener buildWandSafetyListener(
            SelectionSessionManager manager,
            java.util.function.Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback,
            SelectionLandLookup landLookup) {
        return buildWandSafetyListener(manager, editServices, clock, feedback, landLookup,
                SelectionLandBoundaryLookup.none(), OccupiedPreviewController.noop());
    }

    /**
     * Full production assembly: the ownership-aware first-point guard, the
     * immutable boundary lookup for the occupied preview, and the separate
     * preview renderer. None of these touch a World, Chunk, SQL or network on
     * the click path.
     */
    public static WandSafetyListener buildWandSafetyListener(
            SelectionSessionManager manager,
            java.util.function.Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback,
            SelectionLandLookup landLookup,
            SelectionLandBoundaryLookup boundaryLookup,
            OccupiedPreviewController occupiedPreview) {
        return buildWandSafetyListener(
                manager, editServices, clock, feedback, landLookup, boundaryLookup, occupiedPreview, null);
    }

    /**
     * Same assembly, additionally binding the lifecycle listener to the wand
     * handler so quit / world change / cross-world respawn forgets the
     * per-player prompt/preview dedup it just tore down.
     */
    public static WandSafetyListener buildWandSafetyListener(
            SelectionSessionManager manager,
            java.util.function.Supplier<SelectionEditService> editServices,
            SelectionClock clock,
            WandFeedback feedback,
            SelectionLandLookup landLookup,
            SelectionLandBoundaryLookup boundaryLookup,
            OccupiedPreviewController occupiedPreview,
            SelectionLifecycleListener lifecycleListener) {
        SelectionWandClickHandler clickHandler = new SelectionWandClickHandler(
                manager, editServices, clock, feedback, landLookup, boundaryLookup, occupiedPreview);
        if (lifecycleListener != null) {
            lifecycleListener.bindWandStateReset(clickHandler::onSelectionCleared);
        }
        return new WandSafetyListener(clickHandler);
    }

    /**
     * Render one selection guidance/end prompt through the shared message
     * pipeline. The language key and its variables are chosen by the caller;
     * nothing here builds raw text, and a missing pipeline (AceLib not ready)
     * stays silent.
     */
    private void sendWandSelectionMessage(
            org.bukkit.entity.Player player, String messageKey, java.util.Map<String, Object> vars) {
        if (player == null || messageKey == null) {
            return;
        }
        java.util.Map<String, Object> safeVars = vars == null ? java.util.Map.of() : vars;
        landMessagePipeline.ifPresent(pipeline -> {
            try {
                pipeline.sendChat(player, messageKey, safeVars, null);
            } catch (RuntimeException ignored) {
                // A failed prompt must never break the selection/event path.
            }
        });
    }

    /**
     * Memory-only collision lookup over the volatile registry snapshot.
     * Unknown worlds read as wilderness; a failing snapshot read propagates
     * so the click handler fails closed instead of selecting blindly.
     */
    private static SelectionLandIndex wandCollisionIndex(
            LandRegistryStore registryStore, RegistryReadiness readiness) {
        return (worldId, packed) -> {
            if (readiness == null || !readiness.isReady()) {
                // Unhydrated registry: unknown must not read as wilderness.
                throw new IllegalStateException("land registry is not hydrated");
            }
            LandRegistryStore store = registryStore;
            if (store == null) {
                return null;
            }
            LandRegistry snapshot = store.snapshot();
            if (snapshot == null) {
                return null;
            }
            return snapshot.findLandIdPacked(worldId, packed);
        };
    }

    /**
     * Memory-only occupancy lookup for the wand's ownership-aware first point.
     * Unknown worlds and wilderness read as {@code null}; a failing snapshot
     * read or an unhydrated registry propagates so the guard fails closed
     * instead of selecting blindly.
     */
    private static SelectionLandLookup wandOccupancyLookup(
            LandRegistryStore registryStore, RegistryReadiness readiness) {
        return (worldId, chunkX, chunkZ) -> {
            if (readiness == null || !readiness.isReady()) {
                // Unhydrated registry: unknown must not read as wilderness.
                throw new IllegalStateException("land registry is not hydrated");
            }
            LandRegistryStore store = registryStore;
            if (store == null) {
                return null;
            }
            LandRegistry snapshot = store.snapshot();
            if (snapshot == null) {
                return null;
            }
            com.smile.chunkland.api.land.LandId landId = snapshot.findLandId(worldId, chunkX, chunkZ);
            if (landId == null) {
                return null;
            }
            com.smile.chunkland.api.land.LandSnapshot land = snapshot.land(landId);
            if (land == null) {
                return null;
            }
            return new SelectionLandContext(landId, land.ownerRef(), land.structureRevision());
        };
    }

    /**
     * Read-only boundary lookup for the occupied-land preview: the land's real
     * immutable chunk set comes from the same volatile registry snapshot, so
     * irregular shapes are outlined exactly. An unhydrated registry returns
     * empty (no preview) rather than guessing wilderness.
     */
    private static SelectionLandBoundaryLookup wandBoundaryLookup(
            LandRegistryStore registryStore, RegistryReadiness readiness) {
        return landId -> {
            if (readiness == null || !readiness.isReady()) {
                return java.util.Optional.empty();
            }
            LandRegistryStore store = registryStore;
            if (store == null || landId == null) {
                return java.util.Optional.empty();
            }
            LandRegistry snapshot = store.snapshot();
            if (snapshot == null) {
                return java.util.Optional.empty();
            }
            com.smile.chunkland.api.land.LandSnapshot land = snapshot.land(landId);
            if (land == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(land.chunks());
        };
    }

    /**
     * Read-only target-chunk lookup for the {@code /land expand} delta: the
     * target land's immutable chunk set comes from the same volatile registry
     * snapshot everything else reads, so the expanded delta is exactly the
     * selected chunks the target does not already own. No second registry is
     * built and no World, Chunk, SQL or network access happens here.
     */
    private static ExpandCommandHandler.TargetChunkLookup expandTargetChunks(
            LandRegistryStore registryStore, RegistryReadiness readiness) {
        return landId -> {
            if (readiness == null || !readiness.isReady()) {
                return java.util.Optional.empty();
            }
            LandRegistryStore store = registryStore;
            if (store == null || landId == null) {
                return java.util.Optional.empty();
            }
            LandRegistry snapshot = store.snapshot();
            if (snapshot == null) {
                return java.util.Optional.empty();
            }
            com.smile.chunkland.api.land.LandSnapshot land = snapshot.land(landId);
            if (land == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(land.chunks());
        };
    }

    void registerSelectionListener(SelectionLifecycleListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    void registerProtectionListener(ProtectionListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    void registerPlayerSettingsListener(PlayerSettingsLocaleListener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    void registerEnterLeaveListener(EnterLeaveListener listener) {
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
        if (occupiedPreviewController != null) {
            try {
                occupiedPreviewController.stopAll();
            } catch (RuntimeException ignored) {
            }
        }
        if (selectionSessionManager != null && configService != null && configService.isPresent()) {
            try {
                configService.get().removeListener(selectionSessionManager);
            } catch (RuntimeException ignored) {
            }
        }
        // Detach the permission defaults cache before clearing it below: a
        // retained config service reloading after disable must not refresh
        // the disabled cache.
        PermissionDefaultsCache defaults = this.permissionDefaults;
        if (defaults != null && configService != null && configService.isPresent()) {
            try {
                configService.get().removeListener(defaults);
            } catch (RuntimeException ignored) {
            }
        }
        // Detach the decision-cache budget sync for the same reason, then
        // drop the cached decisions themselves: disable leaves no reused
        // verdict behind for the next enable generation.
        ConfigReloadListener budgetSync = this.decisionCacheBudgetSync;
        if (budgetSync != null && configService != null && configService.isPresent()) {
            try {
                configService.get().removeListener(budgetSync);
            } catch (RuntimeException ignored) {
            }
        }
        this.decisionCacheBudgetSync = null;
        PermissionDecisionCache decisions = this.decisionCache;
        if (decisions != null) {
            try {
                decisions.clear();
            } catch (RuntimeException ignored) {
            }
        }
        this.decisionCache = null;
        // Bypass lifecycle: close the generation first so late handler and
        // recovery callbacks stop before any further audit or state
        // mutation, fail any still-pending recovery gate, then drop the
        // memory so the next enable starts with every actor off. Clearing
        // comes before nulling so a concurrent reader observes either the
        // old memory or empty, never a half-cleared set reused later.
        try {
            com.smile.chunkland.command.AdminBypassLifecycle token = this.bypassLifecycle;
            if (token != null) {
                token.close();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            java.util.concurrent.CompletableFuture<Void> gate = this.bypassRecoveryGate;
            if (gate != null && !gate.isDone()) {
                gate.completeExceptionally(new IllegalStateException("disabled"));
            }
        } catch (RuntimeException ignored) {
        }
        this.bypassHandler = null;
        this.bypassProcess = null;
        this.bypassRecoveryGate = null;
        this.bypassLifecycle = null;
        AdminBypassState bypass = this.adminBypassStates;
        if (bypass != null) {
            try {
                bypass.clear();
            } catch (RuntimeException ignored) {
            }
        }
        this.adminBypassStates = null;
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
        // Preferred-locale snapshot: unregister first so no join repopulates
        // the cache, detach the pipeline lookup, then drop the snapshot. The
        // repository holds no closeable of its own; the store stays owned by
        // claim recovery below.
        if (playerSettingsLocaleListener != null) {
            try {
                HandlerList.unregisterAll(playerSettingsLocaleListener);
            } catch (RuntimeException ignored) {
            }
            playerSettingsLocaleListener = null;
        }
        // Enter-leave prompts: unregister first so no move repopulates the
        // tracker, then drop the boundary memory and the switch snapshot.
        if (enterLeaveListener != null) {
            try {
                HandlerList.unregisterAll(enterLeaveListener);
            } catch (RuntimeException ignored) {
            }
            enterLeaveListener = null;
        }
        if (enterLeaveTracker != null) {
            try {
                enterLeaveTracker.clear();
            } catch (RuntimeException ignored) {
            }
            enterLeaveTracker = null;
        }
        if (enterLeavePreferences != null) {
            try {
                enterLeavePreferences.clear();
            } catch (RuntimeException ignored) {
            }
            enterLeavePreferences = null;
        }
        try {
            this.landMessagePipeline.ifPresent(pipeline -> pipeline.setPreferredLocaleLookup(null));
        } catch (RuntimeException ignored) {
        }
        if (playerLocaleService != null) {
            try {
                playerLocaleService.clear();
            } catch (RuntimeException ignored) {
            }
            playerLocaleService = null;
        }
        playerSettingsRepository = null;
        protectionEngine = null;
        protectionStore = null;
        protectionDepthLookup = null;
        permissionDefaults = null;
        // Land authorisation holds no closeable of its own: the store stays
        // owned by claim recovery above. Dropping both references is enough
        // to stop further trust/default mutations from reaching storage, and
        // repeats stay no-ops.
        landAuthorisationService = null;
        landAuthorisationCache = null;
        landRenameService = null;
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
        deleteSaga = null;
        claimQuotas = null;
        claimReservations = null;
        expandSaga = null;
        shrinkSaga = null;
        subLandConfirm = null;
        subLandRunner = null;        if (claimExecutor != null) {
            try {
                claimExecutor.shutdownNow();
            } catch (RuntimeException ignored) {
            }
            claimExecutor = null;
        }
        if (resolveExecutor != null) {
            try {
                resolveExecutor.shutdownNow();
            } catch (RuntimeException ignored) {
            }
            resolveExecutor = null;
        }
        this.messagePipeline = Optional.empty();
        this.capabilityProbe = Optional.empty();
        this.landMessagePipeline = Optional.empty();
        this.landCommand = null;
        // GUI sessions opened through the navigator are closed one by one
        // with their exact tracked player-plus-generation identity. The
        // shared provider is never shut down here; other plugins' sessions
        // are untouched. Clearing the reference first keeps a repeated
        // disable idempotent.
        GuiNavigator navigator = this.guiNavigator;
        this.guiNavigator = null;
        if (navigator != null) {
            try {
                navigator.closeAll();
            } catch (RuntimeException ignored) {
            }
        }
        // Bedrock 表單導航追蹤同樣逐一清空：FormService 沒有遠端關閉 API，
        // 清掉追蹤即不再接受任何回應；遲到回應由 AceLib 側零執行加上
        // generation 守衛雙重丟棄。先清引用再關，保持重複 disable 冪等。
        BedrockFormNavigator bedrockForms = this.bedrockFormNavigator;
        this.bedrockFormNavigator = null;
        closeBedrockForms(bedrockForms);
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
                // The fallback resolver reads the same per-enable bypass
                // memory, and the bypass slot keeps its audit-first toggle
                // when the audit store survived, else stays fail-closed.
                LandRegistryStore store = this.protectionStore;
                ManagementGateResolver resolver =
                        store == null ? null : buildManagementGateResolver(store, adminBypassLookup());
                SelectionSessionManager selections = this.selectionSessionManager;
                Map<String, LandCommand.Handler> handlers = selections == null
                        ? LandCommand.defaultStubHandlers()
                        : buildLandHandlers(selections, null);
                Map<String, LandCommand.Handler> fallbackHandlers = new HashMap<>(handlers);
                fallbackHandlers.put("bypass", fallbackBypassHandler());
                cmd = new LandCommand(fallbackHandlers, null, resolver);
            }
            ChunkLandMessagePipeline pipeline = this.landMessagePipeline.orElse(null);
            return cmd.dispatch(sender, args, pipeline);
        }
        return false;
    }

    /**
     * Bypass toggle for the fail-closed command fallback: reuses the
     * current enable's gated handler, so the recovery gate and lifecycle
     * still apply when the cached command is gone. Without a stored
     * handler only replies unavailable. Never throws and never toggles
     * without a trail.
     */
    LandCommand.Handler fallbackBypassHandler() {
        try {
            AdminBypassCommandHandler handler = this.bypassHandler;
            if (handler != null) {
                return handler;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the fail-closed slot below.
        }
        return (sender, args, sink) -> sink.reply("command.land.bypass.failed",
                Map.of("reason", "bypass.unavailable"));
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
