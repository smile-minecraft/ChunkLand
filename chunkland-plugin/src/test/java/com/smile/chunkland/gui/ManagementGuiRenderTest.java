package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.adapter.gui.ManagementGuiTextProvider;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainLayer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red contract for visible management render data: the second layer must
 * carry every row's action, final outcome and source layer, plus the three
 * conflict remedies, inside the GUI render data itself — not only inside
 * the unrendered {@link ManagementGuiModel}. The public AceLib
 * {@code GuiArgument} only transports title/size/protected slots, so the
 * structured summary lives on the page title and the per-row detail lives
 * on the page lines.
 */
class ManagementGuiRenderTest {

    private static PermissionExplain explainOf(ProtectionActionType action,
            PermissionState outcome, PermissionExplainLayer layer) {
        return new PermissionExplain(action, outcome,
                action.decisionSource(), layer,
                "test reason -> " + outcome, null, false, false, false);
    }

    private static PermissionContext landBindingContext(ProtectionActionType action,
            List<PermissionBinding> bindings) {
        return PermissionContext.builder(action)
                .landBindings(bindings)
                .build();
    }

    private static PermissionBinding binding(UUID player, ProtectionActionType action,
            PermissionState state) {
        return new PermissionBinding(PermissionSubject.player(player),
                new Permission(action, state));
    }

    private static String joinedLines(GuiPage page) {
        return String.join("\n", page.lines());
    }

    private static boolean noEveryone(GuiPage page) {
        String haystack = (page.id() + "\n" + page.title() + "\n" + joinedLines(page))
                .toUpperCase(Locale.ROOT);
        return !haystack.contains("EVERYONE");
    }

    @Test
    void frameworkPageCarriesImmutableRenderLines() {
        GuiPage bare = GuiPage.of("main", "Land Menu", 27, List.of(
                new GuiButton(10, click -> { })));
        assertTrue(bare.lines().isEmpty(), "legacy pages default to no lines");

        GuiPage lined = GuiPage.of("main", "Land Menu", 27,
                List.of(new GuiButton(10, click -> { })),
                List.of("BLOCK_BREAK: DENY @ LAND_BINDING"));
        assertEquals(List.of("BLOCK_BREAK: DENY @ LAND_BINDING"), lined.lines());
        assertThrows(UnsupportedOperationException.class,
                () -> lined.lines().add("mutation must fail"));

        assertThrows(NullPointerException.class, () -> GuiPage.of("main", "t", 9,
                List.of(), null));
    }

    @Test
    void detailsLinesShowEveryRowActionOutcomeAndLayer() {
        ProtectionActionType denied = ProtectionActionType.CONTAINER_OPEN;
        ProtectionActionType allowed = ProtectionActionType.BLOCK_BREAK;
        ManagementGuiModel model = ManagementGuiModel.fromExplains(
                List.of(
                        explainOf(allowed, PermissionState.ALLOW,
                                PermissionExplainLayer.LAND_DEFAULT),
                        explainOf(denied, PermissionState.DENY,
                                PermissionExplainLayer.LAND_BINDING)),
                Map.of(
                        allowed, landBindingContext(allowed, List.of()),
                        denied, landBindingContext(denied, List.of(
                                binding(UUID.randomUUID(), denied, PermissionState.DENY)))));

        GuiPage details = ManagementGuiPages.detailsPage(model, ManagementGuiActions.noop(), ManagementGuiTextProvider.fallbackTexts());
        String lines = joinedLines(details);

        for (ManagementPermissionRow row : model.rows()) {
            assertTrue(lines.contains(row.action().name()),
                    "render data must name " + row.action());
            assertTrue(lines.contains(row.outcome().name()),
                    "render data must show the final " + row.outcome());
            assertTrue(lines.contains(row.layer().name()),
                    "render data must show source layer " + row.layer());
        }
        assertTrue(noEveryone(details));
    }

