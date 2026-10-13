package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.rule.LandRuleView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Rule pages over the management navigator: a two-entry root, one row per
 * rule carrying its explicit, effective and source values, and a
 * three-target confirm page. Every button routes through the caller seam;
 * the pages never write anything themselves.
 */
class ManagementRulePagesTest {

    private static final UUID VIEWER = UUID.randomUUID();

    private static ManagementRuleTexts texts() {
        return new ManagementRuleTexts(
                "Environment rules",
                List.of("Change what the world itself may do here."),
                (deny, allow) -> "Land Rules (" + deny + " denied, " + allow + " allowed)",
                (rule, explicit, effective, source) ->
                        rule + ": " + explicit + " -> " + effective + " (" + source + ")",
                (rule, explicit) -> "Land override for " + rule + ": "
                        + (explicit == null ? "unset" : explicit),
                (effective, source) -> "Effective now: " + effective + " (from " + source + ")",
                "Click to change this rule.",
                rule -> "Confirm rule change: " + rule,
                (explicit, effective, source) -> "Now: " + explicit + " -> " + effective
                        + " (from " + source + ")",
                state -> "After confirming it becomes: " + state,
                "Allow", "Deny", "Inherit", "Cancel", "Back",
                "The change did not go through and nothing was modified.",
                "Land Rules (unavailable)",
                List.of("Rules cannot be shown right now."));
    }

    private static ManagementGuiTexts permissionTexts() {
        return com.smile.chunkland.adapter.gui.ManagementGuiTextProvider.fallbackTexts();
    }

    private static ManagementRuleModel model() {
        List<LandRuleView> views = new ArrayList<>();
        for (LandRuleType rule : LandRuleType.values()) {
            views.add(new LandRuleView(rule, PermissionState.INHERIT, PermissionState.DENY,
                    LandRuleView.Source.BUILT_IN));
        }
        views.set(0, new LandRuleView(LandRuleType.PVP, PermissionState.ALLOW,
                PermissionState.ALLOW, LandRuleView.Source.LAND));
        return ManagementRuleModel.fromViews(views);
    }

    private static ManagementGuiActions actionsRecording(AtomicReference<String> seen,
            AtomicReference<LandRuleType> ruleSeen) {
        return new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
                seen.set("permissions");
            }

            @Override
            public void back(GuiClickContext click) {
            }

            @Override
            public void requestChange(GuiClickContext click, ProtectionActionType action) {
            }

            @Override
            public void openRuleDetails(GuiClickContext click) {
                seen.set("rules");
            }

