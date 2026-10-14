package com.smile.chunkland.gui;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.rule.LandRuleView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * </ul>
 *
 * <p>所有能力都有真正的表單路徑：信任、封鎖（含離線名稱輸入）、基本權限、
 * 子領地、群組、權限設定檔與十一條環境規則各自轉交與 Java 版相同的既有
 * handler 槽位。只剩圈地、擴張、縮減三項沿用既有的 Bedrock 流程入口。
 *
 * <p>所有規格都只呈現已確認的模型列，不會有 EVERYONE 入口；工廠本身不做任何
 * mutation，變更一律留給既有 handler 與服務。玩家名單類選單收線上名稱，
 * 另附自由輸入給已知的離線名稱；保留字一律過濾。
 */
public final class BedrockManageForms {

    /** 十三項 Bedrock 管理能力。 */
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
        EXISTING_FLOW
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
                        "模式、線上玩家選擇與自由名稱輸入表單，確認後轉交名單共用操作；"
                                + "已知離線名稱可解析，未知名稱拒絕且不寫入。"),
                new CapabilityRoute(Capability.BAN_UNBAN, BedrockPath.MANAGE_FORM, "ban",
                        "同信任，確認後轉交名單共用操作；已知離線名稱可解析。"),
                new CapabilityRoute(Capability.BASIC_PERMISSION, BedrockPath.MANAGE_FORM, "binding",
                        "共用模型的權限明細表單；列確認後轉交既有 default 槽位，"
                                + "參數與 Java 管理 GUI 的確認頁同形。"),
                new CapabilityRoute(Capability.SUBLAND, BedrockPath.MANAGE_FORM, "subland",
                        "操作選單加名稱輸入表單，送出時讀取即時選區序號，"
                                + "轉交既有 subland 槽位；刪除有確認框。"),
                new CapabilityRoute(Capability.GROUP, BedrockPath.MANAGE_FORM, "group",
                        "操作選單加名稱輸入表單，轉交既有 group 槽位；"
                                + "刪除有刪除與強制刪除的確認。"),
                new CapabilityRoute(Capability.PROFILE, BedrockPath.MANAGE_FORM, "profile",
                        "操作選單加名稱輸入與下拉表單，轉交既有 profile 槽位；"
                                + "刪除有刪除與強制刪除的確認。"),
                new CapabilityRoute(Capability.LAND_RULE, BedrockPath.MANAGE_FORM, "rule",
                        "十一條規則清單加三態確認表單，轉交既有 rule 槽位，"
                                + "參數與聊天指令同形。"),
                new CapabilityRoute(Capability.INSPECT, BedrockPath.MANAGE_FORM, "inspect",
                        "轉交既有 inspect 槽位，參數與聊天同形。"),
                new CapabilityRoute(Capability.EXPLAIN, BedrockPath.MANAGE_FORM, "explain",
                        "明細列轉交既有 explain 槽位，動作為列上已確認的效果。"),
                new CapabilityRoute(Capability.DELETE, BedrockPath.MANAGE_FORM, "delete",
                        "確認對話框後轉交既有 delete 槽位；token 第二步仍走聊天。"));
    }

    /** 主選單上某能力的按鈕文字（內建英文）。 */
    public static String menuLabel(Capability capability) {
        return menuLabel(capability, BedrockFormTexts.english());
    }

    /** 主選單上某能力的按鈕文字，文字由呼叫端注入。 */
    public static String menuLabel(Capability capability, BedrockFormTexts texts) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(texts, "texts");
        return texts.text(BedrockFormTexts.MENU_PREFIX
                + capability.name().toLowerCase(Locale.ROOT));
    }

    /** 主選單：十三項能力的 Simple 表單，按鈕順序與對照表一致（內建英文）。 */
    public static FormSpec.Simple rootMenu() {
        return rootMenu(BedrockFormTexts.english());
    }

    /** 主選單，文字由呼叫端注入；按鈕順序與對照表一致。 */
    public static FormSpec.Simple rootMenu(BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        List<CapabilityRoute> routes = routes();
        FormSpec.Simple.Builder builder = FormSpec.simple(texts.text(BedrockFormTexts.ROOT_TITLE))
                .content(texts.text(BedrockFormTexts.ROOT_CONTENT));
        for (CapabilityRoute route : routes) {
            builder.button(menuLabel(route.capability(), texts));
        }
        return builder.build();
    }

    /**
     * 已確認模型的權限明細：每列呈現 ACTION、最終效果與來源層，
     * 衝突列附三種解法；最後一顆按鈕永遠是返回。
     * 模型不可用時回傳 fail-closed 的通知頁，不洩漏任何列資料。
     */
    public static FormSpec.Simple permissionDetail(ManagementGuiModel model) {
        return permissionDetail(model, BedrockFormTexts.english());
    }

    /**
     * 權限明細，文字由呼叫端注入：列標題、衝突標記與解法都走與 Java 管理
     * GUI 相同的語言鍵，按鈕顯示該權限的顯示名稱。列順序與按鈕順序不變，
     * 呼叫端仍以索引對回模型列。
     */
    public static FormSpec.Simple permissionDetail(ManagementGuiModel model,
            BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        String back = texts.text(BedrockFormTexts.BACK);
        if (model == null || !model.available() || model.rows().isEmpty()) {
            return FormSpec.simple(texts.text(BedrockFormTexts.UNAVAILABLE_TITLE))
                    .content(texts.text(BedrockFormTexts.UNAVAILABLE_CONTENT))
                    .button(back)
                    .build();
        }
        List<String> lines = new ArrayList<>();
        List<String> buttons = new ArrayList<>();
        int deny = 0;
        int allow = 0;
        for (ManagementPermissionRow row : model.rows()) {
            String action = row.action().name();
            buttons.add(texts.text(BedrockFormTexts.ACTION_PREFIX
                    + action.toLowerCase(Locale.ROOT)));
            if (row.outcome() == PermissionState.DENY) {
                deny++;
            } else {
                allow++;
            }
            String layer = row.layer().name();
            String outcome = row.outcome().name();
            String suffix = row.conflict()
                    ? texts.text(BedrockFormTexts.ROW_CONFLICT_SUFFIX) : "";
            lines.add(texts.text(BedrockFormTexts.ROW_HEAD, Map.of(
                    "action", action, "outcome", outcome, "layer", layer,
                    "conflict", suffix)));
            if (row.conflict()) {
                String remedies = texts.text(BedrockFormTexts.REMEDY_CONFLICT,
                        Map.of("layer", layer, "outcome", outcome));
                for (String remedy : remedies.split("\n")) {
                    if (!remedy.isBlank()) {
                        lines.add(texts.text(BedrockFormTexts.ROW_REMEDY_LINE,
                                Map.of("remedy", remedy)));
                    }
                }
            }
        }
        buttons.add(back);
        FormSpec.Simple.Builder builder = FormSpec.simple(
                texts.text(BedrockFormTexts.DETAILS_TITLE,
                        Map.of("deny_count", deny, "allow_count", allow)))
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
        return trustModeMenu(BedrockFormTexts.english());
    }

    /** 信任模式選單，文字由呼叫端注入。 */
    public static FormSpec.Simple trustModeMenu(BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        return FormSpec.simple(texts.text(BedrockFormTexts.TRUST_TITLE))
                .content(texts.text(BedrockFormTexts.TRUST_CONTENT))
                .button(texts.text(BedrockFormTexts.TRUST_ADD))
                .button(texts.text(BedrockFormTexts.TRUST_REMOVE))
                .button(texts.text(BedrockFormTexts.BACK))
                .build();
    }

    /** 封鎖模式選單：封鎖、解封、返回。 */
    public static FormSpec.Simple banModeMenu() {
        return banModeMenu(BedrockFormTexts.english());
    }

    /** 封鎖模式選單，文字由呼叫端注入。 */
    public static FormSpec.Simple banModeMenu(BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        return FormSpec.simple(texts.text(BedrockFormTexts.BAN_TITLE))
                .content(texts.text(BedrockFormTexts.BAN_CONTENT))
                .button(texts.text(BedrockFormTexts.BAN_ADD))
                .button(texts.text(BedrockFormTexts.BAN_REMOVE))
                .button(texts.text(BedrockFormTexts.BACK))
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
        return playerChoiceMenu(onlineNames, actionLabel, BedrockFormTexts.english());
    }

    /** 線上玩家選擇選單，文字由呼叫端注入；過濾與 fail-closed 規則不變。 */
    public static FormSpec.Simple playerChoiceMenu(List<String> onlineNames, String actionLabel,
            BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        if (actionLabel == null || actionLabel.isBlank()) {
            throw new IllegalArgumentException("action label must not be null or blank");
        }
        Map<String, Object> vars = Map.of("value", actionLabel);
        String back = texts.text(BedrockFormTexts.BACK);
        List<String> names = choiceNames(onlineNames);
        if (names.isEmpty()) {
            return FormSpec.simple(texts.text(BedrockFormTexts.CHOICE_EMPTY_TITLE, vars))
                    .content(texts.text(BedrockFormTexts.CHOICE_EMPTY_CONTENT, vars))
                    .button(back)
                    .build();
        }
        FormSpec.Simple.Builder builder =
                FormSpec.simple(texts.text(BedrockFormTexts.CHOICE_TITLE, vars))
                        .content(texts.text(BedrockFormTexts.CHOICE_CONTENT, vars));
        for (String name : names) {
            builder.button(name);
        }
        builder.button(back);
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

    /** 規則清單每頁列數；十一條規則固定兩頁，不寫死測試以外的頁面。 */
    public static int rulePageSize() {
        return 8;
    }

    /** 規則清單總頁數，至少一頁；空清單回傳零，呼叫端呈現 fail-closed 頁。 */
    public static int ruleListPageCount(int rowCount) {
        if (rowCount <= 0) {
            return 0;
        }
        return (rowCount + rulePageSize() - 1) / rulePageSize();
    }

    /** 子領地操作選單的按鈕位置，返回永遠是最後一顆。 */
    public static final int SUBLAND_SELECT = 0;
    public static final int SUBLAND_CREATE = 1;
    public static final int SUBLAND_UPDATE = 2;
    public static final int SUBLAND_DELETE = 3;
    public static final int SUBLAND_EXTEND = 4;

    /** 群組操作選單的按鈕位置，返回永遠是最後一顆。 */
    public static final int GROUP_CREATE = 0;
    public static final int GROUP_LIST = 1;
    public static final int GROUP_ADD = 2;
    public static final int GROUP_REMOVE = 3;
    public static final int GROUP_DELETE = 4;

    /** 設定檔操作選單的按鈕位置，返回永遠是最後一顆。 */
    public static final int PROFILE_CREATE = 0;
    public static final int PROFILE_LIST = 1;
    public static final int PROFILE_SET = 2;
    public static final int PROFILE_DELETE = 3;

    /** 規則三態確認的按鈕位置，取消永遠是最後一顆（等同返回）。 */
    public static final int RULE_ALLOW_BUTTON = 0;
    public static final int RULE_DENY_BUTTON = 1;
    public static final int RULE_INHERIT_BUTTON = 2;
    public static final int RULE_CANCEL_BUTTON = 3;

    /** 刪除二選一確認的按鈕位置，保留永遠是最後一顆（等同返回）。 */
    public static final int DELETE_NOW_BUTTON = 0;
    public static final int DELETE_FORCE_BUTTON = 1;
    public static final int DELETE_CANCEL_BUTTON = 2;

    /**
     * 自由名稱輸入：單一文字輸入的 Custom 表單，給已知的離線名稱用。
     * 工廠只收字串，解析與保留字判斷一律留給共用操作。
     */
    public static FormSpec.Custom nameInputMenu(String actionLabel) {
        return nameInputMenu(actionLabel, BedrockFormTexts.english());
    }

    /** 自由名稱輸入，文字由呼叫端注入。 */
    public static FormSpec.Custom nameInputMenu(String actionLabel, BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        if (actionLabel == null || actionLabel.isBlank()) {
            throw new IllegalArgumentException("action label must not be null or blank");
        }
        Map<String, Object> vars = Map.of("action", actionLabel);
        return FormSpec.custom(texts.text(BedrockFormTexts.NAME_INPUT_TITLE, vars))
                .input(texts.text(BedrockFormTexts.NAME_INPUT_CONTENT, vars),
                        texts.text(BedrockFormTexts.NAME_INPUT_PLACEHOLDER, vars), "")
                .build();
    }

    /**
     * 讀出名稱輸入的第一個文字值；空白、缺失、非文字或非 VALID 回應
     * 一律回傳 empty，呼叫端呈現錯誤頁而不寫入。
     */
    public static Optional<String> readNameInput(FormResponse response) {
        try {
            if (response == null || response.status() != FormResponseStatus.VALID
                    || response.values() == null || response.values().isEmpty()) {
                return Optional.empty();
            }
            FormValue first = response.values().get(0);
            if (!(first instanceof FormValue.Text text) || text.value() == null
                    || text.value().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(text.value().strip());
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /**
     * 線上玩家選擇選單加名稱輸入入口：線上名之後多一顆輸入按鈕，
     * 最後一顆永遠是返回。名單為空時只剩輸入與返回，不再 fail-closed，
     * 因為離線名稱本來就不需要線上名單。
     */
    public static FormSpec.Simple playerChoiceMenuWithInput(List<String> onlineNames,
            String actionLabel, BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        if (actionLabel == null || actionLabel.isBlank()) {
            throw new IllegalArgumentException("action label must not be null or blank");
        }
        Map<String, Object> vars = Map.of("value", actionLabel);
        String back = texts.text(BedrockFormTexts.BACK);
        String input = texts.text(BedrockFormTexts.CHOICE_INPUT, vars);
        List<String> names = choiceNames(onlineNames);
        FormSpec.Simple.Builder builder;
        if (names.isEmpty()) {
            builder = FormSpec.simple(texts.text(BedrockFormTexts.CHOICE_EMPTY_TITLE, vars))
                    .content(texts.text(BedrockFormTexts.CHOICE_EMPTY_CONTENT, vars));
        } else {
            builder = FormSpec.simple(texts.text(BedrockFormTexts.CHOICE_TITLE, vars))
                    .content(texts.text(BedrockFormTexts.CHOICE_CONTENT, vars));
            for (String name : names) {
                builder.button(name);
            }
        }
        builder.button(input);
        builder.button(back);
        return builder.build();
    }

    /** 名單頁的名稱輸入按鈕索引：過濾後的線上名單長度。 */
    public static int choiceInputIndex(List<String> keptNames) {
        return keptNames == null ? 0 : keptNames.size();
    }

    /**
     * 規則清單：每頁最多八列，之後是上一頁、下一頁（只在需要時出現），
     * 最後一顆永遠是返回。空模型回傳 fail-closed 通知頁，不洩漏任何值。
     */
    public static FormSpec.Simple ruleListMenu(List<LandRuleView> rows, int page) {
        return ruleListMenu(rows, page, BedrockFormTexts.english());
    }

    /** 規則清單，文字由呼叫端注入；分頁與 fail-closed 規則不變。 */
    public static FormSpec.Simple ruleListMenu(List<LandRuleView> rows, int page,
            BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        String back = texts.text(BedrockFormTexts.BACK);
        if (rows == null || rows.isEmpty()) {
            return FormSpec.simple(texts.text(BedrockFormTexts.UNAVAILABLE_TITLE))
                    .content(texts.text(BedrockFormTexts.UNAVAILABLE_CONTENT))
                    .button(back)
                    .build();
        }
        List<LandRuleView> kept = new ArrayList<>();
        for (LandRuleView row : rows) {
            if (row != null && row.rule() != null) {
                kept.add(row);
            }
        }
        if (kept.isEmpty()) {
            return FormSpec.simple(texts.text(BedrockFormTexts.UNAVAILABLE_TITLE))
                    .content(texts.text(BedrockFormTexts.UNAVAILABLE_CONTENT))
                    .button(back)
                    .build();
        }
        int pages = ruleListPageCount(kept.size());
        int safe = Math.min(Math.max(page, 0), pages - 1);
        int size = rulePageSize();
        int from = safe * size;
        int to = Math.min(from + size, kept.size());
        Map<String, Object> vars = Map.of("page", safe + 1, "pages", pages);
        FormSpec.Simple.Builder builder = FormSpec.simple(
                texts.text(BedrockFormTexts.RULE_LIST_TITLE, vars))
                .content(texts.text(BedrockFormTexts.RULE_LIST_CONTENT, vars));
        for (int index = from; index < to; index++) {
            LandRuleView row = kept.get(index);
            String explicit = row.explicit() == null
                    ? PermissionState.INHERIT.name() : row.explicit().name();
            String effective = row.effective() == null
                    ? PermissionState.INHERIT.name() : row.effective().name();
            String source = row.source() == null
                    ? LandRuleView.Source.UNAVAILABLE.name() : row.source().name();
            builder.button(row.rule().name() + ": " + explicit
                    + " (effective " + effective + " from " + source + ")");
        }
        if (safe > 0) {
            builder.button(texts.text(BedrockFormTexts.PAGER_PREV, vars));
        }
        if (safe < pages - 1) {
            builder.button(texts.text(BedrockFormTexts.PAGER_NEXT, vars));
        }
        builder.button(back);
        return builder.build();
    }

    /**
     * 規則三態確認：ALLOW、DENY、INHERIT 三顆寫入按鈕，最後一顆取消
     * （等同返回，絕不寫入）。呼叫端以索引對回固定位置。
     */
    public static FormSpec.Simple ruleConfirmMenu(LandRuleType rule,
            PermissionState explicit, PermissionState effective, String source,
            BedrockFormTexts texts) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(texts, "texts");
        String shownExplicit = explicit == null ? PermissionState.INHERIT.name() : explicit.name();
        String shownEffective = effective == null
                ? PermissionState.INHERIT.name() : effective.name();
        String shownSource = source == null || source.isBlank()
                ? LandRuleView.Source.UNAVAILABLE.name() : source;
        Map<String, Object> vars = Map.of("rule", rule.name(), "explicit", shownExplicit,
                "effective", shownEffective, "source", shownSource);
        return FormSpec.simple(texts.text(BedrockFormTexts.RULE_CONFIRM_TITLE, vars))
                .content(texts.text(BedrockFormTexts.RULE_CONFIRM_CONTENT, vars))
                .button(texts.text(BedrockFormTexts.RULE_ALLOW, vars))
                .button(texts.text(BedrockFormTexts.RULE_DENY, vars))
                .button(texts.text(BedrockFormTexts.RULE_INHERIT, vars))
                .button(texts.text(BedrockFormTexts.RULE_CANCEL, vars))
                .build();
    }

    /**
     * 基本權限確認：Modal，第一顆確認寫入，第二顆取消（等同返回）。
     * 目標由呼叫端按共用切換規則算好，工廠只呈現不決策。
     */
    public static FormSpec.Modal permissionConfirmModal(String action,
            PermissionState current, PermissionState target, BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be null or blank");
        }
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        Map<String, Object> vars = Map.of("action", action,
                "current", current.name(), "target", target.name());
        return FormSpec.modal(texts.text(BedrockFormTexts.PERMISSION_CONFIRM_TITLE, vars))
                .content(texts.text(BedrockFormTexts.PERMISSION_CONFIRM_CONTENT, vars))
                .button1(texts.text(BedrockFormTexts.CONFIRM_OK, vars))
                .button2(texts.text(BedrockFormTexts.CONFIRM_CANCEL, vars))
                .build();
    }

    /** 子領地操作選單：選擇、新增、更新、刪除、擴張、返回。 */
    public static FormSpec.Simple sublandMenu() {
        return sublandMenu(BedrockFormTexts.english());
    }

    /** 子領地操作選單，文字由呼叫端注入。 */
    public static FormSpec.Simple sublandMenu(BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        return FormSpec.simple(texts.text(BedrockFormTexts.SUBLAND_TITLE))
                .content(texts.text(BedrockFormTexts.SUBLAND_CONTENT))
                .button(texts.text(BedrockFormTexts.SUBLAND_SELECT))
                .button(texts.text(BedrockFormTexts.SUBLAND_CREATE))
                .button(texts.text(BedrockFormTexts.SUBLAND_UPDATE))
                .button(texts.text(BedrockFormTexts.SUBLAND_DELETE))
                .button(texts.text(BedrockFormTexts.SUBLAND_EXTEND))
                .button(texts.text(BedrockFormTexts.BACK))
                .build();
    }

    /** 群組操作選單：新增、清單、加人、移除、刪除、返回。 */
    public static FormSpec.Simple groupMenu() {
        return groupMenu(BedrockFormTexts.english());
    }

    /** 群組操作選單，文字由呼叫端注入。 */
    public static FormSpec.Simple groupMenu(BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        return FormSpec.simple(texts.text(BedrockFormTexts.GROUP_TITLE))
                .content(texts.text(BedrockFormTexts.GROUP_CONTENT))
                .button(texts.text(BedrockFormTexts.GROUP_CREATE))
                .button(texts.text(BedrockFormTexts.GROUP_LIST))
                .button(texts.text(BedrockFormTexts.GROUP_ADD))
                .button(texts.text(BedrockFormTexts.GROUP_REMOVE))
                .button(texts.text(BedrockFormTexts.GROUP_DELETE))
                .button(texts.text(BedrockFormTexts.BACK))
                .build();
    }

    /** 設定檔操作選單：新增、清單、設值、刪除、返回。 */
    public static FormSpec.Simple profileMenu() {
        return profileMenu(BedrockFormTexts.english());
    }

    /** 設定檔操作選單，文字由呼叫端注入。 */
    public static FormSpec.Simple profileMenu(BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        return FormSpec.simple(texts.text(BedrockFormTexts.PROFILE_TITLE))
                .content(texts.text(BedrockFormTexts.PROFILE_CONTENT))
                .button(texts.text(BedrockFormTexts.PROFILE_CREATE))
                .button(texts.text(BedrockFormTexts.PROFILE_LIST))
                .button(texts.text(BedrockFormTexts.PROFILE_SET))
                .button(texts.text(BedrockFormTexts.PROFILE_DELETE))
                .button(texts.text(BedrockFormTexts.BACK))
                .build();
    }

    /**
     * 群組成員表單：群組與玩家兩個文字輸入，位置固定。
     * 工廠只收字串，群組與玩家的解析留給既有 group 槽位。
     */
    public static FormSpec.Custom groupMemberForm(String actionLabel) {
        return groupMemberForm(actionLabel, BedrockFormTexts.english());
    }

    /** 群組成員表單，文字由呼叫端注入。 */
    public static FormSpec.Custom groupMemberForm(String actionLabel, BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        if (actionLabel == null || actionLabel.isBlank()) {
            throw new IllegalArgumentException("action label must not be null or blank");
        }
        Map<String, Object> vars = Map.of("action", actionLabel);
        return FormSpec.custom(texts.text(BedrockFormTexts.GROUP_MEMBER_TITLE, vars))
                .input(texts.text(BedrockFormTexts.GROUP_MEMBER_CONTENT, vars),
                        texts.text(BedrockFormTexts.GROUP_MEMBER_GROUP_PLACEHOLDER, vars), "")
                .input(texts.text(BedrockFormTexts.GROUP_MEMBER_CONTENT, vars),
                        texts.text(BedrockFormTexts.GROUP_MEMBER_PLAYER_PLACEHOLDER, vars), "")
                .build();
    }

    /** 群組成員輸入：群組與玩家參照，兩者都必須非空白。 */
    public record GroupMemberInput(String groupRef, String playerName) {
        public GroupMemberInput {
            if (groupRef == null || groupRef.isBlank()) {
                throw new IllegalArgumentException("group ref must not be null or blank");
            }
            if (playerName == null || playerName.isBlank()) {
                throw new IllegalArgumentException("player name must not be null or blank");
            }
        }
    }

    /** 讀出群組成員輸入；缺失、空白或非 VALID 回應一律回傳 empty。 */
    public static Optional<GroupMemberInput> readGroupMember(FormResponse response) {
        try {
            if (response == null || response.status() != FormResponseStatus.VALID
                    || response.values() == null || response.values().size() < 2) {
                return Optional.empty();
            }
            if (!(response.values().get(0) instanceof FormValue.Text group)
                    || !(response.values().get(1) instanceof FormValue.Text player)) {
                return Optional.empty();
            }
            if (group.value() == null || group.value().isBlank()
                    || player.value() == null || player.value().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new GroupMemberInput(
                    group.value().strip(), player.value().strip()));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** 設定檔設值可選的權限：全部 ProtectionActionType，宣告順序。 */
    public static List<String> profileSetActions() {
        List<String> names = new ArrayList<>();
        for (ProtectionActionType action : ProtectionActionType.values()) {
            names.add(action.name());
        }
        return List.copyOf(names);
    }

    /** 設定檔設值可選的三態：ALLOW、DENY、INHERIT。 */
    public static List<String> profileSetStates() {
        return List.of(
                PermissionState.ALLOW.name(),
                PermissionState.DENY.name(),
                PermissionState.INHERIT.name());
    }

    /**
     * 設定檔設值表單：設定檔文字輸入、權限下拉、值下拉。
     * 下拉的合法性由既有 profile 槽位判定，工廠只傳索引。
     */
    public static FormSpec.Custom profileSetForm(BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        return FormSpec.custom(texts.text(BedrockFormTexts.PROFILE_SET_TITLE))
                .input(texts.text(BedrockFormTexts.PROFILE_SET_CONTENT),
                        texts.text(BedrockFormTexts.PROFILE_SET_PROFILE_PLACEHOLDER), "")
                .dropdown(texts.text(BedrockFormTexts.PROFILE_SET_ACTION_LABEL),
                        profileSetActions())
                .dropdown(texts.text(BedrockFormTexts.PROFILE_SET_STATE_LABEL),
                        profileSetStates())
                .build();
    }

    /** 設定檔設值輸入：設定檔參照加兩個下拉索引。 */
    public record ProfileSetInput(String profileRef, int actionIndex, int stateIndex) {
        public ProfileSetInput {
            if (profileRef == null || profileRef.isBlank()) {
                throw new IllegalArgumentException("profile ref must not be null or blank");
            }
        }
    }

    /** 讀出設定檔設值輸入；索引超出範圍一律回傳 empty。 */
    public static Optional<ProfileSetInput> readProfileSet(FormResponse response) {
        try {
            if (response == null || response.status() != FormResponseStatus.VALID
                    || response.values() == null || response.values().size() < 3) {
                return Optional.empty();
            }
            if (!(response.values().get(0) instanceof FormValue.Text profile)
                    || !(response.values().get(1) instanceof FormValue.Option action)
                    || !(response.values().get(2) instanceof FormValue.Option state)) {
                return Optional.empty();
            }
            if (profile.value() == null || profile.value().isBlank()) {
                return Optional.empty();
            }
            int actionIndex = action.index();
            int stateIndex = state.index();
            if (actionIndex < 0 || actionIndex >= profileSetActions().size()
                    || stateIndex < 0 || stateIndex >= profileSetStates().size()) {
                return Optional.empty();
            }
            return Optional.of(new ProfileSetInput(
                    profile.value().strip(), actionIndex, stateIndex));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /**
     * 刪除二選一確認：一般刪除、強制刪除、保留。
     * 保留等同返回，絕不寫入。
     */
    public static FormSpec.Simple deleteChoiceMenu(String targetLabel) {
        return deleteChoiceMenu(targetLabel, BedrockFormTexts.english());
    }

    /** 刪除二選一確認，文字由呼叫端注入。 */
    public static FormSpec.Simple deleteChoiceMenu(String targetLabel, BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        if (targetLabel == null || targetLabel.isBlank()) {
            throw new IllegalArgumentException("target label must not be null or blank");
        }
        Map<String, Object> vars = Map.of("target", targetLabel);
        return FormSpec.simple(texts.text(BedrockFormTexts.DELETE_CHOICE_TITLE, vars))
                .content(texts.text(BedrockFormTexts.DELETE_CHOICE_CONTENT, vars))
                .button(texts.text(BedrockFormTexts.DELETE_NOW, vars))
                .button(texts.text(BedrockFormTexts.FORCE_DELETE, vars))
                .button(texts.text(BedrockFormTexts.DELETE_KEEP, vars))
                .build();
    }

    /**
     * 通用通知頁：標題加內容，只剩返回。空清單這類無需選擇的狀態用它，
     * 不靜默失敗。
     */
    public static FormSpec.Simple noticePage(String title, String content) {
        return noticePage(title, content, BedrockFormTexts.english());
    }

    /** 通用通知頁，文字由呼叫端注入。 */
    public static FormSpec.Simple noticePage(String title, String content,
            BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be null or blank");
        }
        return FormSpec.simple(title)
                .content(content)
                .button(texts.text(BedrockFormTexts.BACK))
                .build();
    }

    /**
     * 通用錯誤頁：說明原因，只剩返回。送出時重新確認失敗、
     * 基線讀不到、輸入非法都回到這裡，不靜默失敗。
     */
    public static FormSpec.Simple errorPage(String reason) {
        return errorPage(reason, BedrockFormTexts.english());
    }

    /** 通用錯誤頁，文字由呼叫端注入。 */
    public static FormSpec.Simple errorPage(String reason, BedrockFormTexts texts) {
        Objects.requireNonNull(texts, "texts");
        String shown = reason == null || reason.isBlank() ? "-" : reason;
        return FormSpec.simple(texts.text(BedrockFormTexts.ERROR_TITLE))
                .content(texts.text(BedrockFormTexts.ERROR_CONTENT, Map.of("reason", shown)))
                .button(texts.text(BedrockFormTexts.BACK))
                .build();
    }
}
