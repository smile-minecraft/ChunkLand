package com.smile.chunkland.command;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.chunkland.adapter.gui.ManagementRosterController;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.gui.BedrockFormNavigator;
import com.smile.chunkland.gui.BedrockFormTexts;
import com.smile.chunkland.gui.BedrockManageForms;
import com.smile.chunkland.gui.ManagementGuiModel;
import com.smile.chunkland.gui.ManagementRosterPages;
import com.smile.chunkland.protection.DirectTrustWhitelist;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.rule.LandRuleView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.entity.Player;

/**
 * {@code /land manage} 的 Bedrock 分支：以 AceLib 表單呈現進階管理流程，
 * 與 Java 管理 GUI 共用同一份不可變快照、ManagementGuiModel 語意與既有
 * gate，不另做第二份權限規則。
 *
 * <p>非 Bedrock 玩家回傳 false，讓呼叫端保留原本的 Java 路徑；
 * 其他 Bedrock 情況一律接管並回傳 true。主選單十三顆按鈕的順序與
 * {@link BedrockManageForms#routes} 一致：表單路徑開新頁或轉交既有槽位，
 * 既有流程入口只做說明而不宣稱表單成功。
 *
 * <p>表單回應永不直接碰觸玩家、快照或 mutation：一律經由 SafeScheduler
 * 派送到玩家 region 執行；過期、重複、未知的回應由導航靜默丟棄，不重複提交。
 * 變更一律轉交既有 handler 槽位並使用與聊天同形的參數，沒有 EVERYONE 入口；
 * 槽位缺失、模型遺失、送出失敗一律 fail-closed。
 *
 * <p>送出時的授權與聊天指令對等：每個轉交在呼叫 handler 之前，先檢查該槽位
 * 的 Bukkit 節點，再用該操作自己的 gate 動作（trust／untrust／ban／unban 走
 * {@code MANAGE_MEMBER}，delete 走 {@code DELETE_LAND}，inspect／explain 走
 * {@code MANAGE_PERMISSION}）對開表單時固定的領地重新裁決。重新裁決讀的是
 * 送出當下的快照與旁路旗標，但領地一律用固定的那個；玩家在開表單後走到別的
 * 領地，送出直接拒絕，絕不改判到新位置。檢查與轉交同一段 region 任務內連續
 * 執行，所以檢查通過的領地就是 handler 隨後解析到的領地。
 */
public final class BedrockManageFormHandler {

    /** 玩家種類檢查；拋出表示未知，直接 fail-closed。 */
    @FunctionalInterface
    public interface BedrockLookup {
        boolean isBedrock(UUID playerId);
    }

    /** 唯讀模型來源；回傳 null 或不可用模型都走 fail-closed 通知頁。 */
    @FunctionalInterface
    public interface ModelSource {
        ManagementGuiModel load(UUID actor, LandId landId);
    }

    /** 線上玩家名單來源；測試用假名單注入，正式接線讀線上玩家。 */
    @FunctionalInterface
    public interface OnlineNames {
        List<String> list();
    }

    /** Visible form copy for one player, resolved in that player's locale. */
    @FunctionalInterface
    public interface TextSource {
        BedrockFormTexts textsFor(Player player);
    }

    /**
     * Shared roster entry behind trust, untrust, ban and unban: the
     * management roster controller's raw-string input, which resolves UUID
     * text, online exact names and known offline names through the shared
     * roster operation and re-checks liveness, presence, land and gate at
     * write time. Production wires the controller's entry directly;
     * {@code null} keeps roster flows fail-closed without writing.
     */
    @FunctionalInterface
    public interface RosterSubmit {
        void addByReference(UUID viewer, LandId landId, ManagementRosterPages.Kind kind,
                boolean add, String raw);
    }

    /** Display rows for the rule list; empty means fail-closed without writing. */
    @FunctionalInterface
    public interface RuleViews {
        Optional<java.util.List<LandRuleView>> read(LandId landId);
    }

    /** Current land default for one action; empty means fail-closed without writing. */
    @FunctionalInterface
    public interface DefaultBaseline {
        Optional<PermissionState> current(UUID actor, LandId landId, ProtectionActionType action);
    }

    /** Write baseline for one rule; empty means fail-closed without writing. */
    @FunctionalInterface
    public interface RuleBaseline {
        Optional<LandRuleCommandHandler.Baseline> read(LandId landId, LandRuleType rule);
    }

    /** Live SubLand session tokens captured at submit time. */
    public record SublandTokens(long generation, long revision) {
    }

    /** Live SubLand session tokens; empty means no active selection. */
    @FunctionalInterface
    public interface SublandTokensSource {
        Optional<SublandTokens> read(UUID actor);
    }

    /** Whether the viewer is still online; {@code false} denies without writing. */
    @FunctionalInterface
    public interface Presence {
        boolean online(UUID viewer);
    }

    private static final String DENIED_KEY = "command.land.manage.denied";
    private static final String UNSUPPORTED_KEY = "command.land.manage.bedrock.unsupported";
    private static final String FORMS_UNAVAILABLE_KEY =
            "command.land.manage.bedrock.forms_unavailable";
    private static final String ROOT_PAGE_ID = "chunkland:bedrock-manage-root";
    private static final String DETAIL_PAGE_ID = "chunkland:bedrock-manage-detail";
    private static final String MODE_PAGE_ID = "chunkland:bedrock-manage-mode";
    private static final String CHOICE_PAGE_ID = "chunkland:bedrock-manage-choice";
    private static final String DELETE_PAGE_ID = "chunkland:bedrock-manage-delete";
    private static final String INPUT_PAGE_ID = "chunkland:bedrock-manage-input";
    private static final String RULE_LIST_PAGE_ID = "chunkland:bedrock-manage-rule-list";
    private static final String RULE_CONFIRM_PAGE_ID = "chunkland:bedrock-manage-rule-confirm";
    private static final String PERMISSION_CONFIRM_PAGE_ID =
            "chunkland:bedrock-manage-permission-confirm";
    private static final String SUBLAND_PAGE_ID = "chunkland:bedrock-manage-subland";
    private static final String GROUP_PAGE_ID = "chunkland:bedrock-manage-group";
    private static final String PROFILE_PAGE_ID = "chunkland:bedrock-manage-profile";
    private static final String MEMBER_PAGE_ID = "chunkland:bedrock-manage-member";
    private static final String SET_PAGE_ID = "chunkland:bedrock-manage-set";
    private static final String DELETE_CHOICE_PAGE_ID = "chunkland:bedrock-manage-delete-choice";
    private static final String NOTICE_PAGE_ID = "chunkland:bedrock-manage-notice";

