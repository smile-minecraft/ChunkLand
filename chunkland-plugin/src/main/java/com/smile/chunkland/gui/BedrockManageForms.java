package com.smile.chunkland.gui;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSpec;
import com.smile.chunkland.api.permission.PermissionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Bedrock 進階管理表單的規格工廠，與 Java 管理 GUI 共用同一份
 * ManagementGuiModel 語意，只是呈現層不同。
 *
 * <p>能力對照表固定十三項 Bedrock 能力，每一項都標明在 Bedrock 上的完成路徑：
 *
 * <ul>
 *   <li>MANAGE_FORM：本里程碑有專屬表單與回應路由，確認後轉交既有 handler 槽位。</li>
 *   <li>EXISTING_FLOW：走既有的 Bedrock 流程（claim Modal 或聊天確認），
 *       選單只做入口說明，不重做第二份流程。</li>
 *   <li>CHAT_DOWNGRADE：仍走既有聊天指令，選單明確回報降級，
 *       絕不把未支援的項目宣稱成功。</li>
 * </ul>
 *
 * <p>所有規格都只呈現已確認的模型列，不會有 EVERYONE 入口；工廠本身不做任何
 * mutation，變更一律留給既有 handler 與服務。玩家名單類選單只收線上名稱，
 * 保留字一律過濾，離線目標仍走聊天指令。
 */
public final class BedrockManageForms {

    /** §67 十三項 Bedrock 能力。 */
    public enum Capability {
        CLAIM,
        EXPAND,
        SHRINK,
        TRUST_UNTRUST,
        BAN_UNBAN,
        BASIC_PERMISSION,
        SUBLAND,
        GROUP,
        PROFILE,
        LAND_RULE,
        INSPECT,
        EXPLAIN,
        DELETE
    }

    /** 能力在 Bedrock 上的完成路徑。 */
    public enum BedrockPath {
        /** 本里程碑有專屬表單與回應路由。 */
        MANAGE_FORM,
        /** 走既有的 Bedrock 流程（表單或聊天確認），本選單只做入口說明。 */
        EXISTING_FLOW,
        /** 仍走既有聊天指令，本選單明確回報降級而不宣稱表單成功。 */
        CHAT_DOWNGRADE
    }

    /** 一項能力的對照列：路徑、既有指令槽位與說明，永遠不承諾未支援的成功。 */
    public record CapabilityRoute(Capability capability, BedrockPath path,
            String handlerSlot, String note) {
        public CapabilityRoute {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(path, "path");
            if (handlerSlot == null || handlerSlot.isBlank()) {
                throw new IllegalArgumentException("handler slot must not be null or blank");
            }
            if (note == null || note.isBlank()) {
                throw new IllegalArgumentException("route note must not be null or blank");
            }
        }
    }

    private BedrockManageForms() {
    }

