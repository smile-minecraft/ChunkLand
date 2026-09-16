package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSpec;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainLayer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Bedrock 進階表單與 Java 共用同一份不可變模型語意：十三項能力對照表、
 * 主選單、權限明細、模式與玩家選擇選單、確認對話框。全部規格都不會有
 * EVERYONE 入口，變更一律留給既有 handler。
 */
class BedrockManageFormsTest {

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

    private static ManagementGuiModel modelWithConflict() {
        UUID holder = UUID.randomUUID();
        List<PermissionExplain> explains = List.of(
                explainOf(ProtectionActionType.BLOCK_BREAK,
                        PermissionState.ALLOW, PermissionExplainLayer.LAND_DEFAULT),
                explainOf(ProtectionActionType.CONTAINER_OPEN,
                        PermissionState.DENY, PermissionExplainLayer.LAND_BINDING));
        Map<ProtectionActionType, PermissionContext> contexts = new EnumMap<>(ProtectionActionType.class);
        contexts.put(ProtectionActionType.BLOCK_BREAK,
                landBindingContext(ProtectionActionType.BLOCK_BREAK, List.of()));
        contexts.put(ProtectionActionType.CONTAINER_OPEN,
                landBindingContext(ProtectionActionType.CONTAINER_OPEN, List.of(
                        binding(holder, ProtectionActionType.CONTAINER_OPEN, PermissionState.DENY),
                        binding(UUID.randomUUID(), ProtectionActionType.CONTAINER_OPEN,
                                PermissionState.ALLOW))));
        return ManagementGuiModel.fromExplains(explains, contexts);
    }

    private static void assertNoEveryone(String text, String what) {
        assertFalse(text.toUpperCase(java.util.Locale.ROOT).contains("EVERYONE"),
                what + " must never name an EVERYONE entry: " + text);
    }

    // ------------------------------------------------------------------
    // 十三項能力對照表
    // ------------------------------------------------------------------

    @Test
    void routesCoverExactlyThirteenCapabilities() {
        List<BedrockManageForms.CapabilityRoute> routes = BedrockManageForms.routes();

        assertEquals(13, routes.size(), "§67 lists thirteen capabilities, got: " + routes);
        assertEquals(13, routes.stream().map(BedrockManageForms.CapabilityRoute::capability)
                .distinct().count(), "every capability appears exactly once");
        for (BedrockManageForms.CapabilityRoute route : routes) {
            assertTrue(route.path() != null, "every route needs an explicit path: " + route);
            assertFalse(route.handlerSlot() == null || route.handlerSlot().isBlank(),
                    "every route names its existing handler slot: " + route);
            assertFalse(route.note() == null || route.note().isBlank(),
                    "every route explains its path: " + route);
            assertNoEveryone(route.handlerSlot(), "handler slot");
            assertNoEveryone(route.note(), "route note");
        }
    }