    @Test
    void conflictLinesShowFinalDenyWithThreeRemedies() {
        ProtectionActionType action = ProtectionActionType.BLOCK_BREAK;
        PermissionContext ctx = landBindingContext(action, List.of(
                binding(UUID.randomUUID(), action, PermissionState.DENY),
                binding(UUID.randomUUID(), action, PermissionState.ALLOW)));
        ManagementGuiModel model = ManagementGuiModel.fromExplains(
                List.of(explainOf(action, PermissionState.DENY,
                        PermissionExplainLayer.LAND_BINDING)),
                Map.of(action, ctx));
        ManagementPermissionRow row = model.rows().get(0);
        assertTrue(row.conflict());

        GuiPage details = ManagementGuiPages.detailsPage(model, ManagementGuiActions.noop(), ManagementGuiTextProvider.fallbackTexts());
        String lines = joinedLines(details);

        assertTrue(lines.contains("DENY"), "conflict render data must show final DENY");
        assertTrue(lines.contains(PermissionExplainLayer.LAND_BINDING.name()));
        List<String> remedies = model.remediesFor(row);
        assertEquals(3, remedies.size());
        for (String remedy : remedies) {
            assertTrue(lines.contains(remedy),
                    "conflict render data must carry remedy: " + remedy);
        }
        assertTrue(noEveryone(details));
    }

    @Test
    void detailsTitleSummarisesFinalEffects() {
        ManagementGuiModel model = ManagementGuiModel.fromExplains(
                List.of(
                        explainOf(ProtectionActionType.BLOCK_BREAK,
                                PermissionState.ALLOW, PermissionExplainLayer.LAND_DEFAULT),
                        explainOf(ProtectionActionType.CONTAINER_OPEN,
                                PermissionState.DENY, PermissionExplainLayer.LAND_BINDING),
                        explainOf(ProtectionActionType.ENTRY,
                                PermissionState.DENY, PermissionExplainLayer.WORLD_DEFAULT)),
                Map.of(
                        ProtectionActionType.BLOCK_BREAK,
                        landBindingContext(ProtectionActionType.BLOCK_BREAK, List.of()),
                        ProtectionActionType.CONTAINER_OPEN,
                        landBindingContext(ProtectionActionType.CONTAINER_OPEN, List.of()),
                        ProtectionActionType.ENTRY,
                        landBindingContext(ProtectionActionType.ENTRY, List.of())));

        GuiPage details = ManagementGuiPages.detailsPage(model, ManagementGuiActions.noop(), ManagementGuiTextProvider.fallbackTexts());

        assertTrue(details.title().contains("2 DENY"),
                "title must summarise DENY count, got: " + details.title());
        assertTrue(details.title().contains("1 ALLOW"),
                "title must summarise ALLOW count, got: " + details.title());
        assertTrue(noEveryone(details));
    }

    @Test
    void unavailablePageRendersFailClosedWithoutRowData() {
        GuiPage unavailable = ManagementGuiPages.detailsPage(
                ManagementGuiModel.unavailable(), ManagementGuiActions.noop(),
                ManagementGuiTextProvider.fallbackTexts());

        assertEquals(ManagementGuiPages.UNAVAILABLE_PAGE_ID, unavailable.id());
        assertFalse(unavailable.lines().isEmpty(), "fail-closed must still render visibly");
        String lines = joinedLines(unavailable).toUpperCase(Locale.ROOT);
        assertTrue(lines.contains("FAIL-CLOSED") || lines.contains("UNAVAILABLE"));
        for (ProtectionActionType action : ProtectionActionType.values()) {
            assertFalse(joinedLines(unavailable).contains(action.name()),
                    "fail-closed render data must not leak row data");
        }
        assertTrue(noEveryone(unavailable));
        assertTrue(noEveryone(
                ManagementGuiPages.detailsPage(null, ManagementGuiActions.noop(), ManagementGuiTextProvider.fallbackTexts())));
    }
}