    /** 十三項能力的對照表，順序即主選單的按鈕順序。 */
    public static List<CapabilityRoute> routes() {
        return List.of(
                new CapabilityRoute(Capability.CLAIM, BedrockPath.EXISTING_FLOW, "claim",
                        "建立走既有的 Bedrock 確認 Modal 與共用 confirm 流程，選單只做入口說明。"),
                new CapabilityRoute(Capability.EXPAND, BedrockPath.EXISTING_FLOW, "expand",
                        "修整走既有選擇與 confirm 流程，Bedrock 可直接在聊天完成。"),
                new CapabilityRoute(Capability.SHRINK, BedrockPath.EXISTING_FLOW, "shrink",
                        "同修整，unclaim 為同一流程的別名。"),
                new CapabilityRoute(Capability.TRUST_UNTRUST, BedrockPath.MANAGE_FORM, "trust",
                        "模式與線上玩家選擇表單，確認後轉交既有 trust/untrust 槽位；離線目標仍走聊天。"),
                new CapabilityRoute(Capability.BAN_UNBAN, BedrockPath.MANAGE_FORM, "ban",
                        "同信任，確認後轉交既有 ban/unban 槽位；離線目標仍走聊天。"),
                new CapabilityRoute(Capability.BASIC_PERMISSION, BedrockPath.MANAGE_FORM, "binding",
                        "共用模型的權限明細唯讀表單；變更仍走既有 binding/default 指令。"),
                new CapabilityRoute(Capability.SUBLAND, BedrockPath.CHAT_DOWNGRADE, "subland",
                        "多步驟選擇流程，本選單明確降級，請走聊天指令。"),
                new CapabilityRoute(Capability.GROUP, BedrockPath.CHAT_DOWNGRADE, "group",
                        "群組管理步驟較多，本選單明確降級，請走聊天指令。"),
                new CapabilityRoute(Capability.PROFILE, BedrockPath.CHAT_DOWNGRADE, "profile",
                        "設定檔管理步驟較多，本選單明確降級，請走聊天指令。"),
                new CapabilityRoute(Capability.LAND_RULE, BedrockPath.CHAT_DOWNGRADE, "binding",
                        "沒有專屬規則槽位，降級為 binding/default/profile 方向，不宣稱表單成功。"),
                new CapabilityRoute(Capability.INSPECT, BedrockPath.MANAGE_FORM, "inspect",
                        "轉交既有 inspect 槽位，參數與聊天同形。"),
                new CapabilityRoute(Capability.EXPLAIN, BedrockPath.MANAGE_FORM, "explain",
                        "明細列轉交既有 explain 槽位，動作為列上已確認的效果。"),
                new CapabilityRoute(Capability.DELETE, BedrockPath.MANAGE_FORM, "delete",
                        "確認對話框後轉交既有 delete 槽位；token 第二步仍走聊天。"));
    }

    /** 主選單上某能力的按鈕文字。 */
    public static String menuLabel(Capability capability) {
        Objects.requireNonNull(capability, "capability");
        return switch (capability) {
            case CLAIM -> "Claim land";
            case EXPAND -> "Expand land";
            case SHRINK -> "Shrink land";
            case TRUST_UNTRUST -> "Trust members";
            case BAN_UNBAN -> "Ban management";
            case BASIC_PERMISSION -> "Permissions";
            case SUBLAND -> "SubLand";
            case GROUP -> "Groups";
            case PROFILE -> "Profiles";
            case LAND_RULE -> "Land rules";
            case INSPECT -> "Inspect";
            case EXPLAIN -> "Explain";
            case DELETE -> "Delete land";
        };
    }

    /** 主選單：十三項能力的 Simple 表單，按鈕順序與對照表一致。 */
    public static FormSpec.Simple rootMenu() {
        List<CapabilityRoute> routes = routes();
        FormSpec.Simple.Builder builder = FormSpec.simple("Land Management")
                .content("Choose a management step. Some steps continue in chat.");
        for (CapabilityRoute route : routes) {
            builder.button(menuLabel(route.capability()));
        }
        return builder.build();
    }

    /**
     * 已確認模型的權限明細：每列呈現 ACTION、最終效果與來源層，
     * 衝突列附三種解法；最後一顆按鈕永遠是返回。
     * 模型不可用時回傳 fail-closed 的通知頁，不洩漏任何列資料。
     */
    public static FormSpec.Simple permissionDetail(ManagementGuiModel model) {
        if (model == null || !model.available() || model.rows().isEmpty()) {
            return FormSpec.simple("Land Management (unavailable)")
                    .content(String.join("\n",
                            ManagementGuiModel.unavailable().remediesFor(null)))
                    .button("Back")
                    .build();
        }
        List<String> lines = new ArrayList<>();
        List<String> buttons = new ArrayList<>();
        int deny = 0;
        int allow = 0;
        for (ManagementPermissionRow row : model.rows()) {
            buttons.add(row.action().name());
            if (row.outcome() == PermissionState.DENY) {
                deny++;
            } else {
                allow++;
            }
            lines.add(row.action().name() + ": " + row.outcome().name()
                    + " @ " + row.layer().name()
                    + (row.conflict() ? " !CONFLICT" : ""));
            if (row.conflict()) {
                for (String remedy : model.remediesFor(row)) {
                    lines.add("  - " + remedy);
                }
            }
        }
        buttons.add("Back");
        FormSpec.Simple.Builder builder = FormSpec.simple(
                "Land Permissions (" + deny + " DENY, " + allow + " ALLOW)")
                .content(String.join("\n", lines));
        for (String button : buttons) {
            builder.button(button);
        }
        return builder.build();
    }

