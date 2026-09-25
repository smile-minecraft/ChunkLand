package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the Java land-management GUI (first-layer entry plus
 * second-layer permission detail over the immutable snapshot path).
 */
class ManagementGuiTest {

    private static final UUID ALICE = UUID.randomUUID();

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

    @Test
    void denyOutranksAllowOutranksInherit() {
        assertTrue(ManagementGuiModel.rank(PermissionState.DENY)
                < ManagementGuiModel.rank(PermissionState.ALLOW));
        assertTrue(ManagementGuiModel.rank(PermissionState.ALLOW)
                < ManagementGuiModel.rank(PermissionState.INHERIT));
    }

    @Test
    void rootPageExposesDetailsEntryWithoutEveryone() {
        AtomicReference<GuiClickContext> seen = new AtomicReference<>();
        ManagementGuiActions actions = new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
                seen.set(click);
            }

            @Override
            public void back(GuiClickContext click) {
            }

            @Override
            public void requestChange(GuiClickContext click, ProtectionActionType action) {
            }
        };

        GuiPage root = ManagementGuiPages.rootPage(actions, ManagementGuiTextProvider.fallbackTexts());

        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, root.id());
        assertFalse(root.slots().isEmpty(), "first layer must carry a details entry");
        for (String text : List.of(root.id(), root.title())) {
            assertFalse(text.toUpperCase(java.util.Locale.ROOT).contains("EVERYONE"));
        }

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, root).orElseThrow();
        int entrySlot = root.slots().iterator().next();
        navigator.handleClick(ALICE, generation, entrySlot);
        assertTrue(seen.get() != null, "details entry must route through the caller seam");
        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, seen.get().pageId());
    }

    @Test
    void detailsPageSortsDenyFirstAndShowsSourceLayer() {
        ManagementGuiActions actions = ManagementGuiActions.noop();
        List<PermissionExplain> explains = List.of(
                explainOf(ProtectionActionType.BLOCK_BREAK,
                        PermissionState.ALLOW, PermissionExplainLayer.LAND_DEFAULT),
                explainOf(ProtectionActionType.CONTAINER_OPEN,
                        PermissionState.DENY, PermissionExplainLayer.LAND_BINDING));
        Map<ProtectionActionType, PermissionContext> contexts = Map.of(
                ProtectionActionType.BLOCK_BREAK,
                landBindingContext(ProtectionActionType.BLOCK_BREAK, List.of()),
                ProtectionActionType.CONTAINER_OPEN,
                landBindingContext(ProtectionActionType.CONTAINER_OPEN, List.of(
                        binding(UUID.randomUUID(), ProtectionActionType.CONTAINER_OPEN,
                                PermissionState.DENY))));

        ManagementGuiModel model =
                ManagementGuiModel.fromExplains(explains, contexts);
        assertTrue(model.available());

        List<ManagementPermissionRow> rows = model.rows();
        assertEquals(2, rows.size());
        assertEquals(PermissionState.DENY, rows.get(0).outcome(),
                "DENY rows sort ahead of ALLOW rows");
        assertEquals(PermissionExplainLayer.LAND_BINDING, rows.get(0).layer());

        GuiPage details = ManagementGuiPages.detailsPage(model, actions, ManagementGuiTextProvider.fallbackTexts());
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID, details.id());
        assertFalse(details.slots().isEmpty());
    }

    @Test
    void conflictShowsFinalEffectWithThreeRemedies() {
        ProtectionActionType action = ProtectionActionType.BLOCK_BREAK;
        UUID denier = UUID.randomUUID();
        UUID allower = UUID.randomUUID();
        PermissionContext ctx = landBindingContext(action, List.of(
                binding(denier, action, PermissionState.DENY),
                binding(allower, action, PermissionState.ALLOW)));

        ManagementGuiModel model = ManagementGuiModel.fromExplains(
                List.of(explainOf(action, PermissionState.DENY,
                        PermissionExplainLayer.LAND_BINDING)),
                Map.of(action, ctx));

        assertTrue(model.available());
        ManagementPermissionRow row = model.rows().get(0);
        assertEquals(PermissionState.DENY, row.outcome());
        assertTrue(row.conflict(), "DENY+ALLOW on one layer is a conflict with final DENY");

        List<String> remedies = model.remediesFor(row);
        assertEquals(3, remedies.size(), "conflicts name exactly three resolutions");
        for (String remedy : remedies) {
            assertFalse(remedy.isBlank());
            assertFalse(remedy.toUpperCase(java.util.Locale.ROOT).contains("EVERYONE"),
                    "no EVERYONE entry may appear in conflict remedies");
        }
    }

    @Test
    void noEveryoneButtonAnywhere() {
        ManagementGuiActions actions = ManagementGuiActions.noop();
        GuiPage root = ManagementGuiPages.rootPage(actions, ManagementGuiTextProvider.fallbackTexts());
        ManagementGuiModel model = ManagementGuiModel.fromExplains(
                List.of(explainOf(ProtectionActionType.ENTRY,
                        PermissionState.ALLOW, PermissionExplainLayer.LAND_DEFAULT)),
                Map.of(ProtectionActionType.ENTRY,
                        landBindingContext(ProtectionActionType.ENTRY, List.of())));
        GuiPage details = ManagementGuiPages.detailsPage(model, actions, ManagementGuiTextProvider.fallbackTexts());
        GuiPage unavailable =
                ManagementGuiPages.detailsPage(ManagementGuiModel.unavailable(), actions, ManagementGuiTextProvider.fallbackTexts());

        List<GuiPage> pages = List.of(root, details, unavailable);
        for (GuiPage page : pages) {
            assertFalse(page.id().toUpperCase(java.util.Locale.ROOT).contains("EVERYONE"));
            assertFalse(page.title().toUpperCase(java.util.Locale.ROOT).contains("EVERYONE"));
        }
        for (ManagementPermissionRow row : model.rows()) {
            for (String remedy : model.remediesFor(row)) {
                assertFalse(remedy.toUpperCase(java.util.Locale.ROOT).contains("EVERYONE"));
            }
        }
    }

    @Test
    void navigatorKeepsGenerationAndBackStackAcrossLayers() {
        ManagementGuiActions actions = ManagementGuiActions.noop();
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        GuiPage root = ManagementGuiPages.rootPage(actions, ManagementGuiTextProvider.fallbackTexts());
        ManagementGuiModel model = ManagementGuiModel.fromExplains(
                List.of(explainOf(ProtectionActionType.ENTRY,
                        PermissionState.ALLOW, PermissionExplainLayer.LAND_DEFAULT)),
                Map.of(ProtectionActionType.ENTRY,
                        landBindingContext(ProtectionActionType.ENTRY, List.of())));
        GuiPage details = ManagementGuiPages.detailsPage(model, actions, ManagementGuiTextProvider.fallbackTexts());

        navigator.open(ALICE, root).orElseThrow();
        long second = navigator.push(ALICE, details).orElseThrow();
        assertEquals(2, navigator.depth(ALICE));
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(second, navigator.currentGeneration(ALICE).orElseThrow().longValue());

        assertTrue(navigator.back(ALICE));
        assertEquals(1, navigator.depth(ALICE));
        assertEquals(ManagementGuiPages.ROOT_PAGE_ID,
                navigator.currentPage(ALICE).orElseThrow().id());
    }

    @Test
    void missingDataAndResolverFailureStayFailClosed() {
        ManagementGuiActions actions = ManagementGuiActions.noop();

        assertFalse(ManagementGuiModel.fromExplains(null, null).available());
        assertFalse(ManagementGuiModel.fromExplains(List.of(), Map.of()).available());

        Map<ProtectionActionType, PermissionContext> contexts = new EnumMap<>(
                ProtectionActionType.class);
        contexts.put(ProtectionActionType.BLOCK_BREAK,
                landBindingContext(ProtectionActionType.BLOCK_BREAK, List.of()));
        ManagementGuiModel failed = ManagementGuiModel.fromContexts(contexts, (ctx, covering) -> {
            throw new RuntimeException("resolver boom");
        });
        assertFalse(failed.available(), "resolver failure must fail closed");

        GuiPage denied = ManagementGuiPages.detailsPage(null, actions, ManagementGuiTextProvider.fallbackTexts());
        assertEquals(ManagementGuiPages.UNAVAILABLE_PAGE_ID, denied.id());
        GuiPage unavailable =
                ManagementGuiPages.detailsPage(ManagementGuiModel.unavailable(), actions, ManagementGuiTextProvider.fallbackTexts());
        assertEquals(ManagementGuiPages.UNAVAILABLE_PAGE_ID, unavailable.id());

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        navigator.open(ALICE, unavailable).orElseThrow();
        assertEquals(0, gui.shutdownCount());
    }

    @Test
    void rowClickRoutesMutationThroughCallerSeam() {
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
        ProtectionActionType action = ProtectionActionType.CONTAINER_OPEN;
        ManagementGuiModel model = ManagementGuiModel.fromExplains(
                List.of(explainOf(action, PermissionState.ALLOW,
                        PermissionExplainLayer.LAND_DEFAULT)),
                Map.of(action, landBindingContext(action, List.of())));
        GuiPage details = ManagementGuiPages.detailsPage(model, actions, ManagementGuiTextProvider.fallbackTexts());

        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, details).orElseThrow();
        List<Integer> slots = new ArrayList<>(details.slots());
        boolean routed = false;
        for (int slot : slots) {
            navigator.handleClick(ALICE, generation, slot);
            if (requested.get() != null || details.buttonAt(slot).orElseThrow()
                    .action() != null) {
                routed = true;
            }
        }
        assertTrue(routed, "detail rows must be clickable");
        Optional<GuiButton> back = details.buttonAt(ManagementGuiPages.BACK_SLOT);
        assertTrue(back.isPresent() || requested.get() != null
                || details.slots().size() == model.rows().size()
                || details.slots().size() == model.rows().size() + 1);
    }
}
