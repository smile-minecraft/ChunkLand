package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The confirm page shows the pending transition (current state and target
 * state) and routes its three buttons to three distinct callbacks. A failed
 * attempt re-renders the same page with the injected failure line and still
 * writes nothing by itself.
 */
class ManagementGuiConfirmPageTest {

    private static final String SENTINEL = ManagementGuiTextInjectionTest.SENTINEL;

    private record Clicks(AtomicReference<GuiClickContext> confirm,
            AtomicReference<GuiClickContext> cancel, AtomicReference<GuiClickContext> back) {
    }

    private static Clicks clicks() {
        return new Clicks(new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>());
    }

    private static ManagementGuiPages.ConfirmRequest request(Clicks clicks, boolean failed) {
        return new ManagementGuiPages.ConfirmRequest(ProtectionActionType.BLOCK_BREAK,
                null, "DENY",
                click -> clicks.confirm().set(click),
                click -> clicks.cancel().set(click),
                click -> clicks.back().set(click),
                failed);
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
    void confirmPageShowsActionCurrentAndTarget() {
        GuiPage page = ManagementGuiPages.confirmPage(request(clicks(), false),
                ManagementGuiTextInjectionTest.sentinelTexts());

        assertEquals(ManagementGuiPages.CONFIRM_PAGE_ID, page.id());
        String visible = visibleText(page);
        assertTrue(visible.contains(SENTINEL + "-confirm:BLOCK_BREAK"),
                "title must carry the action, got:\n" + visible);
        assertTrue(visible.contains(SENTINEL + "-default:BLOCK_BREAK:null"),
                "page must show the current (unset) default line, got:\n" + visible);
        assertTrue(visible.contains(SENTINEL + "-target:DENY"),
                "page must show the target state line, got:\n" + visible);
        assertFalse(visible.toUpperCase(Locale.ROOT).contains("EVERYONE"));
    }

    @Test
    void threeButtonsRouteToThreeDistinctCallbacks() {
        Clicks seen = clicks();
        GuiPage page = ManagementGuiPages.confirmPage(request(seen, false),
                ManagementGuiTextInjectionTest.sentinelTexts());

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        UUID viewer = UUID.randomUUID();
        long generation = navigator.open(viewer, page).orElseThrow();

        navigator.handleClick(viewer, generation, ManagementGuiPages.CONFIRM_SLOT);
        navigator.handleClick(viewer, generation, ManagementGuiPages.CANCEL_SLOT);
        navigator.handleClick(viewer, generation, ManagementGuiPages.BACK_SLOT);

        assertTrue(seen.confirm().get() != null, "confirm slot must route to onConfirm");
        assertTrue(seen.cancel().get() != null, "cancel slot must route to onCancel");
        assertTrue(seen.back().get() != null, "back slot must route to onBack");
        assertEquals(ManagementGuiPages.CONFIRM_SLOT, seen.confirm().get().slot());
        assertEquals(ManagementGuiPages.CANCEL_SLOT, seen.cancel().get().slot());
        assertEquals(ManagementGuiPages.BACK_SLOT, seen.back().get().slot());
    }

    @Test
    void buttonNamesFollowInjectedTexts() {
        GuiPage page = ManagementGuiPages.confirmPage(request(clicks(), false),
                ManagementGuiTextInjectionTest.sentinelTexts());

        List<String> names = new ArrayList<>();
        for (GuiPage.RenderItem item : page.renderItems()) {
            names.add(item.name());
        }
        assertTrue(names.contains(SENTINEL + "-yes"), "confirm name must surface");
        assertTrue(names.contains(SENTINEL + "-no"), "cancel name must surface");
        assertTrue(names.contains(SENTINEL + "-confirm-back"), "back name must surface");
    }

    @Test
    void failedRenderAddsTheFailureLineWithoutWriting() {
        GuiPage clean = ManagementGuiPages.confirmPage(request(clicks(), false),
                ManagementGuiTextInjectionTest.sentinelTexts());
        assertFalse(visibleText(clean).contains(SENTINEL + "-failed"));

        GuiPage failed = ManagementGuiPages.confirmPage(request(clicks(), true),
                ManagementGuiTextInjectionTest.sentinelTexts());
        assertEquals(ManagementGuiPages.CONFIRM_PAGE_ID, failed.id());
        assertTrue(visibleText(failed).contains(SENTINEL + "-failed"),
                "failed render must surface the injected failure line");
    }

    @Test
    void nullCallbacksStayFailClosed() {
        ManagementGuiPages.ConfirmRequest bare = new ManagementGuiPages.ConfirmRequest(
                ProtectionActionType.BLOCK_BREAK, null, "DENY", null, null, null, false);
        GuiPage page = ManagementGuiPages.confirmPage(bare,
                ManagementGuiTextInjectionTest.sentinelTexts());

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        UUID viewer = UUID.randomUUID();
        long generation = navigator.open(viewer, page).orElseThrow();
        navigator.handleClick(viewer, generation, ManagementGuiPages.CONFIRM_SLOT);
        navigator.handleClick(viewer, generation, ManagementGuiPages.CANCEL_SLOT);
        navigator.handleClick(viewer, generation, ManagementGuiPages.BACK_SLOT);
    }
}
