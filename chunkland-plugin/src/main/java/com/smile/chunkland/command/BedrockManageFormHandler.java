package com.smile.chunkland.command;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.gui.BedrockFormNavigator;
import com.smile.chunkland.gui.BedrockFormTexts;
import com.smile.chunkland.gui.BedrockManageForms;
import com.smile.chunkland.gui.ManagementGuiModel;
import com.smile.chunkland.protection.ManagementPermissionGate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * {@code /land manage} 的 Bedrock 分支：以 AceLib 表單呈現進階管理流程，
 * 與 Java 管理 GUI 共用同一份不可變快照、ManagementGuiModel 語意與既有
 * gate，不另做第二份權限規則。
 *
 * <p>非 Bedrock 玩家回傳 false，讓呼叫端保留原本的 Java 路徑；
 * 其他 Bedrock 情況一律接管並回傳 true。主選單十三顆按鈕的順序與
 * {@link BedrockManageForms#routes} 一致：表單路徑開新頁或轉交既有槽位，
 * 既有流程與聊天降級明確回報而不宣稱表單成功。
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

    private static final String DENIED_KEY = "command.land.manage.denied";
    private static final String UNSUPPORTED_KEY = "command.land.manage.bedrock.unsupported";
    private static final String ROOT_PAGE_ID = "chunkland:bedrock-manage-root";
    private static final String DETAIL_PAGE_ID = "chunkland:bedrock-manage-detail";
    private static final String MODE_PAGE_ID = "chunkland:bedrock-manage-mode";
    private static final String CHOICE_PAGE_ID = "chunkland:bedrock-manage-choice";
    private static final String DELETE_PAGE_ID = "chunkland:bedrock-manage-delete";

    private final BedrockLookup bedrock;
    private final ManagementGateResolver gateResolver;
    private final ModelSource models;
    private final Map<String, LandCommand.Handler> handlers;
    private final BedrockFormNavigator navigator;
    private final OnlineNames onlineNames;
    private final SafeScheduler folia;
    private final TextSource texts;

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
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.gateResolver = Objects.requireNonNull(gateResolver, "gateResolver");
        this.models = Objects.requireNonNull(models, "models");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.onlineNames = Objects.requireNonNull(onlineNames, "onlineNames");
        this.folia = folia;
        this.texts = texts;
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
            replyDenied(sink);
            return true;
        }
        if (!bedrockPlayer) {
            return false;
        }
        if (folia == null || !navigator.isAvailable()) {
            replyDenied(sink);
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
            replyDenied(sink);
            return true;
        }
        Optional<Long> opened;
        try {
            opened = navigator.open(actor,
                    new BedrockFormNavigator.FormPage(ROOT_PAGE_ID, root),
                    response -> onRootResponse(player, sink, request, response),
                    dispatch, failure);
        } catch (RuntimeException openFailure) {
            replyDenied(sink);
            return true;
        }
        if (opened == null || opened.isEmpty()) {
            replyDenied(sink);
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
                case EXISTING_FLOW, CHAT_DOWNGRADE ->
                        replyUnsupported(sink, route.handlerSlot());
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
                case BASIC_PERMISSION, EXPLAIN -> pushDetail(player, sink, request);
                case INSPECT -> delegate(player, sink, request, "inspect", new String[] {"inspect"});
                case DELETE -> pushDeleteModal(player, sink, request);
                case TRUST_UNTRUST -> pushModeMenu(player, sink, request,
                        BedrockManageForms.trustModeMenu(textsFor(player)), "trust", "untrust");
                case BAN_UNBAN -> pushModeMenu(player, sink, request,
                        BedrockManageForms.banModeMenu(textsFor(player)), "ban", "unban");
                default -> replyUnsupported(sink, route.handlerSlot());
            }
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    /** 權限明細：點擊當下重讀模型，新資料直接呈現，不沿用舊快照。 */
    private void pushDetail(Player player, ReplySink sink, ManagementGateResolver.Request request) {
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
                    response -> onDetailResponse(player, sink, request, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyDenied(sink);
            }
        } catch (RuntimeException pushFailure) {
            replyDenied(sink);
        }
    }

    /** 明細列轉交既有 explain 槽位，動作為列上已確認的效果；返回回到主選單。 */
    private void onDetailResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response) {
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
            String action = model.rows().get(index).action().name();
            delegate(player, sink, request, "explain", new String[] {"explain", action});
        } catch (RuntimeException failure) {
            replyDenied(sink);
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
            replyDenied(sink);
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(DELETE_PAGE_ID, modal),
                    response -> onDeleteModalResponse(player, sink, request, response),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyDenied(sink);
            }
        } catch (RuntimeException pushFailure) {
            replyDenied(sink);
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
                replyDenied(sink);
            }
        } catch (RuntimeException pushFailure) {
            replyDenied(sink);
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
        FormSpec.Simple menu;
        try {
            menu = BedrockManageForms.playerChoiceMenu(names, actionLabel, textsFor(player));
        } catch (RuntimeException buildFailure) {
            replyDenied(sink);
            return;
        }
        try {
            Optional<Long> pushed = navigator.push(request.actor(),
                    new BedrockFormNavigator.FormPage(CHOICE_PAGE_ID, menu),
                    response -> onPlayerChoiceResponse(player, sink, request, response, slot, names),
                    dispatchFor(player), failureFor(sink));
            if (pushed == null || pushed.isEmpty()) {
                replyDenied(sink);
            }
        } catch (RuntimeException pushFailure) {
            replyDenied(sink);
        }
    }

    /** 玩家選擇轉交既有槽位，參數與聊天同形；返回回到模式選單。 */
    private void onPlayerChoiceResponse(Player player, ReplySink sink,
            ManagementGateResolver.Request request, FormResponse response,
            String slot, List<String> names) {
        try {
            Optional<Integer> button = BedrockManageForms.clickedButton(response);
            if (button.isEmpty() || button.get() == null) {
                replyDenied(sink);
                return;
            }
            int index = button.get();
            if (index == names.size()) {
                backQuietly(request.actor());
                return;
            }
            if (index < 0 || index >= names.size()) {
                replyDenied(sink);
                return;
            }
            delegate(player, sink, request, slot, new String[] {slot, names.get(index)});
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    /**
     * 轉交既有 handler 槽位：先做與聊天派遣對等的授權，再走原本的路。
     *
     * <p>授權保證與 {@link LandCommand} 的聊天派遣站在同一個 gate 上：槽位
     * 的 Bukkit 節點、送出當下重解的 gate 輸入、固定領地上的操作 gate，三者
     * 全過才呼叫 handler。mutation 與回報一律走原本的路，槽位缺失或拋出都
     * fail-closed，絕不假裝成功。
     *
     * <p>授權邊界：成員有沒有資格動一塊玩家領地，由這個送出時點的 gate 裁決
     * 保證；持久化執行緒看不見旁路記憶、 steward 旗標與設定預設值，所以它只
     * 保證命名空間不變條件（玩家命名空間的寫入永不碰 Server 領地、擁有者與
     * Server 領地永不被 ban），由
     * {@code ServerLandAuthorisationIsolationTest} 釘住。兩層缺一不可。
     */
    private void delegate(Player player, ReplySink sink,
            ManagementGateResolver.Request pinned, String slot, String[] args) {
        ProtectionActionType action;
        try {
            action = ManagementPermissionGate.actionForSubcommand(slot).orElse(null);
        } catch (RuntimeException mappingFailure) {
            replyDenied(sink);
            return;
        }
        if (action == null || !nodeAllows(player, slot)) {
            replyDenied(sink);
            return;
        }
        ManagementGateResolver.Request fresh = resolveGate(player, action, args, sink);
        if (fresh == null) {
            return;
        }
        if (!fresh.actor().equals(pinned.actor())
                || !fresh.landId().equals(pinned.landId())) {
            replyDenied(sink);
            return;
        }
        if (!gateAllows(fresh.actor(), pinned.landId(), action, fresh)) {
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