    private final BedrockLookup bedrock;
    private final ManagementGateResolver gateResolver;
    private final ModelSource models;
    private final Map<String, LandCommand.Handler> handlers;
    private final BedrockFormNavigator navigator;
    private final OnlineNames onlineNames;
    private final SafeScheduler folia;
    private final TextSource texts;
    private final RosterSubmit roster;
    private final RuleViews ruleViews;
    private final DefaultBaseline defaultBaseline;
    private final RuleBaseline ruleBaseline;
    private final SublandTokensSource sublandTokens;
    private final Presence presence;
    private final BooleanSupplier alive;

    public BedrockManageFormHandler(BedrockLookup bedrock, ManagementGateResolver gateResolver,
            ModelSource models, Map<String, LandCommand.Handler> handlers,
            BedrockFormNavigator navigator, OnlineNames onlineNames, SafeScheduler folia) {
        this(bedrock, gateResolver, models, handlers, navigator, onlineNames, folia, null);
    }

    /**
     * @param texts visible form copy per player (their own locale);
     *              {@code null} keeps the bundled English wording
     */
    public BedrockManageFormHandler(BedrockLookup bedrock, ManagementGateResolver gateResolver,
            ModelSource models, Map<String, LandCommand.Handler> handlers,
            BedrockFormNavigator navigator, OnlineNames onlineNames, SafeScheduler folia,
            TextSource texts) {
        this(bedrock, gateResolver, models, handlers, navigator, onlineNames, folia, texts,
                (RosterSubmit) null, null, null, null, null, null, null);
    }

    /**
     * Full wiring: every management write re-enters the same handler slot the
     * chat command would, and roster writes go through the management roster
     * controller's raw-string entry (the shared roster operation with offline
     * resolution). Any {@code null} seam fails its own flow closed without
     * touching mutations; {@code null} presence or liveness denies every
     * submit.
     *
     * @param roster shared roster entry behind trust and ban;
     *               {@code null} keeps roster flows read-only fail-closed
     * @param ruleViews display rows for the rule list;
     *                  {@code null} or empty keeps the rule list fail-closed
     * @param defaultBaseline current land default for the permission confirm;
     *                        {@code null} or empty keeps it fail-closed
     * @param ruleBaseline write baseline for the rule confirm;
     *                     {@code null} or empty keeps it fail-closed
     * @param sublandTokens live SubLand session tokens read at submit time;
     *                      {@code null} or empty keeps SubLand writes fail-closed
     * @param presence viewer-online re-check at submit time;
     *                 {@code null} denies every submit
     * @param alive enable-generation liveness at submit time;
     *              {@code null} denies every submit
     */
    public BedrockManageFormHandler(BedrockLookup bedrock, ManagementGateResolver gateResolver,
            ModelSource models, Map<String, LandCommand.Handler> handlers,
            BedrockFormNavigator navigator, OnlineNames onlineNames, SafeScheduler folia,
            TextSource texts, ManagementRosterController roster, RuleViews ruleViews,
            DefaultBaseline defaultBaseline, RuleBaseline ruleBaseline,
            SublandTokensSource sublandTokens, Presence presence, BooleanSupplier alive) {
        this(bedrock, gateResolver, models, handlers, navigator, onlineNames, folia, texts,
                roster == null ? null : roster::addByReference, ruleViews, defaultBaseline,
                ruleBaseline, sublandTokens, presence, alive);
    }

    /**
     * Same wiring with the roster entry as a seam, so tests observe the
     * shared-operation call without standing up the full controller.
     */
    public BedrockManageFormHandler(BedrockLookup bedrock, ManagementGateResolver gateResolver,
            ModelSource models, Map<String, LandCommand.Handler> handlers,
            BedrockFormNavigator navigator, OnlineNames onlineNames, SafeScheduler folia,
            TextSource texts, RosterSubmit roster, RuleViews ruleViews,
            DefaultBaseline defaultBaseline, RuleBaseline ruleBaseline,
            SublandTokensSource sublandTokens, Presence presence, BooleanSupplier alive) {
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.gateResolver = Objects.requireNonNull(gateResolver, "gateResolver");
        this.models = Objects.requireNonNull(models, "models");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.onlineNames = Objects.requireNonNull(onlineNames, "onlineNames");
        this.folia = folia;
        this.texts = texts;
        this.roster = roster;
        this.ruleViews = ruleViews;
        this.defaultBaseline = defaultBaseline;
        this.ruleBaseline = ruleBaseline;
        this.sublandTokens = sublandTokens;
        this.presence = presence;
        this.alive = alive;
    }

    /**
     * Visible form copy for one player. A {@code null} source, a
     * {@code null} answer or a failing lookup falls back to the bundled
     * English wording, so a form always opens.
     */
    private BedrockFormTexts textsFor(Player player) {
        TextSource source = this.texts;
        if (source == null) {
            return BedrockFormTexts.english();
        }
        try {
            BedrockFormTexts resolved = source.textsFor(player);
            return resolved == null ? BedrockFormTexts.english() : resolved;
        } catch (RuntimeException unresolved) {
            return BedrockFormTexts.english();
        }
    }

    /** Display name of the pinned land, or its id when the snapshot cannot name it. */
    private static String landLabel(ManagementGateResolver.Request request) {
        try {
            var land = request.snapshot().land(request.landId());
            if (land != null && land.displayName() != null && !land.displayName().isBlank()) {
                return land.displayName();
            }
        } catch (RuntimeException unresolved) {
            // Fall through to the id.
        }
        return String.valueOf(request.landId().value());
    }

    /**
     * 導航追蹤，disable 清理用；平時不碰。
     */
    public com.smile.chunkland.gui.BedrockFormNavigator navigator() {
        return navigator;
    }