    @Test
    void everyRouteHasAnHonestPathKind() {
        Map<BedrockManageForms.Capability, BedrockManageForms.BedrockPath> paths = new EnumMap<>(
                BedrockManageForms.Capability.class);
        for (BedrockManageForms.CapabilityRoute route : BedrockManageForms.routes()) {
            paths.put(route.capability(), route.path());
        }
        assertEquals(BedrockManageForms.BedrockPath.EXISTING_FLOW, paths.get(
                BedrockManageForms.Capability.CLAIM), "claim keeps its tested Bedrock modal flow");
        assertEquals(BedrockManageForms.BedrockPath.EXISTING_FLOW, paths.get(
                BedrockManageForms.Capability.EXPAND));
        assertEquals(BedrockManageForms.BedrockPath.EXISTING_FLOW, paths.get(
                BedrockManageForms.Capability.SHRINK));
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM, paths.get(
                BedrockManageForms.Capability.BASIC_PERMISSION));
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM, paths.get(
                BedrockManageForms.Capability.TRUST_UNTRUST));
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM, paths.get(
                BedrockManageForms.Capability.BAN_UNBAN));
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM, paths.get(
                BedrockManageForms.Capability.INSPECT));
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM, paths.get(
                BedrockManageForms.Capability.EXPLAIN));
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM, paths.get(
                BedrockManageForms.Capability.DELETE));
        assertEquals(BedrockManageForms.BedrockPath.CHAT_DOWNGRADE, paths.get(
                BedrockManageForms.Capability.SUBLAND), "multi-step selection flow stays chat");
        assertEquals(BedrockManageForms.BedrockPath.CHAT_DOWNGRADE, paths.get(
                BedrockManageForms.Capability.GROUP));
        assertEquals(BedrockManageForms.BedrockPath.CHAT_DOWNGRADE, paths.get(
                BedrockManageForms.Capability.PROFILE));
        assertEquals(BedrockManageForms.BedrockPath.CHAT_DOWNGRADE, paths.get(
                BedrockManageForms.Capability.LAND_RULE),
                "no dedicated rule slot exists; the route must say so instead of faking success");
    }

    // ------------------------------------------------------------------
    // 主選單：十三顆按鈕，順序與對照表一致
    // ------------------------------------------------------------------

    @Test
    void rootMenuListsAllCapabilitiesInRouteOrder() {
        FormSpec.Simple menu = BedrockManageForms.rootMenu();
        List<BedrockManageForms.CapabilityRoute> routes = BedrockManageForms.routes();

        assertEquals(13, menu.buttons().size(), "root menu carries one button per capability");
        assertFalse(menu.title().isBlank());
        assertFalse(menu.content().isBlank());
        for (String button : menu.buttons()) {
            assertFalse(button.isBlank());
            assertNoEveryone(button, "menu button");
        }
        assertNoEveryone(menu.title(), "menu title");
        assertNoEveryone(menu.content(), "menu content");
        assertEquals(routes.size(), menu.buttons().size());
    }

    // ------------------------------------------------------------------
    // 權限明細：共用模型語意，無 EVERYONE
    // ------------------------------------------------------------------

    @Test
    void permissionDetailRendersSharedModelSemantics() {
        ManagementGuiModel model = modelWithConflict();
        assertTrue(model.available());

        FormSpec.Simple detail = BedrockManageForms.permissionDetail(model);

        assertEquals(model.rows().size() + 1, detail.buttons().size(),
                "one button per confirmed row plus Back");
        assertTrue(detail.content().contains("CONTAINER_OPEN: DENY @ LAND_BINDING"),
                "detail carries the final effect with its source layer: " + detail.content());
        assertTrue(detail.content().contains("BLOCK_BREAK: ALLOW @ LAND_DEFAULT"),
                "detail carries the final effect with its source layer: " + detail.content());
        assertTrue(detail.content().contains("!CONFLICT"),
                "conflict rows are marked: " + detail.content());
        assertTrue(detail.content().contains("DENY"),
                "conflict remedies stay visible: " + detail.content());
        assertNoEveryone(detail.content(), "detail content");
        for (String button : detail.buttons()) {
            assertNoEveryone(button, "detail button");
        }
        int back = BedrockManageForms.detailBackButton(detail);
        assertEquals(detail.buttons().size() - 1, back, "Back is always the last button");
        assertEquals("Back", detail.buttons().get(back));
    }

    @Test
    void unavailableModelYieldsFailClosedPageWithoutRowLeak() {
        FormSpec.Simple detail = BedrockManageForms.permissionDetail(ManagementGuiModel.unavailable());
        FormSpec.Simple nullDetail = BedrockManageForms.permissionDetail(null);

        for (FormSpec.Simple page : List.of(detail, nullDetail)) {
            assertEquals(1, page.buttons().size(), "fail-closed page carries only Back");
            assertEquals("Back", page.buttons().get(0));
            assertFalse(page.content().isBlank(), "fail-closed page explains itself");
            assertFalse(page.content().contains(" @ "),
                    "fail-closed page leaks no confirmed row: " + page.content());
        }
    }

    // ------------------------------------------------------------------
    // 模式與玩家選擇選單
    // ------------------------------------------------------------------

    @Test
    void trustAndBanModeMenusExposeBothDirectionsPlusBack() {
        FormSpec.Simple trust = BedrockManageForms.trustModeMenu();
        FormSpec.Simple ban = BedrockManageForms.banModeMenu();

        assertEquals(3, trust.buttons().size(), "trust, untrust, back");
        assertEquals(3, ban.buttons().size(), "ban, unban, back");
        assertEquals("Back", trust.buttons().get(2));
        assertEquals("Back", ban.buttons().get(2));
        assertNotEquals(trust.buttons().get(0), trust.buttons().get(1));
        assertNotEquals(ban.buttons().get(0), ban.buttons().get(1));
    }

    @Test
    void playerChoiceMenuListsOnlineNamesPlusBack() {
        FormSpec.Simple menu = BedrockManageForms.playerChoiceMenu(
                List.of("Alice", "Bob"), "Trust");

        assertEquals(3, menu.buttons().size(), "two names plus Back");
        assertTrue(menu.buttons().contains("Alice"));
        assertTrue(menu.buttons().contains("Bob"));
        assertEquals("Back", menu.buttons().get(2));
    }

    @Test
    void playerChoiceMenuFiltersReservedNamesAndFailsClosedWhenEmpty() {
        FormSpec.Simple filtered = BedrockManageForms.playerChoiceMenu(
                List.of("Alice", "EVERYONE", "*", "  ", "everyone"), "Trust");

        assertEquals(List.of("Alice", "Back"), filtered.buttons(),
                "reserved and blank names never become buttons");
        for (String button : filtered.buttons()) {
            assertNoEveryone(button, "choice button");
        }

        FormSpec.Simple empty = BedrockManageForms.playerChoiceMenu(List.of(), "Trust");
        FormSpec.Simple nullMenu = BedrockManageForms.playerChoiceMenu(null, "Trust");
        for (FormSpec.Simple page : List.of(empty, nullMenu)) {
            assertEquals(List.of("Back"), page.buttons(), "empty roster fails closed");
            assertFalse(page.content().isBlank());
        }
    }

    // ------------------------------------------------------------------
    // 確認對話框與回應解析
    // ------------------------------------------------------------------

    @Test
    void confirmModalCarriesDistinctButtons() {
        FormSpec.Modal modal = BedrockManageForms.confirmModal(
                "Delete land", "Delete Home with 3 chunks?", "Delete", "Keep");

        assertEquals(FormSpec.Kind.MODAL, modal.kind());
        assertFalse(modal.button1().isBlank());
        assertFalse(modal.button2().isBlank());
        assertNotEquals(modal.button1(), modal.button2());
    }

    @Test
    void clickedButtonOnlyTrustsValidResponses() {
        assertEquals(2, BedrockManageForms.clickedButton(
                new FormResponse(FormResponseStatus.VALID, 2, List.of())).orElseThrow());
        assertTrue(BedrockManageForms.clickedButton(
                new FormResponse(FormResponseStatus.CLOSED, null, List.of())).isEmpty());
        assertTrue(BedrockManageForms.clickedButton(
                new FormResponse(FormResponseStatus.INVALID, null, List.of())).isEmpty());
        assertTrue(BedrockManageForms.clickedButton(null).isEmpty());
        assertTrue(BedrockManageForms.clickedButton(
                new FormResponse(FormResponseStatus.VALID, null, List.of())).isEmpty());
    }

    @Test
    void buildersRejectBlankText() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockManageForms.confirmModal("  ", "content", "Yes", "No"));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockManageForms.playerChoiceMenu(List.of("Alice"), "  "));
    }
}
