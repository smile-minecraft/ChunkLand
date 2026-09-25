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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The second layer shows one land-default lore line per row, composed from
 * the injected {@code defaultLore} slot: a set default names its state,
 * an unset (INHERIT) default renders the unset wording. After a toggle the
 * same composition with the fresh lookup reflects the new state.
 */
class ManagementGuiDefaultLoreTest {

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
                        new Permission(action, PermissionState.DENY))))
                .build();
        return ManagementGuiModel.fromExplains(
                List.of(explainOf(action, PermissionState.DENY,
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
    void setDefaultRendersItsStateOnTheRow() {
        GuiPage details = ManagementGuiPages.detailsPage(model(ProtectionActionType.BLOCK_BREAK),
                ManagementGuiActions.noop(), ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.DENY);

        assertTrue(visibleText(details).contains(SENTINEL + "-default:BLOCK_BREAK:DENY"),
                "row lore must carry the set land default");
    }

    @Test
    void unsetDefaultRendersTheUnsetWording() {
        GuiPage details = ManagementGuiPages.detailsPage(model(ProtectionActionType.BLOCK_BREAK),
                ManagementGuiActions.noop(), ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.INHERIT);

        String visible = visibleText(details);
        assertTrue(visible.contains(SENTINEL + "-default:BLOCK_BREAK:null"),
                "INHERIT must render as the unset wording, got:\n" + visible);
        assertFalse(visible.toUpperCase(Locale.ROOT).contains("EVERYONE"));
    }

    @Test
    void loreFollowsTheFreshLookupAfterAToggle() {
        ManagementGuiModel rows = model(ProtectionActionType.BLOCK_BREAK);
        GuiPage before = ManagementGuiPages.detailsPage(rows,
                ManagementGuiActions.noop(), ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.INHERIT);
        GuiPage after = ManagementGuiPages.detailsPage(rows,
                ManagementGuiActions.noop(), ManagementGuiTextInjectionTest.sentinelTexts(),
                action -> PermissionState.DENY);

        assertTrue(visibleText(before).contains(SENTINEL + "-default:BLOCK_BREAK:null"));
        assertFalse(visibleText(before).contains(SENTINEL + "-default:BLOCK_BREAK:DENY"));
        assertTrue(visibleText(after).contains(SENTINEL + "-default:BLOCK_BREAK:DENY"));
    }

    @Test
    void legacyDetailsWithoutLookupCarriesNoDefaultLine() {
        GuiPage details = ManagementGuiPages.detailsPage(model(ProtectionActionType.BLOCK_BREAK),
                ManagementGuiActions.noop(), ManagementGuiTextInjectionTest.sentinelTexts());

        assertFalse(visibleText(details).contains(SENTINEL + "-default:"),
                "the legacy overload must not invent a default line");
    }
}