            @Override
            public void requestRuleChange(GuiClickContext click, LandRuleType rule) {
                ruleSeen.set(rule);
            }
        };
    }

    @Test
    void rootCarriesBothPermissionAndRuleEntries() {
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<LandRuleType> ruleSeen = new AtomicReference<>();
        GuiPage root = ManagementRulePages.rootPage(
                actionsRecording(seen, ruleSeen), permissionTexts(), texts());

        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, root.id());
        assertTrue(root.buttonAt(ManagementGuiPages.ENTRY_SLOT).isPresent(),
                "the permission entry stays on its slot");
        assertTrue(root.buttonAt(ManagementRulePages.RULE_ENTRY_SLOT).isPresent(),
                "the rule entry gets its own slot");

        GuiNavigator navigator = new GuiNavigator(new FakeGuiService());
        long generation = navigator.open(VIEWER, root).orElseThrow();
        navigator.handleClick(VIEWER, generation, ManagementRulePages.RULE_ENTRY_SLOT);
        assertEquals("rules", seen.get(), "the rule entry must route to the rule seam");

        navigator.handleClick(VIEWER, navigator.currentGeneration(VIEWER).orElseThrow(),
                ManagementGuiPages.ENTRY_SLOT);
        assertEquals("permissions", seen.get(), "the permission entry keeps routing");
    }

    @Test
    void ruleRowsShowExplicitEffectiveAndSource() {
        GuiPage page = ManagementRulePages.rulesPage(model(),
                ManagementGuiActions.noop(), texts());

        assertEquals(ManagementRulePages.RULES_PAGE_ID, page.id());
        assertEquals(11, page.renderItems().size() - 1,
                "one item per rule plus the back button, got: " + page.renderItems());
        String lines = String.join("\n", page.lines());
        assertTrue(lines.contains("PVP") && lines.contains("ALLOW"),
                "rows must name the rule and its explicit value, got:\n" + lines);
        assertTrue(lines.contains("BUILT_IN") && lines.contains("LAND"),
                "rows must name the source layer, got:\n" + lines);
        assertTrue(page.buttonAt(ManagementGuiPages.BACK_SLOT).isPresent());
    }

    @Test
    void ruleRowClickRoutesRuleChangeThroughTheSeam() {
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<LandRuleType> ruleSeen = new AtomicReference<>();
        GuiNavigator navigator = new GuiNavigator(new FakeGuiService());
        GuiPage page = ManagementRulePages.rulesPage(model(),
                actionsRecording(seen, ruleSeen), texts());
        long generation = navigator.open(VIEWER, page).orElseThrow();

        navigator.handleClick(VIEWER, generation, 0);

        assertEquals(LandRuleType.values()[0], ruleSeen.get(),
                "clicking a rule row must request that rule, in rule order");
    }

    @Test
    void unavailableModelYieldsTheUnavailablePage() {
        GuiPage page = ManagementRulePages.rulesPage(ManagementRuleModel.unavailable(),
                ManagementGuiActions.noop(), texts());
        assertEquals(ManagementRulePages.RULE_UNAVAILABLE_PAGE_ID, page.id());
        assertTrue(page.renderItems().size() <= 2,
                "no rule rows may leak, got: " + page.renderItems().size());
    }

    @Test
    void confirmPageOffersThreeTargetsAndShowsCurrent() {
        AtomicReference<String> seen = new AtomicReference<>();
        GuiAction record = click -> seen.set("target");
        ManagementRulePages.RuleConfirmRequest request =
                new ManagementRulePages.RuleConfirmRequest(LandRuleType.PVP,
                        "INHERIT", "DENY", "BUILT_IN",
                        record, record, record, click -> seen.set("cancel"),
                        click -> seen.set("back"), false);
        GuiPage page = ManagementRulePages.confirmPage(request, texts());

        assertEquals(ManagementRulePages.RULE_CONFIRM_PAGE_ID, page.id());
        assertTrue(page.buttonAt(ManagementRulePages.RULE_ALLOW_SLOT).isPresent());
        assertTrue(page.buttonAt(ManagementRulePages.RULE_DENY_SLOT).isPresent());
        assertTrue(page.buttonAt(ManagementRulePages.RULE_INHERIT_SLOT).isPresent());
        String lines = String.join("\n", page.lines());
        assertTrue(lines.contains("INHERIT") && lines.contains("DENY")
                && lines.contains("BUILT_IN"),
                "the confirm must show the current triple, got:\n" + lines);
        assertFalse(lines.contains("nothing was modified"),
                "a fresh confirm must not show the failure line");

        GuiNavigator navigator = new GuiNavigator(new FakeGuiService());
        long generation = navigator.open(VIEWER, page).orElseThrow();
        navigator.handleClick(VIEWER, generation, ManagementRulePages.RULE_ALLOW_SLOT);
        assertEquals("target", seen.get());
    }

    @Test
    void failedConfirmShowsTheFailureLine() {
        GuiAction noop = click -> {
        };
        ManagementRulePages.RuleConfirmRequest request =
                new ManagementRulePages.RuleConfirmRequest(LandRuleType.PVP,
                        "INHERIT", "DENY", "BUILT_IN",
                        noop, noop, noop, noop, noop, true);
        GuiPage page = ManagementRulePages.confirmPage(request, texts());
        assertTrue(String.join("\n", page.lines()).contains("nothing was modified"));
    }
}
