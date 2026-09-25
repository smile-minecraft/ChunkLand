package com.smile.chunkland.gui;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The management pages must render caller-injected texts instead of
 * producing user-visible strings themselves: every visible slot (page
 * title, page lines, item names and item lore) follows the injected
 * sentinel, which proves the composition path no longer hardcodes copy.
 */
class ManagementGuiTextInjectionTest {

    static final String SENTINEL = "__SENTINEL_ENTRY__";

    /** Legacy hardcoded wording that must no longer surface anywhere. */
    private static final List<String> LEGACY_COPY = List.of(
            "Land Management",
            "Land Permissions",
            "Open the permission detail view.",
            "Outcome unavailable",
            " !CONFLICT");

    private static PermissionExplain explainOf(ProtectionActionType action,
            PermissionState outcome, PermissionExplainLayer layer) {
        return new PermissionExplain(action, outcome,
                action.decisionSource(), layer,
                "test reason -> " + outcome, null, false, false, false);
    }

    private static ManagementGuiModel model() {
        ProtectionActionType action = ProtectionActionType.CONTAINER_OPEN;
        PermissionContext ctx = PermissionContext.builder(action)
                .landBindings(List.of(
                        new PermissionBinding(
                                PermissionSubject.player(UUID.randomUUID()),
                                new Permission(action, PermissionState.DENY)),
                        new PermissionBinding(
                                PermissionSubject.player(UUID.randomUUID()),
                                new Permission(action, PermissionState.ALLOW))))
                .build();
        return ManagementGuiModel.fromExplains(
                List.of(explainOf(action, PermissionState.DENY,
                        PermissionExplainLayer.LAND_BINDING)),
                Map.of(action, ctx));
    }

    static ManagementGuiTexts sentinelTexts() {
        return new ManagementGuiTexts(
                SENTINEL + "-root",
                SENTINEL + "-entry",
                List.of(SENTINEL + "-hint"),
                SENTINEL + "-unavailable-title",
                List.of(SENTINEL + "-u1", SENTINEL + "-u2", SENTINEL + "-u3"),
                SENTINEL + "-back",
                (deny, allow) -> SENTINEL + "-title(" + deny + "," + allow + ")",
                (action, outcome, layer, conflict) -> SENTINEL + "-head:" + action + ":"
                        + outcome + ":" + layer + (conflict ? ":conflict" : ""),
                (layer, outcome, conflict) -> List.of(
                        SENTINEL + "-remedy1:" + layer,
                        SENTINEL + "-remedy2:" + outcome,
                        SENTINEL + "-remedy3"),
                (action, state) -> SENTINEL + "-default:" + action + ":" + state,
                new ManagementGuiTexts.ConfirmTexts(
                        action -> SENTINEL + "-confirm:" + action,
                        SENTINEL + "-yes",
                        SENTINEL + "-no",
                        SENTINEL + "-confirm-back",
                        state -> SENTINEL + "-target:" + state,
                        SENTINEL + "-failed"),
                SENTINEL + "-readonly");
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

    private static void assertOnlySentinel(GuiPage page) {
        String visible = visibleText(page);
        assertTrue(visible.contains(SENTINEL),
                "page must render injected texts, got:\n" + visible);
        for (String legacy : LEGACY_COPY) {
            assertFalse(visible.contains(legacy),
                    "legacy copy must not surface, found \"" + legacy + "\" in:\n" + visible);
        }
        assertFalse(visible.contains("Back"),
                "legacy Back label must not surface in:\n" + visible);
    }

    @Test
    void rootPageRendersInjectedTexts() {
        GuiPage root = ManagementGuiPages.rootPage(
                ManagementGuiActions.noop(), sentinelTexts());
        assertOnlySentinel(root);
    }

    @Test
    void detailsPageRendersInjectedTexts() {
        GuiPage details = ManagementGuiPages.detailsPage(
                model(), ManagementGuiActions.noop(), sentinelTexts());
        assertOnlySentinel(details);
        // The conflict row must carry the injected remedy lore.
        assertTrue(visibleText(details).contains(SENTINEL + "-remedy1:LAND_BINDING"));
    }

    @Test
    void unavailablePageRendersInjectedTexts() {
        GuiPage unavailable = ManagementGuiPages.detailsPage(
                ManagementGuiModel.unavailable(), ManagementGuiActions.noop(), sentinelTexts());
        assertOnlySentinel(unavailable);
    }
}
