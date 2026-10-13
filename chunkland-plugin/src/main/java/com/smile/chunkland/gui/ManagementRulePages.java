package com.smile.chunkland.gui;

import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.rule.LandRuleView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rule management pages over the shared {@link GuiNavigator}.
 *
 * <p>The first layer is shared with the permission pages: the root carries
 * both the permission entry (slot {@link ManagementGuiPages#ENTRY_SLOT})
 * and the rule-list entry ({@link #RULE_ENTRY_SLOT}). The second layer
 * renders one button per rule in {@link LandRuleType} declaration order —
 * eleven rows fit one page, so no paging is needed — each carrying its
 * explicit (land override), effective (deciding) and source values. The
 * third layer is a three-target confirm page: unlike the permission toggle,
 * a rule change needs {@code ALLOW}, {@code DENY} and {@code INHERIT} as
 * separate targets because "inherit" is its own state, not a toggle step.
 *
 * <p>All pages are Bukkit-free immutable values; navigation generations
 * stay with the upstream sessions owned by the navigator. The pages never
 * mutate domain state and never decide authorisation: every button only
 * reaches its injected callback, and the caller re-checks the shared
 * management gate plus the pinned baseline at submit time.
 */
public final class ManagementRulePages {

    /** Second-layer rule list. */
    public static final String RULES_PAGE_ID = "chunkland:land-manage-rules";

    /** Fail-closed page for missing rule data. */
    public static final String RULE_UNAVAILABLE_PAGE_ID = "chunkland:land-manage-rules-unavailable";

    /** Third-layer confirm page for one pending rule change. */
    public static final String RULE_CONFIRM_PAGE_ID = "chunkland:land-manage-rule-confirm";

    /** Slot of the rule-list entry button on the root page. */
    public static final int RULE_ENTRY_SLOT = 17;

    /** Slot of the allow-target button on the rule confirm page. */
    public static final int RULE_ALLOW_SLOT = 10;

    /** Slot of the deny-target button on the rule confirm page. */
    public static final int RULE_DENY_SLOT = 12;

    /** Slot of the inherit-target button on the rule confirm page. */
    public static final int RULE_INHERIT_SLOT = 14;

    /** Slot of the cancel button on the rule confirm page. */
    public static final int RULE_CANCEL_SLOT = 16;

    /** Inventory size for the rule list and confirm pages. */
    static final int RULES_SIZE = 27;

    static final int RULE_CONFIRM_SIZE = 27;

    /**
     * One pending rule change plus its button callbacks.
     *
     * @param rule the rule under change; never {@code null}
     * @param explicit current land-override display name, or {@code null}
     *     when unset (renders the injected unset wording)
     * @param effective current effective display name; never {@code null}
     * @param source current source display name; never {@code null}
     * @param allow allow-target callback; {@code null} reads as no-op
     * @param deny deny-target callback; {@code null} reads as no-op
     * @param inherit inherit-target callback; {@code null} reads as no-op
     * @param cancel cancel-button callback; {@code null} reads as no-op
     * @param back back-button callback; {@code null} reads as no-op
     * @param failed whether this render follows a failed attempt, in
     *     which case the injected failure line is shown as well
     */
    public record RuleConfirmRequest(LandRuleType rule, String explicit, String effective,
            String source, GuiAction allow, GuiAction deny, GuiAction inherit,
            GuiAction cancel, GuiAction back, boolean failed) {
        public RuleConfirmRequest {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(effective, "effective");
            Objects.requireNonNull(source, "source");
        }
    }

    private ManagementRulePages() {
    }

    /**
     * First-layer entry page with both the permission and the rule entries.
     * A {@code null} seam reads as fail-closed (no-op buttons). All visible
     * copy comes from {@code texts} and {@code ruleTexts}; the page never
     * embeds prose.
     */
    public static GuiPage rootPage(ManagementGuiActions actions, ManagementGuiTexts texts,
            ManagementRuleTexts ruleTexts) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        Objects.requireNonNull(texts, "texts");
        Objects.requireNonNull(ruleTexts, "ruleTexts");
        GuiPage permissionRoot = ManagementGuiPages.rootPage(seam, texts);
        GuiButton ruleEntry = new GuiButton(RULE_ENTRY_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.openRuleDetails(click);
        });
        List<GuiButton> buttons = new ArrayList<>(permissionRoot.buttons().values());
        buttons.add(ruleEntry);
        List<String> lines = new ArrayList<>(permissionRoot.lines());
        lines.addAll(ruleTexts.entryLines());
        List<GuiPage.RenderItem> renderItems = new ArrayList<>(permissionRoot.renderItems());
        renderItems.add(new GuiPage.RenderItem(RULE_ENTRY_SLOT, ruleTexts.entryName(),
                List.copyOf(ruleTexts.entryLines()), "rules"));
        return GuiPage.of(permissionRoot.id(), permissionRoot.title(), permissionRoot.size(),
                List.copyOf(buttons), List.copyOf(lines), List.copyOf(renderItems));
    }

    /**
     * Second-layer rule list for a confirmed model. A {@code null} or
     * unavailable model yields the fail-closed unavailable page with no
     * rows.
     *
     * <p>The page title carries the effective counts and every row item
     * carries the explicit line, the effective line and the change hint,
     * so the triple lives in the GUI render data itself.
     */
    public static GuiPage rulesPage(ManagementRuleModel model, ManagementGuiActions actions,
            ManagementRuleTexts texts) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        Objects.requireNonNull(texts, "texts");
        if (model == null || !model.available() || model.rows().isEmpty()) {
            return unavailablePage(seam, texts);
        }
        List<LandRuleView> rows = model.rows();
        int capacity = RULES_SIZE - 1;
        List<GuiButton> buttons = new ArrayList<>(rows.size() + 1);
        List<String> lines = new ArrayList<>(rows.size() * 2 + 1);
        List<GuiPage.RenderItem> renderItems = new ArrayList<>(rows.size() + 1);
        int deny = 0;
        int allow = 0;
        for (int index = 0; index < rows.size() && index < capacity; index++) {
            LandRuleView row = rows.get(index);
            if (row == null || row.rule() == null) {
                continue;
            }
            final LandRuleType rule = row.rule();
            buttons.add(new GuiButton(index, click -> {
                Objects.requireNonNull(click, "click");
                seam.requestRuleChange(click, rule);
            }));
            String explicit = row.explicit() == null ? null : row.explicit().name();
            String effective = row.effective() == null
                    ? com.smile.chunkland.api.permission.PermissionState.INHERIT.name()
                    : row.effective().name();
            if (com.smile.chunkland.api.permission.PermissionState.DENY.name()
                    .equals(effective)) {
                deny++;
            } else {
                allow++;
            }
            String source = row.source() == null
                    ? LandRuleView.Source.UNAVAILABLE.name() : row.source().name();
            String head = texts.rowHead().head(rule.name(), explicitName(explicit), effective,
                    source);
            lines.add(head);
            List<String> itemLore = List.of(
                    texts.explicitLine().line(rule.name(), explicit),
                    texts.effectiveLine().line(effective, source),
                    texts.changeHintLine());
            lines.addAll(itemLore);
            renderItems.add(new GuiPage.RenderItem(index, head, itemLore,
                    com.smile.chunkland.api.permission.PermissionState.DENY.name()
                            .equals(effective) ? "deny" : "allow"));
        }
        buttons.add(new GuiButton(ManagementGuiPages.BACK_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.back(click);
        }));
        renderItems.add(new GuiPage.RenderItem(ManagementGuiPages.BACK_SLOT, texts.backName(),
                List.of(), "back"));
        String title = texts.detailsTitle().title(deny, allow);
        return GuiPage.of(RULES_PAGE_ID, title, RULES_SIZE, buttons, lines, renderItems);
    }

    /**
     * Third-layer confirm page for one pending rule change. The title names
     * the rule; the lore carries the current triple plus the target-state
     * line so the pending transition stays visible, and a failed attempt
     * appends the injected failure line. The page never writes anything
     * itself: every button only reaches its injected callback.
     */
    public static GuiPage confirmPage(RuleConfirmRequest request, ManagementRuleTexts texts) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(texts, "texts");
        GuiAction onAllow = request.allow() == null ? click -> {
        } : request.allow();
        GuiAction onDeny = request.deny() == null ? click -> {
        } : request.deny();
        GuiAction onInherit = request.inherit() == null ? click -> {
        } : request.inherit();
        GuiAction onCancel = request.cancel() == null ? click -> {
        } : request.cancel();
        GuiAction onBack = request.back() == null ? click -> {
        } : request.back();
        String ruleName = request.rule().name();
        String currentLine = texts.confirmCurrentLine().line(
                explicitName(request.explicit()), request.effective(), request.source());
        String allowTarget = texts.confirmTargetLine().line(
                com.smile.chunkland.api.permission.PermissionState.ALLOW.name());
        String denyTarget = texts.confirmTargetLine().line(
                com.smile.chunkland.api.permission.PermissionState.DENY.name());
        String inheritTarget = texts.confirmTargetLine().line(
                com.smile.chunkland.api.permission.PermissionState.INHERIT.name());
        List<String> allowLore = request.failed()
                ? List.of(currentLine, allowTarget, texts.confirmFailedLine())
                : List.of(currentLine, allowTarget);
        List<String> denyLore = request.failed()
                ? List.of(currentLine, denyTarget, texts.confirmFailedLine())
                : List.of(currentLine, denyTarget);
        List<String> inheritLore = request.failed()
                ? List.of(currentLine, inheritTarget, texts.confirmFailedLine())
                : List.of(currentLine, inheritTarget);
        List<String> lines = new ArrayList<>();
        lines.add(currentLine);
        lines.add(allowTarget);
        lines.add(denyTarget);
        lines.add(inheritTarget);
        if (request.failed()) {
            lines.add(texts.confirmFailedLine());
        }
        List<GuiButton> buttons = List.of(
                new GuiButton(RULE_ALLOW_SLOT, onAllow),
                new GuiButton(RULE_DENY_SLOT, onDeny),
                new GuiButton(RULE_INHERIT_SLOT, onInherit),
                new GuiButton(RULE_CANCEL_SLOT, onCancel),
                new GuiButton(ManagementGuiPages.BACK_SLOT, onBack));
        List<GuiPage.RenderItem> renderItems = List.of(
                new GuiPage.RenderItem(RULE_ALLOW_SLOT, texts.confirmAllowName(),
                        allowLore, "allow"),
                new GuiPage.RenderItem(RULE_DENY_SLOT, texts.confirmDenyName(),
                        denyLore, "deny"),
                new GuiPage.RenderItem(RULE_INHERIT_SLOT, texts.confirmInheritName(),
                        inheritLore, "inherit"),
                new GuiPage.RenderItem(RULE_CANCEL_SLOT, texts.confirmCancelName(),
                        List.of(), "cancel"),
                new GuiPage.RenderItem(ManagementGuiPages.BACK_SLOT, texts.backName(),
                        List.of(), "back"));
        return GuiPage.of(RULE_CONFIRM_PAGE_ID,
                texts.confirmTitle().title(ruleName),
                RULE_CONFIRM_SIZE, buttons, List.copyOf(lines), renderItems);
    }

    /**
     * Fail-closed page: no rows, only a back button. The lines render the
     * generic fail-closed notice visibly and never leak row data.
     */
    public static GuiPage unavailablePage(ManagementGuiActions actions,
            ManagementRuleTexts texts) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        Objects.requireNonNull(texts, "texts");
        GuiButton back = new GuiButton(ManagementGuiPages.BACK_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.back(click);
        });
        List<String> lines = texts.unavailableLines();
        return GuiPage.of(RULE_UNAVAILABLE_PAGE_ID,
                texts.unavailableTitle(), RULES_SIZE, List.of(back),
                lines, List.of(new GuiPage.RenderItem(ManagementGuiPages.BACK_SLOT,
                        texts.backName(), lines, "back")));
    }

    private static String explicitName(String explicit) {
        return explicit == null
                ? com.smile.chunkland.api.permission.PermissionState.INHERIT.name() : explicit;
    }
}
