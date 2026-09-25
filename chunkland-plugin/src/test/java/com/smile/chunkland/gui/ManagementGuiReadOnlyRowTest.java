package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Management-class rows are view-only: they keep their display item
 * (including the read-only marker lore) but their slot never routes a
 * change request, so a click can neither open a confirm page nor reach a
 * write seam. Whitelisted rows keep routing exactly as before.
 */
class ManagementGuiReadOnlyRowTest {

    private static final String SENTINEL = ManagementGuiTextInjectionTest.SENTINEL;

    private static PermissionExplain explainOf(ProtectionActionType action,
            PermissionState outcome, PermissionExplainLayer layer) {
        return new PermissionExplain(action, outcome,
                action.decisionSource(), layer,
                "test reason -> " + outcome, null, false, false, false);
    }

    private static ManagementGuiModel model(ProtectionActionType action) {
        PermissionContext ctx = PermissionContext.builder(action)
                .landBindings(List.of(new PermissionBinding(
                        PermissionSubject.player(UUID.randomUUID()),
                        new Permission(action, PermissionState.ALLOW))))
                .build();
        return ManagementGuiModel.fromExplains(
                List.of(explainOf(action, PermissionState.ALLOW,
                        PermissionExplainLayer.LAND_BINDING)),
                Map.of(action, ctx));
    }

    private static String visibleText(GuiPage page) {
        StringBuilder out = new StringBuilder(page.title()).append('\n');
        for (String line : page.lines()) {
            out.append(line).append('\n');
        }
        for (GuiPage.RenderItem item : page.renderItems()) {
            out.append(item.name()).append('\n');
            for (String lore : item.lore()) {
                out.append(lore).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void managementRowKeepsItsItemButNeverRoutesAChange() {
        AtomicReference<ProtectionActionType> requested = new AtomicReference<>();
        ManagementGuiActions actions = new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
            }

            @Override
            public void back(GuiClickContext click) {
            }

            @Override
            public void requestChange(GuiClickContext click, ProtectionActionType action) {
                requested.set(action);
            }
        };
        GuiPage details = ManagementGuiPages.detailsPage(
                model(ProtectionActionType.DELETE_LAND), actions,
                ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.INHERIT,
                action -> false);

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        UUID viewer = UUID.randomUUID();
        long generation = navigator.open(viewer, details).orElseThrow();
        navigator.handleClick(viewer, generation, 0);

        assertTrue(requested.get() == null, "read-only rows must not route change requests");
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(viewer).orElseThrow().id());
    }

    @Test
    void managementRowCarriesTheReadOnlyMarker() {
        GuiPage details = ManagementGuiPages.detailsPage(
                model(ProtectionActionType.MANAGE_PERMISSION),
                ManagementGuiActions.noop(),
                ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.INHERIT,
                action -> false);

        String visible = visibleText(details);
        assertTrue(visible.contains(SENTINEL + "-readonly"),
                "read-only rows must explain themselves, got:\n" + visible);
        assertFalse(visible.toUpperCase(Locale.ROOT).contains("EVERYONE"));
    }

    @Test
    void whitelistedRowStillRoutesWhenPredicateAllows() {
        AtomicReference<ProtectionActionType> requested = new AtomicReference<>();
        ManagementGuiActions actions = new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
            }

            @Override
            public void back(GuiClickContext click) {
            }

            @Override
            public void requestChange(GuiClickContext click, ProtectionActionType action) {
                requested.set(action);
            }
        };
        GuiPage details = ManagementGuiPages.detailsPage(
                model(ProtectionActionType.BLOCK_BREAK), actions,
                ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.INHERIT,
                action -> true);

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        UUID viewer = UUID.randomUUID();
        long generation = navigator.open(viewer, details).orElseThrow();
        navigator.handleClick(viewer, generation, 0);

        assertEquals(ProtectionActionType.BLOCK_BREAK, requested.get());
    }

    @Test
    void nullPredicateReadsAsAllToggleable() {
        AtomicReference<ProtectionActionType> requested = new AtomicReference<>();
        ManagementGuiActions actions = new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
            }

            @Override
            public void back(GuiClickContext click) {
            }

            @Override
            public void requestChange(GuiClickContext click, ProtectionActionType action) {
                requested.set(action);
            }
        };
        GuiPage details = ManagementGuiPages.detailsPage(
                model(ProtectionActionType.BLOCK_BREAK), actions,
                ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.INHERIT,
                null);

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        UUID viewer = UUID.randomUUID();
        long generation = navigator.open(viewer, details).orElseThrow();
        navigator.handleClick(viewer, generation, 0);

        assertEquals(ProtectionActionType.BLOCK_BREAK, requested.get());
    }
}