    /**
     * 執行 Bedrock 分支。
     *
     * @return true 表示 Bedrock 路徑已接管（含 fail-closed 回報）；
     *         false 表示非 Bedrock 玩家，呼叫端保留 Java 路徑
     */
    public boolean handle(Player player, String[] args, ReplySink sink) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(sink, "sink");
        UUID actor = actorOf(player, sink);
        if (actor == null) {
            return true;
        }
        boolean bedrockPlayer;
        try {
            bedrockPlayer = bedrock.isBedrock(actor);
        } catch (RuntimeException failure) {
            replyFormsUnavailable(sink, "lookup.failed", "manage");
            return true;
        }
        if (!bedrockPlayer) {
            return false;
        }
        if (folia == null || !navigator.isAvailable()) {
            replyFormsUnavailable(sink, "forms.unavailable", "manage");
            return true;
        }
        ManagementGateResolver.Request request = resolve(player, args, sink);
        if (request == null) {
            return true;
        }
        if (!gateAllows(request)) {
            replyDenied(sink);
            return true;
        }
        BedrockFormNavigator.Dispatcher dispatch = task -> folia.runForPlayer(player, task);
        BedrockFormNavigator.FailureReply failure = (key, vars) -> sink.reply(key, vars);
        FormSpec.Simple root;
        try {
            root = BedrockManageForms.rootMenu(textsFor(player));
        } catch (RuntimeException failureToBuild) {
            replyFormsUnavailable(sink, "send.failed", "manage");
            return true;
        }
        Optional<Long> opened;
        try {
            opened = navigator.open(actor,
                    new BedrockFormNavigator.FormPage(ROOT_PAGE_ID, root),
                    response -> onRootResponse(player, sink, request, response),
                    dispatch, failure);
        } catch (RuntimeException openFailure) {
            replyFormsUnavailable(sink, "send.failed", "manage");
            return true;
        }
        if (opened == null || opened.isEmpty()) {
            replyFormsUnavailable(sink, "send.failed", "manage");
        }
        return true;
    }

    private UUID actorOf(Player player, ReplySink sink) {
        try {
            UUID actor = player.getUniqueId();
            if (actor == null) {
                replyDenied(sink);
                return null;
            }
            return actor;
        } catch (RuntimeException failure) {
            replyDenied(sink);
            return null;
        }
    }

    private ManagementGateResolver.Request resolve(Player player, String[] args, ReplySink sink) {
        return resolveGate(player, ProtectionActionType.MANAGE_PERMISSION, args, sink);
    }

    private static boolean gateAllows(ManagementGateResolver.Request request) {
        return gateAllows(request.actor(), request.landId(),
                ProtectionActionType.MANAGE_PERMISSION, request);
    }

    private static boolean gateAllows(UUID actor, LandId landId,
            ProtectionActionType action, ManagementGateResolver.Request inputs) {
        try {
            return ManagementPermissionGate.check(
                    actor,
                    landId,
                    action,
                    inputs.snapshot(),
                    inputs.adminBypass(),
                    inputs.serverLandSteward(),
                    inputs.provider()).outcome() == PermissionState.ALLOW;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    /**
     * 主選單回應，只在玩家 region 執行。按鈕索引對照 routes 順序；
     * 無效索引、缺失槽位一律 fail-closed，絕不觸碰 mutation。
     */
    private void onRootResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            List<BedrockManageForms.CapabilityRoute> routes = BedrockManageForms.routes();
            if (button.isEmpty() || button.get() == null
                    || button.get() < 0 || button.get() >= routes.size()) {
                replyDenied(sink);
                return;
            }
            BedrockManageForms.CapabilityRoute route = routes.get(button.get());
            if (route == null || route.path() == null) {
                replyDenied(sink);
                return;
            }
            switch (route.path()) {
                case MANAGE_FORM -> openManageForm(player, sink, request, route);
                case EXISTING_FLOW -> replyUnsupported(sink, route.handlerSlot());
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void openManageForm(Player player, ReplySink sink,
            ManagementGateResolver.Request request,
            BedrockManageForms.CapabilityRoute route) {
        try {
            switch (route.capability()) {
                case BASIC_PERMISSION -> pushDetail(player, sink, request, true);
                case EXPLAIN -> pushDetail(player, sink, request, false);
                case INSPECT -> delegate(player, sink, request, "inspect", new String[] {"inspect"});
                case DELETE -> pushDeleteModal(player, sink, request);
                case TRUST_UNTRUST -> pushModeMenu(player, sink, request,
                        BedrockManageForms.trustModeMenu(textsFor(player)), "trust", "untrust");
                case BAN_UNBAN -> pushModeMenu(player, sink, request,
                        BedrockManageForms.banModeMenu(textsFor(player)), "ban", "unban");
                case SUBLAND -> pushSublandMenu(player, sink, request);
                case GROUP -> pushGroupMenu(player, sink, request);
                case PROFILE -> pushProfileMenu(player, sink, request);
                case LAND_RULE -> pushRuleList(player, sink, request, 0);
                default -> replyUnsupported(sink, route.handlerSlot());
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    /** 權限明細：點擊當下重讀模型，新資料直接呈現，不沿用舊快照。 */
    private void pushDetail(Player player, ReplySink sink, ManagementGateResolver.Request request,
            boolean writable) {
        ManagementGuiModel live = loadModel(request);
        FormSpec.Simple detail;
        try {
            detail = BedrockManageForms.permissionDetail(live, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyDenied(sink);
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(DETAIL_PAGE_ID, detail),
                    response -> onDetailResponse(player, sink, request, response, writable),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "binding");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "binding");
        }
    }

    /**
     * 明細列：唯讀入口轉交既有 explain 槽位；可寫入口開啟共用切換確認，
     * 取消回到明細，絕不在這裡直接寫入。
     */
    private void onDetailResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response, boolean writable) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            ManagementGuiModel model = loadModel(request);
            int rows = model == null ? 0 : model.rows().size();
            if (button.isEmpty() || button.get() == null || button.get() < 0) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            if (index == rows) {
                backQuietly(request.actor());
                return;
            }
            if (index < 0 || index > rows) {
                replyDenied(sink);
                return;
            }
            ProtectionActionType action = model.rows().get(index).action();
            if (!writable) {
                delegate(player, sink, request, "explain",
                        new String[] {"explain", action.name()});
                return;
            }
            if (!DirectTrustWhitelist.isAllowed(action)) {
                pushError(player, sink, request, "default", "default.unknown_action");
                return;
            }
            PermissionState current = defaultCurrent(request, action);
            if (current == null) {
                pushError(player, sink, request, "default", "default.failed");
                return;
            }
            PermissionState target =
                    com.smile.chunkland.gui.ManagementGuiToggle.targetFor(current);
            pushPermissionConfirm(player, sink, request, action.name(), current, target);
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void pushPermissionConfirm(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String action,
            PermissionState current, PermissionState target) {
        FormSpec.Modal modal;
        try {
            modal = BedrockManageForms.permissionConfirmModal(
                    action, current, target, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "default");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(PERMISSION_CONFIRM_PAGE_ID, modal),
                    response -> onPermissionConfirmResponse(
                            player, sink, request, action, target, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "default");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "default");
        }
    }

    /** 明細列確認：第一顆轉交既有 default 槽位，第二顆回到明細。 */
    private void onPermissionConfirmResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String action, PermissionState target,
            FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            if (button.get() == 0) {
                delegate(player, sink, request, "default",
                        new String[] {"default", action, target.name()});
                return;
            }
            if (button.get() == 1) {
                backQuietly(request.actor());
                return;
            }
            replyDenied(sink);
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    // ------------------------------------------------------------------
    // 環境規則：十一條清單加三態確認，走 rule 槽位
    // ------------------------------------------------------------------

    private void pushRuleList(Player player, ReplySink sink,
            ManagementGateResolver.Request request, int page) {
        List<LandRuleView> rows = ruleRows(request);
        FormSpec.Simple menu;
        try {
            BedrockFormTexts copy = textsFor(player);
            menu = rows == null
                    ? BedrockManageForms.ruleListMenu(null, 0, copy)
                    : BedrockManageForms.ruleListMenu(rows, page, copy);
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "rule");
            return;
        }
        final List<LandRuleView> kept = rows == null ? List.of() : List.copyOf(rows);
        final int safePage = rows == null ? 0 : page;
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(RULE_LIST_PAGE_ID, menu),
                    response -> onRuleListResponse(player, sink, request, response, kept,
                            safePage),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "rule");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "rule");
        }
    }

    /** 規則清單：列開啟三態確認，翻頁換頁，返回回到主選單。 */
    private void onRuleListResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response,
            List<LandRuleView> rows, int page) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            if (rows.isEmpty()) {
                backQuietly(request.actor());
                return;
            }
            int size = BedrockManageForms.rulePageSize();
            int pages = BedrockManageForms.ruleListPageCount(rows.size());
            int safe = Math.min(Math.max(page, 0), pages - 1);
            int from = safe * size;
            int count = Math.min(size, rows.size() - from);
            boolean hasPrev = safe > 0;
            boolean hasNext = safe < pages - 1;
            int backIndex = count + (hasPrev ? 1 : 0) + (hasNext ? 1 : 0);
            if (index == backIndex) {
                backQuietly(request.actor());
                return;
            }
            int cursor = count;
            if (hasPrev) {
                if (index == cursor) {
                    replaceRuleList(player, sink, request, safe - 1);
                    return;
                }
                cursor++;
            }
            if (hasNext && index == cursor) {
                replaceRuleList(player, sink, request, safe + 1);
                return;
            }
            if (index < 0 || index >= count) {
                replyDenied(sink);
                return;
            }
            pushRuleConfirm(player, sink, request, rows.get(from + index));
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void replaceRuleList(Player player, ReplySink sink,
            ManagementGateResolver.Request request, int page) {
        List<LandRuleView> rows = ruleRows(request);
        FormSpec.Simple menu;
        try {
            menu = rows == null
                    ? BedrockManageForms.ruleListMenu(null, 0, textsFor(player))
                    : BedrockManageForms.ruleListMenu(rows, page, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "rule");
            return;
        }
        final List<LandRuleView> kept = rows == null ? List.of() : List.copyOf(rows);
        final int safePage = rows == null ? 0 : page;
        try {
            Optional<Long> replaced = navigator.replace(request.actor(),
                    new BedrockFormNavigator.FormPage(RULE_LIST_PAGE_ID, menu),
                    response -> onRuleListResponse(player, sink, request, response, kept,
                            safePage),
                    dispatchFor(player), failureFor(sink));
            if (replaced == null || replaced.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "rule");
            }
        } catch (RuntimeException replaceFailure) {
            replyFormsUnavailable(sink, "send.failed", "rule");
        }
    }

    private void pushRuleConfirm(Player player, ReplySink sink,
            ManagementGateResolver.Request request, LandRuleView row) {
        if (row == null || row.rule() == null) {
            pushError(player, sink, request, "rule", "rule.unknown_rule");
            return;
        }
        LandRuleCommandHandler.Baseline baseline = ruleBaseline(request.landId(), row.rule());
        PermissionState explicit = baseline == null ? null : baseline.current();
        if (explicit == null) {
            pushError(player, sink, request, "rule", "rule.failed");
            return;
        }
        FormSpec.Simple menu;
        try {
            menu = BedrockManageForms.ruleConfirmMenu(row.rule(), explicit,
                    row.effective(), row.source() == null ? null : row.source().name(),
                    textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "rule");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(RULE_CONFIRM_PAGE_ID, menu),
                    response -> onRuleConfirmResponse(player, sink, request, row.rule(),
                            response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "rule");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "rule");
        }
    }

    /** 規則三態確認：三顆寫入按鈕轉交 rule 槽位，取消回到清單。 */
    private void onRuleConfirmResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, LandRuleType rule, FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            if (index == BedrockManageForms.RULE_ALLOW_BUTTON) {
                delegate(player, sink, request, "rule",
                        new String[] {"rule", rule.name(), PermissionState.ALLOW.name()});
            } else if (index == BedrockManageForms.RULE_DENY_BUTTON) {
                delegate(player, sink, request, "rule",
                        new String[] {"rule", rule.name(), PermissionState.DENY.name()});
            } else if (index == BedrockManageForms.RULE_INHERIT_BUTTON) {
                delegate(player, sink, request, "rule",
                        new String[] {"rule", rule.name(), PermissionState.INHERIT.name()});
            } else if (index == BedrockManageForms.RULE_CANCEL_BUTTON) {
                backQuietly(request.actor());
            } else {
                replyDenied(sink);
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    // ------------------------------------------------------------------
    // 子領地：操作選單加名稱輸入，送出時讀取即時選區序號
    // ------------------------------------------------------------------

    private void pushSublandMenu(Player player, ReplySink sink,
            ManagementGateResolver.Request request) {
        FormSpec.Simple menu;
        try {
            menu = BedrockManageForms.sublandMenu(textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "subland");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(SUBLAND_PAGE_ID, menu),
                    response -> onSublandResponse(player, sink, request, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "subland");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "subland");
        }
    }

    private void onSublandResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            int backIndex = BedrockManageForms.sublandMenu(textsFor(player)).buttons().size() - 1;
            if (index == backIndex) {
                backQuietly(request.actor());
                return;
            }
            if (index == BedrockManageForms.SUBLAND_SELECT) {
                pushSublandNameInput(player, sink, request, "select", false);
            } else if (index == BedrockManageForms.SUBLAND_CREATE) {
                if (sublandTokens(request.actor()) == null) {
                    pushSublandEmpty(player, sink, request);
                    return;
                }
                pushSublandNameInput(player, sink, request, "create", true);
            } else if (index == BedrockManageForms.SUBLAND_UPDATE) {
                if (sublandTokens(request.actor()) == null) {
                    pushSublandEmpty(player, sink, request);
                    return;
                }
                pushSublandNameInput(player, sink, request, "update", true);
            } else if (index == BedrockManageForms.SUBLAND_DELETE) {
                if (sublandTokens(request.actor()) == null) {
                    pushSublandEmpty(player, sink, request);
                    return;
                }
                pushSublandDeleteConfirm(player, sink, request);
            } else if (index == BedrockManageForms.SUBLAND_EXTEND) {
                SublandTokens tokens = sublandTokens(request.actor());
                if (tokens == null) {
                    pushSublandEmpty(player, sink, request);
                    return;
                }
                delegate(player, sink, request, "subland", new String[] {"subland", "extend",
                        String.valueOf(tokens.generation()), String.valueOf(tokens.revision())});
            } else {
                replyDenied(sink);
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void pushSublandEmpty(Player player, ReplySink sink,
            ManagementGateResolver.Request request) {
        BedrockFormTexts copy = textsFor(player);
        pushNotice(player, sink, request, "subland",
                copy.text(BedrockFormTexts.SUBLAND_EMPTY_TITLE),
                copy.text(BedrockFormTexts.SUBLAND_EMPTY_CONTENT));
    }

    private void pushSublandNameInput(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String op, boolean required) {
        BedrockFormTexts copy = textsFor(player);
        FormSpec.Custom menu;
        try {
            menu = BedrockManageForms.nameInputMenu(
                    copy.text(BedrockFormTexts.SUBLAND_TITLE), copy);
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "subland");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(INPUT_PAGE_ID, menu),
                    response -> onSublandNameResponse(player, sink, request, op, required,
                            response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "subland");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "subland");
        }
    }

    private void onSublandNameResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String op, boolean required,
            FormResponse response) {
        Optional<String> name = BedrockManageForms.readNameInput(response);
        if (name.isEmpty()) {
            if ("select".equals(op)) {
                delegate(player, sink, request, "subland", new String[] {"subland", "select"});
                return;
            }
            if (required) {
                pushError(player, sink, request, "subland", "name.blank");
                return;
            }
            replyDenied(sink);
            return;
        }
        if ("select".equals(op)) {
            delegate(player, sink, request, "subland",
                    new String[] {"subland", "select", name.get()});
            return;
        }
        SublandTokens tokens = sublandTokens(request.actor());
        if (tokens == null) {
            pushSublandEmpty(player, sink, request);
            return;
        }
        delegate(player, sink, request, "subland", new String[] {"subland", op,
                String.valueOf(tokens.generation()), String.valueOf(tokens.revision()),
                name.get()});
    }

    private void pushSublandDeleteConfirm(Player player, ReplySink sink,
            ManagementGateResolver.Request request) {
        BedrockFormTexts copy = textsFor(player);
        FormSpec.Modal modal;
        try {
            modal = BedrockManageForms.confirmModal(
                    copy.text(BedrockFormTexts.SUBLAND_DELETE),
                    copy.text(BedrockFormTexts.SUBLAND_CONTENT),
                    copy.text(BedrockFormTexts.CONFIRM_OK),
                    copy.text(BedrockFormTexts.CONFIRM_CANCEL));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "subland");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(DELETE_PAGE_ID, modal),
                    response -> onSublandDeleteConfirmResponse(player, sink, request, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "subland");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "subland");
        }
    }

    /** 子領地刪除確認：第一顆轉交 subland 槽位，第二顆回到選單。 */
    private void onSublandDeleteConfirmResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            if (button.get() == 0) {
                SublandTokens tokens = sublandTokens(request.actor());
                if (tokens == null) {
                    pushSublandEmpty(player, sink, request);
                    return;
                }
                delegate(player, sink, request, "subland", new String[] {"subland", "delete",
                        String.valueOf(tokens.generation()), String.valueOf(tokens.revision())});
                return;
            }
            backQuietly(request.actor());
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    // ------------------------------------------------------------------
    // 群組：操作選單加輸入表單，走 group 槽位
    // ------------------------------------------------------------------

    private void pushGroupMenu(Player player, ReplySink sink,
            ManagementGateResolver.Request request) {
        FormSpec.Simple menu;
        try {
            menu = BedrockManageForms.groupMenu(textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "group");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(GROUP_PAGE_ID, menu),
                    response -> onGroupResponse(player, sink, request, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "group");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "group");
        }
    }

    private void onGroupResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            BedrockFormTexts copy = textsFor(player);
            int backIndex = BedrockManageForms.groupMenu(copy).buttons().size() - 1;
            if (index == backIndex) {
                backQuietly(request.actor());
                return;
            }
            if (index == BedrockManageForms.GROUP_CREATE) {
                pushNameInput(player, sink, request, "group",
                        copy.text(BedrockFormTexts.GROUP_CREATE), copy,
                        name -> delegateUngated(player, sink, request, "group",
                                new String[] {"group", "create", name}));
            } else if (index == BedrockManageForms.GROUP_LIST) {
                delegateUngated(player, sink, request, "group", new String[] {"group", "list"});
            } else if (index == BedrockManageForms.GROUP_ADD) {
                pushGroupMemberInput(player, sink, request, "add", copy);
            } else if (index == BedrockManageForms.GROUP_REMOVE) {
                pushGroupMemberInput(player, sink, request, "remove", copy);
            } else if (index == BedrockManageForms.GROUP_DELETE) {
                pushNameInput(player, sink, request, "group",
                        copy.text(BedrockFormTexts.GROUP_DELETE), copy,
                        name -> pushDeleteChoice(player, sink, request, "group", name));
            } else {
                replyDenied(sink);
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    /** 群組成員輸入：群組與玩家兩個文字，送出轉交 group 槽位。 */
    private void pushGroupMemberInput(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String op, BedrockFormTexts copy) {
        FormSpec.Custom menu;
        try {
            String actionLabel = "add".equals(op)
                    ? copy.text(BedrockFormTexts.GROUP_ADD)
                    : copy.text(BedrockFormTexts.GROUP_REMOVE);
            menu = BedrockManageForms.groupMemberForm(actionLabel, copy);
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "group");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(MEMBER_PAGE_ID, menu),
                    response -> {
                        Optional<BedrockManageForms.GroupMemberInput> parsed =
                                BedrockManageForms.readGroupMember(response);
                        if (parsed.isEmpty()) {
                            pushError(player, sink, request, "group", "name.blank");
                            return;
                        }
                        delegateUngated(player, sink, request, "group", new String[] {"group",
                                op, parsed.get().groupRef(), parsed.get().playerName()});
                    },
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "group");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "group");
        }
    }

    // ------------------------------------------------------------------
    // 設定檔：操作選單加輸入與下拉表單，走 profile 槽位
    // ------------------------------------------------------------------

    private void pushProfileMenu(Player player, ReplySink sink,
            ManagementGateResolver.Request request) {
        FormSpec.Simple menu;
        try {
            menu = BedrockManageForms.profileMenu(textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "profile");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(PROFILE_PAGE_ID, menu),
                    response -> onProfileResponse(player, sink, request, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "profile");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "profile");
        }
    }

    private void onProfileResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            BedrockFormTexts copy = textsFor(player);
            int backIndex = BedrockManageForms.profileMenu(copy).buttons().size() - 1;
            if (index == backIndex) {
                backQuietly(request.actor());
                return;
            }
            if (index == BedrockManageForms.PROFILE_CREATE) {
                pushNameInput(player, sink, request, "profile",
                        copy.text(BedrockFormTexts.PROFILE_CREATE), copy,
                        name -> delegateUngated(player, sink, request, "profile",
                                new String[] {"profile", "create", name}));
            } else if (index == BedrockManageForms.PROFILE_LIST) {
                delegateUngated(player, sink, request, "profile",
                        new String[] {"profile", "list"});
            } else if (index == BedrockManageForms.PROFILE_SET) {
                pushProfileSetInput(player, sink, request, copy);
            } else if (index == BedrockManageForms.PROFILE_DELETE) {
                pushNameInput(player, sink, request, "profile",
                        copy.text(BedrockFormTexts.PROFILE_DELETE), copy,
                        name -> pushDeleteChoice(player, sink, request, "profile", name));
            } else {
                replyDenied(sink);
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    /** 設定檔設值輸入：設定檔文字加權限與值下拉，送出轉交 profile 槽位。 */
    private void pushProfileSetInput(Player player, ReplySink sink,
            ManagementGateResolver.Request request, BedrockFormTexts copy) {
        FormSpec.Custom menu;
        try {
            menu = BedrockManageForms.profileSetForm(copy);
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "profile");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(SET_PAGE_ID, menu),
                    response -> {
                        Optional<BedrockManageForms.ProfileSetInput> parsed =
                                BedrockManageForms.readProfileSet(response);
                        if (parsed.isEmpty()) {
                            pushError(player, sink, request, "profile", "name.blank");
                            return;
                        }
                        List<String> actions = BedrockManageForms.profileSetActions();
                        List<String> states = BedrockManageForms.profileSetStates();
                        int actionIndex = parsed.get().actionIndex();
                        int stateIndex = parsed.get().stateIndex();
                        if (actionIndex < 0 || actionIndex >= actions.size()
                                || stateIndex < 0 || stateIndex >= states.size()) {
                            pushError(player, sink, request, "profile", "name.blank");
                            return;
                        }
                        delegateUngated(player, sink, request, "profile", new String[] {"profile",
                                "set", parsed.get().profileRef(), actions.get(actionIndex),
                                states.get(stateIndex)});
                    },
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "profile");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "profile");
        }
    }

    // ------------------------------------------------------------------
    // 共用小表單：名稱輸入與刪除二選一
    // ------------------------------------------------------------------

    /** 共用名稱輸入的送出動作；空字串不會走到這裡。 */
    @FunctionalInterface
    private interface NameSubmit {
        void submit(String name);
    }

    private void pushNameInput(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot, String actionLabel,
            BedrockFormTexts copy, NameSubmit submit) {
        FormSpec.Custom menu;
        try {
            menu = BedrockManageForms.nameInputMenu(actionLabel, copy);
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(INPUT_PAGE_ID, menu),
                    response -> {
                        Optional<String> name = BedrockManageForms.readNameInput(response);
                        if (name.isEmpty()) {
                            pushError(player, sink, request, slot, "name.blank");
                            return;
                        }
                        try {
                            submit.submit(name.get());
                        } catch (RuntimeException failure) {
                            replyDenied(sink);
                        }
                    },
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", slot);
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
        }
    }

    /** 刪除二選一：一般刪除、強制刪除、保留（等同返回）。 */
    private void pushDeleteChoice(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot, String ref) {
        FormSpec.Simple menu;
        try {
            menu = BedrockManageForms.deleteChoiceMenu(ref, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(DELETE_CHOICE_PAGE_ID, menu),
                    response -> {
                        Optional<Integer> button =
                                BedrockManageForms.clickedButton(response);
                        if (button.isEmpty() || button.get() == null) {
                            replyDenied(sink);
                            return;
                        }
                        int index = button.get();
                        if (index == BedrockManageForms.DELETE_NOW_BUTTON) {
                            delegateUngated(player, sink, request, slot,
                                    new String[] {slot, "delete", ref});
                        } else if (index == BedrockManageForms.DELETE_FORCE_BUTTON) {
                            delegateUngated(player, sink, request, slot,
                                    new String[] {slot, "delete", ref, "--force"});
                        } else if (index == BedrockManageForms.DELETE_CANCEL_BUTTON) {
                            backQuietly(request.actor());
                        } else {
                            replyDenied(sink);
                        }
                    },
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", slot);
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
        }
    }

    private void pushDeleteModal(Player player, ReplySink sink,
            ManagementGateResolver.Request request) {
        FormSpec.Modal modal;
        try {
            BedrockFormTexts copy = textsFor(player);
            modal = BedrockManageForms.confirmModal(
                    copy.text(BedrockFormTexts.DELETE_TITLE),
                    copy.text(BedrockFormTexts.DELETE_CONTENT,
                            Map.of("land_name", landLabel(request))),
                    copy.text(BedrockFormTexts.DELETE_CONFIRM),
                    copy.text(BedrockFormTexts.DELETE_CANCEL));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", "delete");
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(DELETE_PAGE_ID, modal),
                    response -> onDeleteModalResponse(player, sink, request, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", "delete");
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", "delete");
        }
    }

    /** 確認轉交既有 delete 槽位；取消回到主選單，絕不提交。 */
    private void onDeleteModalResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            if (button.get() == 0) {
                delegate(player, sink, request, "delete", new String[] {"delete"});
                return;
            }
            backQuietly(request.actor());
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void pushModeMenu(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormSpec.Simple menu,
            String firstSlot, String secondSlot) {
        int backIndex = menu.buttons().size() - 1;
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(MODE_PAGE_ID, menu),
                    response -> onModeResponse(player, sink, request, response,
                            firstSlot, secondSlot, backIndex),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", firstSlot);
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", firstSlot);
        }
    }

    private void onModeResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response,
            String firstSlot, String secondSlot, int backIndex) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            if (index == 0) {
                pushPlayerChoice(player, sink, request, firstSlot,
                        actionLabel(firstSlot, textsFor(player)));
            } else if (index == 1) {
                pushPlayerChoice(player, sink, request, secondSlot,
                        actionLabel(secondSlot, textsFor(player)));
            } else if (index == backIndex) {
                backQuietly(request.actor());
            } else {
                replyDenied(sink);
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void pushPlayerChoice(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot, String actionLabel) {
        final List<String> names;
        try {
            names = BedrockManageForms.choiceNames(onlineNames.list());
        } catch (RuntimeException listFailure) {
            replyDenied(sink);
            return;
        }
        ManagementRosterPages.Kind kind = rosterKindOf(slot);
        boolean add = rosterAddOf(slot);
        if (kind == null) {
            replyDenied(sink);
            return;
        }
        FormSpec.Simple menu;
        try {
            menu = BedrockManageForms.playerChoiceMenuWithInput(
                    names, actionLabel, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyDenied(sink);
            return;
        }
        final List<String> kept = List.copyOf(names);
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(CHOICE_PAGE_ID, menu),
                    response -> onPlayerChoiceResponse(player, sink, request, response,
                            slot, kept, kind, add, actionLabel),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyDenied(sink);
            }
        } catch (RuntimeException pushFailure) {
            replyDenied(sink);
        }
    }

    /**
     * 玩家選擇：線上名轉交名單共用操作，輸入按鈕開啟自由名稱表單，
     * 返回回到模式選單。參數形狀與名單控制器一致，解析留給共用操作。
     */
    private void onPlayerChoiceResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response,
            String slot, List<String> names, ManagementRosterPages.Kind kind, boolean add,
            String actionLabel) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            int inputIndex = BedrockManageForms.choiceInputIndex(names);
            int backIndex = inputIndex + 1;
            if (index == backIndex) {
                backQuietly(request.actor());
                return;
            }
            if (index == inputIndex) {
                pushRosterInput(player, sink, request, slot, kind, add, actionLabel);
                return;
            }
            if (index < 0 || index >= names.size()) {
                replyDenied(sink);
                return;
            }
            submitRoster(player, sink, request, kind, add, names.get(index));
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void pushRosterInput(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot,
            ManagementRosterPages.Kind kind, boolean add, String actionLabel) {
        FormSpec.Custom menu;
        try {
            menu = BedrockManageForms.nameInputMenu(actionLabel, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(INPUT_PAGE_ID, menu),
                    response -> onRosterInputResponse(
                            player, sink, request, slot, kind, add, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", slot);
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
        }
    }

    /** 自由名稱送出：空白回錯誤頁，有效文字轉交名單共用操作。 */
    private void onRosterInputResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot,
            ManagementRosterPages.Kind kind, boolean add, FormResponse response) {
        // 關閉與無效回應由導航直接丟棄，不會走到這裡；這裡只處理 VALID。
        Optional<String> raw = BedrockManageForms.readNameInput(response);
        if (raw.isEmpty()) {
            pushError(player, sink, request, slot, "name.blank");
            return;
        }
        submitRoster(player, sink, request, kind, add, raw.get());
    }

    /**
     * 名單送出：先做與聊天派遣對等的送出時重新確認（節點、固定領地、
     * 操作 gate、在線、存活世代），再轉交名單控制器的原始字串入口。
     * 解析與寫入都在共用操作裡，未知名稱拒絕且不寫入，結果經由
     * 控制器的回報回到聊天。
     */
    private void submitRoster(Player player, ReplySink sink,
            ManagementGateResolver.Request pinned, ManagementRosterPages.Kind kind, boolean add,
            String raw) {
        String slot = kind == ManagementRosterPages.Kind.MEMBERS
                ? (add ? "trust" : "untrust") : (add ? "ban" : "unban");
        ManagementGateResolver.Request fresh =
                checkSubmit(player, sink, pinned, slot, new String[] {slot, raw});
        if (fresh == null) {
            return;
        }
        RosterSubmit rosterSubmit = this.roster;
        if (rosterSubmit == null) {
            replyDenied(sink);
            return;
        }
        try {
            rosterSubmit.addByReference(
                    pinned.actor(), pinned.landId(), kind, add, raw);
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    /** 名單槽位對應的名單種類；未知槽位回傳 null，呼叫端 fail-closed。 */
    private static ManagementRosterPages.Kind rosterKindOf(String slot) {
        if (slot == null) {
            return null;
        }
        return switch (slot.toLowerCase(java.util.Locale.ROOT)) {
            case "trust", "untrust" -> ManagementRosterPages.Kind.MEMBERS;
            case "ban", "unban" -> ManagementRosterPages.Kind.BANS;
            default -> null;
        };
    }

    /** 名單槽位是加入還是移除；未知槽位預設移除，呼叫端已先擋下。 */
    private static boolean rosterAddOf(String slot) {
        if (slot == null) {
            return false;
        }
        return switch (slot.toLowerCase(java.util.Locale.ROOT)) {
            case "trust", "ban" -> true;
            default -> false;
        };
    }

    /**
     * 轉交既有 handler 槽位：先做與聊天派遣對等的授權，再走原本的路。
     *
     * <p>授權保證與 {@link LandCommand} 的聊天派遣站在同一個 gate 上：槽位
     * 的 Bukkit 節點、在線、存活世代、送出當下重解的 gate 輸入、固定領地
     * 上的操作 gate 與固定領地仍然存在，六者全過才呼叫 handler。mutation
     * 與回報一律走原本的路，槽位缺失或拋出都 fail-closed，絕不假裝成功。
     *
     * <p>授權邊界：成員有沒有資格動一塊玩家領地，由這個送出時點的 gate 裁決
     * 保證；持久化執行緒看不見旁路記憶、 steward 旗標與設定預設值，所以它只
     * 保證命名空間不變條件（玩家命名空間的寫入永不碰 Server 領地、擁有者與
     * Server 領地永不被 ban），由
     * {@code ServerLandAuthorisationIsolationTest} 釘住。兩層缺一不可。
     */
    private void delegate(Player player, ReplySink sink,
            ManagementGateResolver.Request pinned, String slot, String[] args) {
        ManagementGateResolver.Request fresh = checkSubmit(player, sink, pinned, slot, args);
        if (fresh == null) {
            return;
        }
        final LandCommand.Handler target;
        try {
            target = handlers.get(slot);
        } catch (RuntimeException lookupFailure) {
            replyDenied(sink);
            return;
        }
        if (target == null) {
            replyDenied(sink);
            return;
        }
        try {
            target.handle(player, args, sink);
        } catch (RuntimeException handlerFailure) {
            replyDenied(sink);
        }
    }

    /**
     * 送出時重新確認：節點、在線、存活世代、送出當下重解 gate、固定 actor
     * 與領地比對、操作 gate、固定領地仍然存在。群組與設定檔沒有領地 gate
     *（與聊天路徑一致：在擁有者命名空間內運作），只做節點、actor、
     * 在線與存活世代四項。任一不符回明確拒絕，不寫入。
     *
     * @return 通過重新確認的新請求；不符時已回報並回傳 null
     */
    private ManagementGateResolver.Request checkSubmit(Player player, ReplySink sink,
            ManagementGateResolver.Request pinned, String slot, String[] args) {
        ProtectionActionType action;
        try {
            action = ManagementPermissionGate.actionForSubcommand(slot).orElse(null);
        } catch (RuntimeException mappingFailure) {
            replyDenied(sink);
            return null;
        }
        if (!nodeAllows(player, slot) || !actorMatches(player, pinned) || !present(pinned.actor())
                || !live()) {
            replyDenied(sink);
            return null;
        }
        if (action == null) {
            return pinned;
        }
        ManagementGateResolver.Request fresh = resolveGate(player, action, args, sink);
        if (fresh == null) {
            return null;
        }
        if (!fresh.actor().equals(pinned.actor())
                || !fresh.landId().equals(pinned.landId())) {
            replyDenied(sink);
            return null;
        }
        if (!gateAllows(fresh.actor(), pinned.landId(), action, fresh)) {
            replyDenied(sink);
            return null;
        }
        try {
            if (fresh.snapshot() == null || fresh.snapshot().land(pinned.landId()) == null) {
                replyDenied(sink);
                return null;
            }
        } catch (RuntimeException unresolved) {
            replyDenied(sink);
            return null;
        }
        return fresh;
    }

    /**
     * 無領地 gate 的槽位轉交（群組、設定檔）：只做節點、actor、在線與
     * 存活世代四項，與聊天路徑的授權一致。mutation 與回報走原本的路。
     */
    private void delegateUngated(Player player, ReplySink sink,
            ManagementGateResolver.Request pinned, String slot, String[] args) {
        if (!nodeAllows(player, slot) || !actorMatches(player, pinned) || !present(pinned.actor())
                || !live()) {
            replyDenied(sink);
            return;
        }
        final LandCommand.Handler target;
        try {
            target = handlers.get(slot);
        } catch (RuntimeException lookupFailure) {
            replyDenied(sink);
            return;
        }
        if (target == null) {
            replyDenied(sink);
            return;
        }
        try {
            target.handle(player, args, sink);
        } catch (RuntimeException handlerFailure) {
            replyDenied(sink);
        }
    }

    private static boolean actorMatches(Player player,
            ManagementGateResolver.Request pinned) {
        try {
            return player != null && pinned != null && pinned.actor() != null
                    && pinned.actor().equals(player.getUniqueId());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean present(UUID viewer) {
        Presence online = this.presence;
        if (online == null || viewer == null) {
            return false;
        }
        try {
            return online.online(viewer);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean live() {
        BooleanSupplier generation = this.alive;
        if (generation == null) {
            return false;
        }
        try {
            return generation.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private PermissionState defaultCurrent(ManagementGateResolver.Request request,
            ProtectionActionType action) {
        DefaultBaseline baseline = this.defaultBaseline;
        if (baseline == null || request == null || action == null) {
            return null;
        }
        try {
            return baseline.current(request.actor(), request.landId(), action).orElse(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private List<LandRuleView> ruleRows(ManagementGateResolver.Request request) {
        RuleViews views = this.ruleViews;
        if (views == null || request == null || request.landId() == null) {
            return null;
        }
        final Optional<List<LandRuleView>> read;
        try {
            read = views.read(request.landId());
        } catch (RuntimeException failure) {
            return null;
        }
        if (read == null || read.isEmpty() || read.get() == null) {
            return null;
        }
        List<LandRuleView> kept = new ArrayList<>();
        for (LandRuleView row : read.get()) {
            if (row != null && row.rule() != null) {
                kept.add(row);
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    private LandRuleCommandHandler.Baseline ruleBaseline(LandId landId, LandRuleType rule) {
        RuleBaseline baseline = this.ruleBaseline;
        if (baseline == null || landId == null || rule == null) {
            return null;
        }
        try {
            return baseline.read(landId, rule).orElse(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private SublandTokens sublandTokens(UUID actor) {
        SublandTokensSource source = this.sublandTokens;
        if (source == null || actor == null) {
            return null;
        }
        try {
            return source.read(actor).orElse(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** 通用錯誤頁：說明原因，只剩返回，不靜默失敗。 */
    private void pushError(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot, String reason) {
        FormSpec.Simple page;
        try {
            page = BedrockManageForms.errorPage(reason, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
            return;
        }
        pushNoticePage(player, sink, request, slot, page);
    }

    /** 通用通知頁：無需選擇的空狀態，只剩返回。 */
    private void pushNotice(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot, String title, String content) {
        FormSpec.Simple page;
        try {
            page = BedrockManageForms.noticePage(title, content, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
            return;
        }
        pushNoticePage(player, sink, request, slot, page);
    }

    private void pushNoticePage(Player player, ReplySink sink,
            ManagementGateResolver.Request request, String slot, FormSpec.Simple page) {
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(NOTICE_PAGE_ID, page),
                    response -> backQuietly(request.actor()),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyFormsUnavailable(sink, "send.failed", slot);
            }
        } catch (RuntimeException pushFailure) {
            replyFormsUnavailable(sink, "send.failed", slot);
        }
    }

    private ManagementGateResolver.Request resolveGate(Player player,
            ProtectionActionType action, String[] args, ReplySink sink) {
        final Optional<ManagementGateResolver.Request> resolved;
        try {
            resolved = gateResolver.resolve(player, action, args);
        } catch (RuntimeException unresolved) {
            replyDenied(sink);
            return null;
        }
        if (resolved == null || resolved.isEmpty() || resolved.get() == null) {
            replyDenied(sink);
            return null;
        }
        return resolved.get();
    }

    private static boolean nodeAllows(Player player, String slot) {
        final String node;
        try {
            node = LandPermissions.forSubcommand(slot);
        } catch (RuntimeException lookupFailure) {
            return false;
        }
        if (node == null || node.isBlank()) {
            return false;
        }
        try {
            return player.hasPermission(node);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private ManagementGuiModel loadModel(ManagementGateResolver.Request request) {
        try {
            return models.load(request.actor(), request.landId());
        } catch (RuntimeException loadFailure) {
            return null;
        }
    }

    private void backQuietly(UUID actor) {
        try {
            navigator.back(actor);
        } catch (RuntimeException ignored) {
            // 返回失敗保持現頁；導航本身已經 fail-closed。
        }
    }

    private BedrockFormNavigator.Dispatcher dispatchFor(Player player) {
        return task -> folia.runForPlayer(player, task);
    }

    private static BedrockFormNavigator.FailureReply failureFor(ReplySink sink) {
        return sink::reply;
    }

    private static String actionLabel(String slot, BedrockFormTexts copy) {
        return switch (slot) {
            case "trust" -> copy.text(BedrockFormTexts.TRUST_ADD);
            case "untrust" -> copy.text(BedrockFormTexts.TRUST_REMOVE);
            case "ban" -> copy.text(BedrockFormTexts.BAN_ADD);
            case "unban" -> copy.text(BedrockFormTexts.BAN_REMOVE);
            default -> slot;
        };
    }

    private static void replyDenied(ReplySink sink) {
        failQuietly(() -> sink.reply(DENIED_KEY, Map.of()));
    }

    /**
     * 表單不可用的備援：只在 Floodgate 或表單服務不可用、送出失敗時使用，
     * 訊息說明原因並指出聊天指令備援，不用籠統的降級說法。
     */
    private static void replyFormsUnavailable(ReplySink sink, String reason, String subcommand) {
        String safeReason = reason == null || reason.isBlank() ? "forms.unavailable" : reason;
        String safeSubcommand = subcommand == null || subcommand.isBlank() ? "manage"
                : subcommand;
        failQuietly(() -> sink.reply(FORMS_UNAVAILABLE_KEY,
                Map.of("reason", safeReason, "subcommand", safeSubcommand)));
    }

    private static void replyUnsupported(ReplySink sink, String slot) {
        String subcommand = slot == null || slot.isBlank() ? "manage" : slot;
        failQuietly(() -> sink.reply(UNSUPPORTED_KEY, Map.of("subcommand", subcommand)));
    }

    private static void failQuietly(Runnable reply) {
        try {
            reply.run();
        } catch (RuntimeException ignored) {
            // 終端 fail-closed：壞掉的回報出口不可逃出表單流程。
        }
    }
}
