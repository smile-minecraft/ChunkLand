package com.smile.chunkland.command;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.gui.BedrockFormNavigator;
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

    public BedrockManageFormHandler(BedrockLookup bedrock, ManagementGateResolver gateResolver,
            ModelSource models, Map<String, LandCommand.Handler> handlers,
            BedrockFormNavigator navigator, OnlineNames onlineNames, SafeScheduler folia) {
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.gateResolver = Objects.requireNonNull(gateResolver, "gateResolver");
        this.models = Objects.requireNonNull(models, "models");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.onlineNames = Objects.requireNonNull(onlineNames, "onlineNames");
        this.folia = folia;
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
            root = BedrockManageForms.rootMenu();
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
        final Optional<ManagementGateResolver.Request> resolved;
        try {
            resolved = gateResolver.resolve(
                    player, ProtectionActionType.MANAGE_PERMISSION, args);
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

    private static boolean gateAllows(ManagementGateResolver.Request request) {
        try {
            return ManagementPermissionGate.check(
                    request.actor(),
                    request.landId(),
                    ProtectionActionType.MANAGE_PERMISSION,
                    request.snapshot(),
                    request.adminBypass(),
                    request.serverLandSteward(),
                    request.provider()).outcome() == PermissionState.ALLOW;
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
                case INSPECT -> delegate(player, sink, "inspect", new String[] {"inspect"});
                case DELETE -> pushDeleteModal(player, sink, request);
                case TRUST_UNTRUST -> pushModeMenu(player, sink, request,
                        BedrockManageForms.trustModeMenu(), "trust", "untrust");
                case BAN_UNBAN -> pushModeMenu(player, sink, request,
                        BedrockManageForms.banModeMenu(), "ban", "unban");
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
            detail = BedrockManageForms.permissionDetail(live);
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
            delegate(player, sink, "explain", new String[] {"explain", action});
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    private void pushDeleteModal(Player player, ReplySink sink,
            ManagementGateResolver.Request request) {
        FormSpec.Modal modal;
        try {
            modal = BedrockManageForms.confirmModal("Delete land",
                    "Delete land " + request.landId().value()
                            + "? This cannot be undone. Confirming runs the usual delete "
                            + "prompt; finish with /land delete confirm <token> in chat.",
                    "Delete", "Keep");
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
                delegate(player, sink, "delete", new String[] {"delete"});
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
                pushPlayerChoice(player, sink, request, firstSlot, actionLabel(firstSlot));
            } else if (index == 1) {
                pushPlayerChoice(player, sink, request, secondSlot, actionLabel(secondSlot));
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
            menu = BedrockManageForms.playerChoiceMenu(names, actionLabel);
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
            delegate(player, sink, slot, new String[] {slot, names.get(index)});
        } catch (RuntimeException failure) {
            replyDenied(sink);
        }
    }

    /**
     * 轉交既有 handler 槽位：mutation 與回報一律走原本的路，
     * 槽位缺失或拋出都 fail-closed，絕不假裝成功。
     */
    private void delegate(Player player, ReplySink sink, String slot, String[] args) {
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

    private static String actionLabel(String slot) {
        return switch (slot) {
            case "trust" -> "Trust player";
            case "untrust" -> "Untrust player";
            case "ban" -> "Ban player";
            case "unban" -> "Unban player";
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