    /** 明細頁的返回按鈕索引，永遠是最後一顆。 */
    public static int detailBackButton(FormSpec.Simple spec) {
        Objects.requireNonNull(spec, "spec");
        return spec.buttons().size() - 1;
    }

    /** 信任模式選單：信任、取消信任、返回。 */
    public static FormSpec.Simple trustModeMenu() {
        return FormSpec.simple("Trust members")
                .content("Choose an action. Offline players: use chat instead.")
                .button("Trust player")
                .button("Untrust player")
                .button("Back")
                .build();
    }

    /** 封鎖模式選單：封鎖、解封、返回。 */
    public static FormSpec.Simple banModeMenu() {
        return FormSpec.simple("Ban management")
                .content("Choose an action. Offline players: use chat instead.")
                .button("Ban player")
                .button("Unban player")
                .button("Back")
                .build();
    }

    /**
     * 過濾後的玩家名單：去掉 null、空白與保留字；呼叫端以此把回應索引
     * 對回名單，保證按鈕與名單一致。
     */
    public static List<String> choiceNames(List<String> onlineNames) {
        if (onlineNames == null || onlineNames.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String name : onlineNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.strip();
            if (trimmed.equals("*")
                    || trimmed.toUpperCase(Locale.ROOT).equals("EVERYONE")) {
                continue;
            }
            kept.add(trimmed);
        }
        return List.copyOf(kept);
    }

    /**
     * 線上玩家選擇選單：每顆按鈕一名玩家，最後一顆返回。
     * 保留字與空白名稱一律過濾；名單為空時回傳 fail-closed 通知頁。
     */
    public static FormSpec.Simple playerChoiceMenu(List<String> onlineNames, String actionLabel) {
        if (actionLabel == null || actionLabel.isBlank()) {
            throw new IllegalArgumentException("action label must not be null or blank");
        }
        List<String> names = choiceNames(onlineNames);
        if (names.isEmpty()) {
            return FormSpec.simple(actionLabel + " - no players online")
                    .content("No online players right now. "
                            + "For offline players, use the chat command instead.")
                    .button("Back")
                    .build();
        }
        FormSpec.Simple.Builder builder = FormSpec.simple(actionLabel + " - choose player")
                .content("Choose a player for " + actionLabel
                        + ". Offline players: use the chat command instead.");
        for (String name : names) {
            builder.button(name);
        }
        builder.button("Back");
        return builder.build();
    }

    /** 確認對話框：刪除等不可逆操作的第一步，確認後仍走既有 handler。 */
    public static FormSpec.Modal confirmModal(String title, String content,
            String confirm, String cancel) {
        return FormSpec.modal(title).content(content).button1(confirm).button2(cancel).build();
    }

    /** 從 Simple 回應取出按鈕索引；非 VALID 或無按鈕時回傳 empty，永不拋出。 */
    public static Optional<Integer> clickedButton(FormResponse response) {
        try {
            if (response == null || response.status() == null) {
                return Optional.empty();
            }
            return switch (response.status()) {
                case CLOSED, INVALID -> Optional.empty();
                case VALID -> response.clickedButton();
            };
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }
}
