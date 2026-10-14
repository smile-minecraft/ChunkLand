package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import com.smile.chunkland.adapter.gui.ManagementRosterController;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.gui.BedrockFormNavigator;
import com.smile.chunkland.gui.BedrockFormTexts;
import com.smile.chunkland.gui.BedrockManageForms;
import com.smile.chunkland.gui.GuiClickContext;
import com.smile.chunkland.gui.GuiNavigator;
import com.smile.chunkland.gui.GuiPage;
import com.smile.chunkland.gui.ManagementGuiModel;
import com.smile.chunkland.gui.ManagementRosterPages;
import com.smile.chunkland.gui.ManagementRosterTexts;
import com.smile.chunkland.message.ConfirmClick;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.protection.DirectTrustWhitelist;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.rule.LandRuleView;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * 基岩管理表單的完整能力：四條降級能力與信任封鎖的離線名稱輸入，
 * 全部走與 Java 版相同的共用操作，每個回應最多寫入一次，
 * 送出時重新確認，返回、取消、翻頁、空清單與錯誤畫面齊全，
 * 刪除類有明確確認，沒有聊天可點擊元件專屬操作。
 */
class BedrockManageCompletionTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID OFFLINE_BOB = UUID.randomUUID();

    // ------------------------------------------------------------------
    // 共用假裝置
    // ------------------------------------------------------------------

    static final class FakeSender implements BedrockFormNavigator.FormSender {
        final AtomicLong sends = new AtomicLong();
        volatile FormSendResult next = FormSendResult.SENT;
        volatile FormSpec lastSpec;
        volatile Consumer<FormResponse> lastCallback;

        @Override
        public FormSendResult send(UUID playerId, FormSpec spec, Consumer<FormResponse> callback) {
            sends.incrementAndGet();
            lastSpec = spec;
            lastCallback = callback;
            return next;
        }

        void fire(FormResponse response) {
            lastCallback.accept(response);
        }
    }

    static final class RecordingSink implements ReplySink {
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> vars = new CopyOnWriteArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> varMap) {
            keys.add(messageKey);
            vars.add(Map.copyOf(varMap));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> varMap, java.util.Locale locale) {
            reply(messageKey, varMap);
        }
    }

    static final class RecordingHandler implements LandCommand.Handler {
        final AtomicLong calls = new AtomicLong();
        volatile String[] lastArgs;

        @Override
        public void handle(org.bukkit.command.CommandSender sender, String[] args, ReplySink sink) {
            calls.incrementAndGet();
            lastArgs = args == null ? null : args.clone();
        }
    }

    static final class FakeLookup implements BedrockManageFormHandler.BedrockLookup {
        volatile boolean bedrock = true;

        @Override
        public boolean isBedrock(UUID playerId) {
            return bedrock;
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "BedrockPlayer";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "BedrockPlayer-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static LandSnapshot playerLand(LandId id, UUID owner) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static ManagementGateResolver.Request ownerRequest() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        return new ManagementGateResolver.Request(OWNER, id, snapshot, false, false, provider);
    }

    private static ManagementGateResolver fixed(ManagementGateResolver.Request request) {
        return (sender, action, args) -> Optional.ofNullable(request);
    }

    /** 只有指定節點的玩家；空集合即節點全被撤銷。 */
    private static Player playerWithNodes(UUID id, Set<String> nodes) {
        Set<String> granted = nodes == null ? Set.of() : Set.copyOf(nodes);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        Object raw = args == null || args.length == 0 ? null : args[0];
                        return raw instanceof String node && granted.contains(node);
                    }
                    if (name.equals("getName")) {
                        return "BedrockPlayer";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "BedrockPlayer-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static FormResponse valid(int button) {
        return new FormResponse(FormResponseStatus.VALID, button, List.of());
    }

    private static FormResponse textInput(String text) {
        return new FormResponse(FormResponseStatus.VALID, null, List.of(new FormValue.Text(text)));
    }

    private static int rootIndexOf(BedrockManageForms.Capability capability) {
        List<BedrockManageForms.CapabilityRoute> routes = BedrockManageForms.routes();
        for (int index = 0; index < routes.size(); index++) {
            if (routes.get(index).capability() == capability) {
                return index;
            }
        }
        throw new IllegalStateException("no route for " + capability);
    }

    /** 測試用的名單控制器：與正式接線同一入口，記憶體解析 only。 */
    static final class RosterFixture {
        final AtomicBoolean gateOpen = new AtomicBoolean(true);
        final AtomicBoolean online = new AtomicBoolean(true);
        final AtomicBoolean landKnown = new AtomicBoolean(true);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final List<String> mutations = new CopyOnWriteArrayList<>();
        final List<String> tells = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> tellVars = new CopyOnWriteArrayList<>();
        final ManagementRosterController controller;
        final Map<String, UUID> knownOffline = new HashMap<>();

        RosterFixture() {
            knownOffline.put("OfflineBob", OFFLINE_BOB);
            Function<String, Optional<UUID>> onlineIds = name -> {
                if ("Alice".equals(name)) {
                    return Optional.of(ALICE);
                }
                return Optional.empty();
            };
            OfflinePlayerResolver resolver = new OfflinePlayerResolver(
                    onlineIds, name -> Optional.ofNullable(knownOffline.get(name)),
                    Runnable::run);
            RosterChangeOperation operation = new RosterChangeOperation(
                    onlineIds, resolver, PlayerScheduler.direct(),
                    (viewer, landId, slot) -> gateOpen.get(),
                    viewer -> online.get(),
                    landId -> landKnown.get(),
                    () -> alive.get(),
                    null,
                    slot -> (actor, landId, target) -> {
                        mutations.add(slot.key() + ":" + target);
                        return CompletableFuture.completedFuture(null);
                    });
            ManagementRosterController.Navigation navigation =
                    new ManagementRosterController.Navigation() {
                        @Override
                        public GuiNavigator navigator() {
                            return null;
                        }

                        @Override
                        public void render(UUID viewer, long generation, GuiPage page) {
                        }

                        @Override
                        public void back(GuiClickContext click) {
                        }
                    };
            ManagementRosterController.Rosters rosters =
                    new ManagementRosterController.Rosters() {
                        @Override
                        public Optional<Set<UUID>> members(LandId landId) {
                            return Optional.of(Set.of());
                        }

                        @Override
                        public Optional<Set<UUID>> bans(LandId landId) {
                            return Optional.of(Set.of());
                        }

                        @Override
                        public UUID ownerOf(LandId landId) {
                            return OWNER;
                        }
                    };
            ManagementRosterController.People people =
                    new ManagementRosterController.People() {
                        @Override
                        public String nameOf(UUID playerId) {
                            if (ALICE.equals(playerId)) {
                                return "Alice";
                            }
                            if (OFFLINE_BOB.equals(playerId)) {
                                return "OfflineBob";
                            }
                            return null;
                        }

                        @Override
                        public List<ManagementRosterPages.Entry> online() {
                            return List.of(new ManagementRosterPages.Entry(ALICE, "Alice"));
                        }
                    };
            controller = new ManagementRosterController(
                    navigation, rosters, (viewer, landId, slot) -> gateOpen.get(),
                    new ManagementRosterController.Changes(null, null, null, null),
                    people, (viewer, key, vars) -> {
                        tells.add(key);
                        tellVars.add(Map.copyOf(vars));
                    },
                    (viewer, task) -> task.run(),
                    viewer -> ManagementRosterTexts.english(),
                    operation, viewer -> player(viewer));
        }
    }

    static final class Fixture {
        final FakeSender sender = new FakeSender();
        final RecordingSink sink = new RecordingSink();
        final Map<String, RecordingHandler> recorders = new HashMap<>();
        final RosterFixture roster = new RosterFixture();
        final AtomicBoolean presence = new AtomicBoolean(true);
        final AtomicBoolean alive = new AtomicBoolean(true);
        volatile List<LandRuleView> ruleViews;
        volatile PermissionState defaultCurrent = PermissionState.DENY;
        volatile PermissionState ruleCurrent = PermissionState.INHERIT;
        volatile BedrockManageFormHandler.SublandTokens tokens =
                new BedrockManageFormHandler.SublandTokens(7L, 3L);
        ManagementGateResolver resolver;

        Fixture() {
            for (String slot : List.of("trust", "untrust", "ban", "unban", "inspect",
                    "explain", "delete", "binding", "default", "rule", "group",
                    "profile", "subland")) {
                recorders.put(slot, new RecordingHandler());
            }
            List<LandRuleView> views = new ArrayList<>();
            for (LandRuleType rule : LandRuleType.values()) {
                views.add(new LandRuleView(rule, PermissionState.INHERIT,
                        PermissionState.DENY, LandRuleView.Source.BUILT_IN));
            }
            ruleViews = views;
            resolver = fixed(ownerRequest());
        }

        Map<String, LandCommand.Handler> handlerMap() {
            Map<String, LandCommand.Handler> map = new HashMap<>();
            map.putAll(recorders);
            return map;
        }

        BedrockManageFormHandler handler() {
            Map<ProtectionActionType, PermissionState> defaults = new EnumMap<>(
                    ProtectionActionType.class);
            PermissionState pinned = defaultCurrent;
            defaults.put(ProtectionActionType.BLOCK_BREAK, pinned);
            return new BedrockManageFormHandler(
                    new FakeLookup(), resolver,
                    (actor, landId) -> BedrockManageCompletionTest.twoRowModel(),
                    handlerMap(),
                    new BedrockFormNavigator(sender),
                    () -> List.of("Alice"),
                    new BedrockClaimFormHandlerTest.ImmediateScheduler(),
                    null,
                    roster.controller,
                    landId -> Optional.ofNullable(ruleViews),
                    (actor, landId, action) -> Optional.ofNullable(defaultCurrent),
                    (landId, rule) -> ruleCurrent == null ? Optional.empty()
                            : Optional.of(new LandRuleCommandHandler.Baseline(
                                    ruleCurrent, 11L)),
                    actor -> Optional.ofNullable(tokens),
                    viewer -> presence.get(),
                    () -> alive.get());
        }

        void openRoot() {
            openRootAs(player(OWNER));
        }

        void openRootAs(Player who) {
            handler().handle(who, new String[] {"manage"}, sink);
            assertEquals(1, sender.sends.get(), "root menu must open");
        }

        long totalCalls() {
            return recorders.values().stream().mapToLong(recorder -> recorder.calls.get()).sum();
        }
    }

    private static ManagementGuiModel twoRowModel() {
        com.smile.chunkland.protection.PermissionExplain first =
                new com.smile.chunkland.protection.PermissionExplain(
                        ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW,
                        ProtectionActionType.BLOCK_BREAK.decisionSource(),
                        com.smile.chunkland.protection.PermissionExplainLayer.LAND_DEFAULT,
                        "test", null, false, false, false);
        com.smile.chunkland.protection.PermissionExplain second =
                new com.smile.chunkland.protection.PermissionExplain(
                        ProtectionActionType.CONTAINER_OPEN, PermissionState.DENY,
                        ProtectionActionType.CONTAINER_OPEN.decisionSource(),
                        com.smile.chunkland.protection.PermissionExplainLayer.LAND_BINDING,
                        "test", null, false, false, false);
        Map<ProtectionActionType, com.smile.chunkland.api.permission.PermissionContext> contexts =
                new EnumMap<>(ProtectionActionType.class);
        contexts.put(ProtectionActionType.BLOCK_BREAK,
                com.smile.chunkland.api.permission.PermissionContext.builder(
                        ProtectionActionType.BLOCK_BREAK).build());
        return ManagementGuiModel.fromExplains(List.of(first, second), contexts);
    }

    // ------------------------------------------------------------------
    // 對照表：四條降級能力全部改走真正的表單路徑
    // ------------------------------------------------------------------

    @Test
    void routesExposeRealFormPathsForAllDowngradedCapabilities() {
        Map<BedrockManageForms.Capability, BedrockManageForms.CapabilityRoute> byCapability =
                new EnumMap<>(BedrockManageForms.Capability.class);
        for (BedrockManageForms.CapabilityRoute route : BedrockManageForms.routes()) {
            byCapability.put(route.capability(), route);
        }
        for (BedrockManageForms.Capability capability : List.of(
                BedrockManageForms.Capability.SUBLAND,
                BedrockManageForms.Capability.GROUP,
                BedrockManageForms.Capability.PROFILE,
                BedrockManageForms.Capability.LAND_RULE)) {
            assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM,
                    byCapability.get(capability).path(),
                    capability + " must have a real form path, not a chat downgrade");
        }
        assertEquals("rule", byCapability.get(BedrockManageForms.Capability.LAND_RULE)
                .handlerSlot(), "the rule route names the rule slot, not the binding slot");
        assertEquals("subland",
                byCapability.get(BedrockManageForms.Capability.SUBLAND).handlerSlot());
        assertEquals("group",
                byCapability.get(BedrockManageForms.Capability.GROUP).handlerSlot());
        assertEquals("profile",
                byCapability.get(BedrockManageForms.Capability.PROFILE).handlerSlot());
    }

    // ------------------------------------------------------------------
    // 信任與封鎖：表單輸入已知的離線名稱可以完成
    // ------------------------------------------------------------------

    @Test
    void trustFormInputWithKnownOfflineNameWritesThroughSharedController() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.TRUST_UNTRUST)));
        // 模式選單：信任。
        fixture.sender.fire(valid(0));
        // 名單頁現在附帶名稱輸入入口。
        FormSpec choice = fixture.sender.lastSpec;
        assertTrue(choice instanceof FormSpec.Simple);
        int inputButton = ((FormSpec.Simple) choice).buttons().size() - 2;
        fixture.sender.fire(valid(inputButton));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Custom,
                "the name entry must be a Custom form with a text input");
        fixture.sender.fire(textInput("OfflineBob"));

        assertEquals(List.of("trust:" + OFFLINE_BOB), fixture.roster.mutations,
                "the offline name must flow into the shared roster operation");
        assertEquals(0, fixture.totalCalls(),
                "roster writes go through the shared controller, not the chat slots");
    }

    @Test
    void trustFormInputWithUnknownNameIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.TRUST_UNTRUST)));
        fixture.sender.fire(valid(0));
        FormSpec choice = fixture.sender.lastSpec;
        int inputButton = ((FormSpec.Simple) choice).buttons().size() - 2;
        fixture.sender.fire(valid(inputButton));
        fixture.sender.fire(textInput("NobodyEverPlayed"));

        assertTrue(fixture.roster.mutations.isEmpty(),
                "unknown names must never reach the mutation");
        assertTrue(fixture.roster.tells.contains("command.land.trust.failed"),
                "unknown names are reported, not silently dropped: " + fixture.roster.tells);
        assertTrue(fixture.roster.tellVars.stream().anyMatch(
                vars -> "trust.unknown_target".equals(vars.get("reason"))),
                "unknown names carry the unknown_target reason: " + fixture.roster.tellVars);
    }

    @Test
    void banFormInputWithKnownOfflineNameWritesThroughSharedController() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.BAN_UNBAN)));
        fixture.sender.fire(valid(0));
        FormSpec choice = fixture.sender.lastSpec;
        int inputButton = ((FormSpec.Simple) choice).buttons().size() - 2;
        fixture.sender.fire(valid(inputButton));
        fixture.sender.fire(textInput("OfflineBob"));

        assertEquals(List.of("ban:" + OFFLINE_BOB), fixture.roster.mutations);
    }

    @Test
    void onlineChoiceTapStillWritesThroughSharedController() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.TRUST_UNTRUST)));
        fixture.sender.fire(valid(0));
        // 線上名單第一位就是 Alice。
        fixture.sender.fire(valid(0));

        assertEquals(List.of("trust:" + ALICE), fixture.roster.mutations);
        assertEquals(0, fixture.recorders.get("trust").calls.get());
    }

    @Test
    void blankNameInputShowsErrorWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.TRUST_UNTRUST)));
        fixture.sender.fire(valid(0));
        FormSpec choice = fixture.sender.lastSpec;
        int inputButton = ((FormSpec.Simple) choice).buttons().size() - 2;
        fixture.sender.fire(valid(inputButton));
        fixture.sender.fire(textInput("   "));

        assertTrue(fixture.roster.mutations.isEmpty());
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Simple,
                "blank input returns to an explanatory page, not silence");
    }

    // ------------------------------------------------------------------
    // 基本權限：明細列確認後走 default 共用操作
    // ------------------------------------------------------------------

    @Test
    void permissionRowConfirmWritesDefaultThroughSharedSlot() {
        Fixture fixture = new Fixture();
        fixture.defaultCurrent = PermissionState.DENY;
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.BASIC_PERMISSION)));
        // 找到 BLOCK_BREAK 那一列（列順序由共用模型決定，不寫死位置）。
        FormSpec.Simple detail = (FormSpec.Simple) fixture.sender.lastSpec;
        ManagementGuiModel model = twoRowModel();
        int row = -1;
        for (int index = 0; index < model.rows().size(); index++) {
            if (model.rows().get(index).action() == ProtectionActionType.BLOCK_BREAK) {
                row = index;
                break;
            }
        }
        assertTrue(row >= 0, "BLOCK_BREAK must appear in the detail");
        fixture.sender.fire(valid(row));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Modal,
                "permission changes need an explicit confirm step");
        fixture.sender.fire(valid(0));

        assertEquals(1, fixture.recorders.get("default").calls.get());
        assertArrayEquals(
                new String[] {"default", "BLOCK_BREAK", "ALLOW"},
                fixture.recorders.get("default").lastArgs,
                "DENY toggles to ALLOW, exactly like the Java GUI confirm path");
    }

    @Test
    void permissionRowConfirmCancelWritesNothing() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.BASIC_PERMISSION)));
        fixture.sender.fire(valid(0));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Modal);
        fixture.sender.fire(valid(1));

        assertEquals(0, fixture.totalCalls(), "cancel never submits");
    }

    // ------------------------------------------------------------------
    // 環境規則：十一條清單加三態確認，走 rule 共用操作
    // ------------------------------------------------------------------

    @Test
    void ruleListConfirmWritesRuleThroughSharedSlot() {
        Fixture fixture = new Fixture();
        fixture.ruleCurrent = PermissionState.INHERIT;
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Simple);
        FormSpec.Simple list = (FormSpec.Simple) fixture.sender.lastSpec;
        assertTrue(list.buttons().size() > 2, "the rule list shows rows plus navigation");
        // 第一列就是第一條規則。
        fixture.sender.fire(valid(0));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Simple,
                "rule changes need an explicit three-state confirm step");
        fixture.sender.fire(valid(BedrockManageForms.RULE_ALLOW_BUTTON));

        assertEquals(1, fixture.recorders.get("rule").calls.get());
        String expectedRule = LandRuleType.values()[0].name();
        assertArrayEquals(new String[] {"rule", expectedRule, "ALLOW"},
                fixture.recorders.get("rule").lastArgs,
                "Bedrock submits the same args the chat command would");
    }

    @Test
    void ruleChatAndFormReachTheSameSlotWithTheSameArgs() {
        Fixture fixture = new Fixture();
        RecordingHandler rule = fixture.recorders.get("rule");
        Player sender = player(OWNER);

        rule.handle(sender, new String[] {"rule", "PVP", "DENY"}, fixture.sink);
        String[] chatArgs = rule.lastArgs.clone();

        fixture.openRoot();
        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        // 找到 PVP 那一列。
        FormSpec.Simple list = (FormSpec.Simple) fixture.sender.lastSpec;
        int row = -1;
        LandRuleType[] declared = LandRuleType.values();
        for (int index = 0; index < declared.length && index < list.buttons().size(); index++) {
            if (list.buttons().get(index).contains("PVP")) {
                row = index;
                break;
            }
        }
        assertTrue(row >= 0, "PVP must appear in the rule list");
        fixture.sender.fire(valid(row));
        fixture.sender.fire(valid(BedrockManageForms.RULE_DENY_BUTTON));

        assertArrayEquals(chatArgs, rule.lastArgs,
                "the same rule set from chat and from Bedrock reaches the slot identically");
    }

    @Test
    void ruleListPagesTurnWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        FormSpec.Simple first = (FormSpec.Simple) fixture.sender.lastSpec;
        int totalButtons = first.buttons().size();
        // 十一條規則以每頁八條呈現：八列加下一頁加返回。
        assertEquals(10, totalButtons);
        fixture.sender.fire(valid(8));

        FormSpec.Simple second = (FormSpec.Simple) fixture.sender.lastSpec;
        assertEquals(5, second.buttons().size(), "three rows plus Previous plus Back");
        assertEquals(0, fixture.totalCalls(), "paging never submits");
        // 上一頁回到第一頁。
        fixture.sender.fire(valid(3));
        FormSpec.Simple back = (FormSpec.Simple) fixture.sender.lastSpec;
        assertEquals(10, back.buttons().size());
        assertEquals(0, fixture.totalCalls());
    }

    @Test
    void ruleEmptyViewsShowUnavailableWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.ruleViews = List.of();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));

        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Simple);
        assertEquals(1, ((FormSpec.Simple) fixture.sender.lastSpec).buttons().size(),
                "missing rule data fails closed to a single-Back page");
        assertEquals(0, fixture.totalCalls());
    }

    // ------------------------------------------------------------------
    // 子領地：走同一個 subland 槽位，刪除有確認
    // ------------------------------------------------------------------

    @Test
    void sublandCreateWritesThroughSharedSlotWithLiveTokens() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.SUBLAND)));
        fixture.sender.fire(valid(BedrockManageForms.SUBLAND_CREATE));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Custom);
        fixture.sender.fire(textInput("Shop"));

        assertEquals(1, fixture.recorders.get("subland").calls.get());
        assertArrayEquals(new String[] {"subland", "create", "7", "3", "Shop"},
                fixture.recorders.get("subland").lastArgs,
                "Bedrock submits live session tokens exactly like the chat preview command");
    }

    @Test
    void sublandDeleteHasConfirmStep() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.SUBLAND)));
        fixture.sender.fire(valid(BedrockManageForms.SUBLAND_DELETE));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Modal,
                "subland deletion needs an explicit confirm step");
        fixture.sender.fire(valid(0));

        assertEquals(1, fixture.recorders.get("subland").calls.get());
        assertArrayEquals(new String[] {"subland", "delete", "7", "3"},
                fixture.recorders.get("subland").lastArgs);
    }

    @Test
    void sublandDeleteCancelWritesNothing() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.SUBLAND)));
        fixture.sender.fire(valid(BedrockManageForms.SUBLAND_DELETE));
        fixture.sender.fire(valid(1));

        assertEquals(0, fixture.totalCalls());
    }

    @Test
    void sublandWithoutSelectionShowsEmptyPageWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.tokens = null;
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.SUBLAND)));
        fixture.sender.fire(valid(BedrockManageForms.SUBLAND_CREATE));
        fixture.sender.fire(textInput("Shop"));

        assertEquals(0, fixture.totalCalls(), "no live selection means no submit");
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Simple);
    }

    // ------------------------------------------------------------------
    // 群組：走同一個 group 槽位，刪除有確認與強制選項
    // ------------------------------------------------------------------

    @Test
    void groupCreateWritesThroughSharedSlot() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        fixture.sender.fire(valid(BedrockManageForms.GROUP_CREATE));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Custom);
        fixture.sender.fire(textInput("builders"));

        assertEquals(1, fixture.recorders.get("group").calls.get());
        assertArrayEquals(new String[] {"group", "create", "builders"},
                fixture.recorders.get("group").lastArgs);
    }

    @Test
    void groupAddWritesThroughSharedSlot() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        fixture.sender.fire(valid(BedrockManageForms.GROUP_ADD));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Custom);
        fixture.sender.fire(new FormResponse(FormResponseStatus.VALID, null, List.of(
                new FormValue.Text("builders"), new FormValue.Text("Alice"))));

        assertEquals(1, fixture.recorders.get("group").calls.get());
        assertArrayEquals(new String[] {"group", "add", "builders", "Alice"},
                fixture.recorders.get("group").lastArgs);
    }

    @Test
    void groupDeleteForceWritesWithFlagAfterConfirm() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        fixture.sender.fire(valid(BedrockManageForms.GROUP_DELETE));
        fixture.sender.fire(textInput("builders"));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Simple,
                "group deletion offers delete, force delete and cancel");
        fixture.sender.fire(valid(BedrockManageForms.DELETE_FORCE_BUTTON));

        assertEquals(1, fixture.recorders.get("group").calls.get());
        assertArrayEquals(new String[] {"group", "delete", "builders", "--force"},
                fixture.recorders.get("group").lastArgs);
    }

    // ------------------------------------------------------------------
    // 權限設定檔：走同一個 profile 槽位
    // ------------------------------------------------------------------

    @Test
    void profileCreateWritesThroughSharedSlot() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.PROFILE)));
        fixture.sender.fire(valid(BedrockManageForms.PROFILE_CREATE));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Custom);
        fixture.sender.fire(textInput("vip"));

        assertEquals(1, fixture.recorders.get("profile").calls.get());
        assertArrayEquals(new String[] {"profile", "create", "vip"},
                fixture.recorders.get("profile").lastArgs);
    }

    @Test
    void profileSetWritesThroughSharedSlot() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.PROFILE)));
        fixture.sender.fire(valid(BedrockManageForms.PROFILE_SET));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Custom);
        List<String> actions = BedrockManageForms.profileSetActions();
        List<String> states = BedrockManageForms.profileSetStates();
        int actionIndex = actions.indexOf("CONTAINER_OPEN");
        int stateIndex = states.indexOf("ALLOW");
        assertTrue(actionIndex >= 0 && stateIndex >= 0);
        fixture.sender.fire(new FormResponse(FormResponseStatus.VALID, null, List.of(
                new FormValue.Text("vip"),
                new FormValue.Option(actionIndex),
                new FormValue.Option(stateIndex))));

        assertEquals(1, fixture.recorders.get("profile").calls.get());
        assertArrayEquals(new String[] {"profile", "set", "vip", "CONTAINER_OPEN", "ALLOW"},
                fixture.recorders.get("profile").lastArgs);
    }

    @Test
    void profileDeleteCancelWritesNothing() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.PROFILE)));
        fixture.sender.fire(valid(BedrockManageForms.PROFILE_DELETE));
        fixture.sender.fire(textInput("vip"));
        fixture.sender.fire(valid(BedrockManageForms.DELETE_CANCEL_BUTTON));

        assertEquals(0, fixture.totalCalls());
    }

    // ------------------------------------------------------------------
    // 每個回應最多寫入一次；失效條件不寫入
    // ------------------------------------------------------------------

    @Test
    void duplicateDeliveryOfOneResponseWritesOnce() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        fixture.sender.fire(valid(BedrockManageForms.GROUP_CREATE));
        assertTrue(fixture.sender.lastSpec instanceof FormSpec.Custom);
        Consumer<FormResponse> callback = fixture.sender.lastCallback;
        FormResponse response = textInput("builders");
        callback.accept(response);
        callback.accept(response);

        assertEquals(1, fixture.recorders.get("group").calls.get(),
                "the same response delivered twice still submits once");
    }

    @Test
    void submitAfterRevokeIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        // 同一塊領地、同一送出者：開啟時是擁有者，送出時擁有權已轉走，
        // gate 重裁決拒絕。這才是真正的撤權，不是換地。
        LandId land = new LandId(UUID.randomUUID());
        UUID other = UUID.randomUUID();
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        ManagementGateResolver.Request open = new ManagementGateResolver.Request(OWNER, land,
                LandRegistry.from(List.of(playerLand(land, OWNER))), false, false, provider);
        ManagementGateResolver.Request revoked = new ManagementGateResolver.Request(OWNER, land,
                LandRegistry.from(List.of(playerLand(land, other))), false, false, provider);
        java.util.concurrent.atomic.AtomicInteger resolutions =
                new java.util.concurrent.atomic.AtomicInteger();
        fixture.resolver = (sender, action, args) ->
                Optional.of(resolutions.getAndIncrement() == 0 ? open : revoked);
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        fixture.sender.fire(valid(0));
        fixture.sender.fire(valid(BedrockManageForms.RULE_ALLOW_BUTTON));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    // ------------------------------------------------------------------
    // 節點撤銷：五條能力各一條，群組與設定檔以此為唯一撤權路徑
    // ------------------------------------------------------------------

    @Test
    void ruleSubmitWithoutNodeIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        Player powerless = playerWithNodes(OWNER, Set.of());
        fixture.openRootAs(powerless);

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        fixture.sender.fire(valid(0));
        fixture.sender.fire(valid(BedrockManageForms.RULE_ALLOW_BUTTON));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));

        // 相同操作重開再送，仍然被拒。
        Fixture retry = new Fixture();
        retry.openRootAs(playerWithNodes(OWNER, Set.of()));
        retry.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        retry.sender.fire(valid(0));
        retry.sender.fire(valid(BedrockManageForms.RULE_ALLOW_BUTTON));

        assertEquals(0, retry.totalCalls());
        assertTrue(retry.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void sublandSubmitWithoutNodeIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.openRootAs(playerWithNodes(OWNER, Set.of()));

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.SUBLAND)));
        fixture.sender.fire(valid(BedrockManageForms.SUBLAND_CREATE));
        fixture.sender.fire(textInput("Shop"));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void groupSubmitWithoutNodeIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.openRootAs(playerWithNodes(OWNER, Set.of()));

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        fixture.sender.fire(valid(BedrockManageForms.GROUP_CREATE));
        fixture.sender.fire(textInput("builders"));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void profileSubmitWithoutNodeIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.openRootAs(playerWithNodes(OWNER, Set.of()));

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.PROFILE)));
        fixture.sender.fire(valid(BedrockManageForms.PROFILE_CREATE));
        fixture.sender.fire(textInput("vip"));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void nodeRevokedAndMovedLandTogetherIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        LandId landA = new LandId(UUID.randomUUID());
        LandId landB = new LandId(UUID.randomUUID());
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        ManagementGateResolver.Request requestA = new ManagementGateResolver.Request(OWNER, landA,
                LandRegistry.from(List.of(playerLand(landA, OWNER))), false, false, provider);
        ManagementGateResolver.Request requestB = new ManagementGateResolver.Request(OWNER, landB,
                LandRegistry.from(List.of(playerLand(landB, OWNER))), false, false, provider);
        java.util.concurrent.atomic.AtomicInteger resolutions =
                new java.util.concurrent.atomic.AtomicInteger();
        fixture.resolver = (sender, action, args) ->
                Optional.of(resolutions.getAndIncrement() == 0 ? requestA : requestB);
        // 節點被撤銷，同時送出時人也不在原領地：兩層都要擋。
        fixture.handler().handle(playerWithNodes(OWNER, Set.of()),
                new String[] {"manage"}, fixture.sink);

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        fixture.sender.fire(valid(0));
        fixture.sender.fire(valid(BedrockManageForms.RULE_ALLOW_BUTTON));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void submitAfterLandDeletedIsDeniedWithoutWriting() {        Fixture fixture = new Fixture();
        // 同一領地編號：開啟時領地存在，送出時快照已無此領地。
        // 用規則流程驗證，因為群組與設定檔本來就沒有領地上下文
        //（聊天路徑亦然，行為必須一致）。
        LandId land = new LandId(UUID.randomUUID());
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        ManagementGateResolver.Request open = new ManagementGateResolver.Request(OWNER, land,
                LandRegistry.from(List.of(playerLand(land, OWNER))), false, false, provider);
        ManagementGateResolver.Request gone = new ManagementGateResolver.Request(OWNER, land,
                LandRegistry.from(List.of()), false, false, provider);
        java.util.concurrent.atomic.AtomicInteger resolutions =
                new java.util.concurrent.atomic.AtomicInteger();
        fixture.resolver = (sender, action, args) ->
                Optional.of(resolutions.getAndIncrement() == 0 ? open : gone);
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        fixture.sender.fire(valid(0));
        fixture.sender.fire(valid(BedrockManageForms.RULE_ALLOW_BUTTON));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void submitAfterMovingToAnotherLandIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        LandId landA = new LandId(UUID.randomUUID());
        LandId landB = new LandId(UUID.randomUUID());
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        ManagementGateResolver.Request requestA = new ManagementGateResolver.Request(OWNER, landA,
                LandRegistry.from(List.of(playerLand(landA, OWNER))), false, false, provider);
        ManagementGateResolver.Request requestB = new ManagementGateResolver.Request(OWNER, landB,
                LandRegistry.from(List.of(playerLand(landB, OWNER))), false, false, provider);
        java.util.concurrent.atomic.AtomicInteger resolutions = new java.util.concurrent.atomic.AtomicInteger();
        fixture.resolver = (sender, action, args) ->
                Optional.of(resolutions.getAndIncrement() == 0 ? requestA : requestB);
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.SUBLAND)));
        fixture.sender.fire(valid(BedrockManageForms.SUBLAND_CREATE));
        fixture.sender.fire(textInput("Shop"));

        assertEquals(0, fixture.totalCalls(),
                "a submit after moving must not land on the new plot");
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void submitWhileOfflineIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.presence.set(false);
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        fixture.sender.fire(valid(BedrockManageForms.GROUP_CREATE));
        fixture.sender.fire(textInput("builders"));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void submitAfterDisableIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.alive.set(false);
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.PROFILE)));
        fixture.sender.fire(valid(BedrockManageForms.PROFILE_CREATE));
        fixture.sender.fire(textInput("vip"));

        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));
    }

    @Test
    void rosterSubmitAfterDisableIsDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        // 處理器層放行，共用操作層的存活世代擋下：證明兩層都在。
        fixture.roster.alive.set(false);
        fixture.openRoot();

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.TRUST_UNTRUST)));
        fixture.sender.fire(valid(0));
        FormSpec choice = fixture.sender.lastSpec;
        int inputButton = ((FormSpec.Simple) choice).buttons().size() - 2;
        fixture.sender.fire(valid(inputButton));
        fixture.sender.fire(textInput("OfflineBob"));

        assertTrue(fixture.roster.mutations.isEmpty(),
                "a retired generation never reaches the mutation");
        assertTrue(fixture.roster.tells.stream().anyMatch(key -> key.contains("failed")),
                "the retired submit is reported, not silent: " + fixture.roster.tells);
    }

    @Test
    void closedAndInvalidResponsesNeverWrite() {
        Fixture closed = new Fixture();
        closed.openRoot();

        closed.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        closed.sender.fire(valid(BedrockManageForms.GROUP_CREATE));
        closed.sender.fire(new FormResponse(FormResponseStatus.CLOSED, null, List.of()));
        assertEquals(0, closed.totalCalls());

        Fixture invalid = new Fixture();
        invalid.openRoot();
        invalid.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.GROUP)));
        invalid.sender.fire(valid(BedrockManageForms.GROUP_CREATE));
        invalid.sender.fire(new FormResponse(FormResponseStatus.INVALID, null, List.of()));
        assertEquals(0, invalid.totalCalls());
    }

    @Test
    void outOfRangeButtonsAreDeniedWithoutWriting() {
        Fixture fixture = new Fixture();
        fixture.openRoot();

        fixture.sender.fire(valid(99));
        assertEquals(0, fixture.totalCalls());
        assertTrue(fixture.sink.keys.contains("command.land.manage.denied"));

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        fixture.sender.fire(valid(99));
        assertEquals(0, fixture.totalCalls());
    }

    // ------------------------------------------------------------------
    // 返回、取消、錯誤畫面
    // ------------------------------------------------------------------

    @Test
    void backNavigationReturnsThroughPages() {
        Fixture fixture = new Fixture();
        BedrockFormNavigator navigator = new BedrockFormNavigator(fixture.sender);
        BedrockManageFormHandler manage = new BedrockManageFormHandler(
                new FakeLookup(), fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), fixture.handlerMap(), navigator,
                () -> List.of("Alice"),
                new BedrockClaimFormHandlerTest.ImmediateScheduler(),
                null, fixture.roster.controller,
                landId -> Optional.ofNullable(fixture.ruleViews),
                (actor, landId, action) -> Optional.ofNullable(fixture.defaultCurrent),
                (landId, rule) -> Optional.of(new LandRuleCommandHandler.Baseline(
                        PermissionState.INHERIT, 11L)),
                actor -> Optional.ofNullable(fixture.tokens),
                viewer -> fixture.presence.get(), () -> fixture.alive.get());
        manage.handle(player(OWNER), new String[] {"manage"}, fixture.sink);
        assertEquals(1, navigator.depth(OWNER));

        fixture.sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.TRUST_UNTRUST)));
        assertEquals(2, navigator.depth(OWNER));
        fixture.sender.fire(valid(0));
        assertEquals(3, navigator.depth(OWNER));

        navigator.back(OWNER);
        assertEquals(2, navigator.depth(OWNER));
        assertEquals(0, fixture.totalCalls(), "back navigation never submits");
        assertTrue(fixture.roster.mutations.isEmpty());
    }

    @Test
    void formsUnavailableFallbackExplainsTheCause() {
        Fixture fixture = new Fixture();
        BedrockFormNavigator unavailable = new BedrockFormNavigator(null);
        BedrockManageFormHandler manage = new BedrockManageFormHandler(
                new FakeLookup(), fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), fixture.handlerMap(), unavailable,
                () -> List.of("Alice"),
                new BedrockClaimFormHandlerTest.ImmediateScheduler(),
                null, fixture.roster.controller,
                landId -> Optional.ofNullable(fixture.ruleViews),
                (actor, landId, action) -> Optional.ofNullable(fixture.defaultCurrent),
                (landId, rule) -> Optional.of(new LandRuleCommandHandler.Baseline(
                        PermissionState.INHERIT, 11L)),
                actor -> Optional.ofNullable(fixture.tokens),
                viewer -> fixture.presence.get(), () -> fixture.alive.get());

        boolean tookOver = manage.handle(player(OWNER), new String[] {"manage"}, fixture.sink);

        assertTrue(tookOver, "the Bedrock branch still takes over when forms are down");
        assertEquals(List.of("command.land.manage.bedrock.forms_unavailable"), fixture.sink.keys);
        assertEquals(0, fixture.totalCalls());
    }

    // ------------------------------------------------------------------
    // 表單工廠：輸入、規則清單、確認、選單
    // ------------------------------------------------------------------

    @Test
    void formFactoriesBuildShapedSpecs() {
        BedrockFormTexts texts = BedrockFormTexts.english();

        FormSpec.Custom input = BedrockManageForms.nameInputMenu("Trust player", texts);
        assertEquals(FormSpec.Kind.CUSTOM, input.kind());
        assertEquals(1, input.components().size());

        assertEquals(Optional.of("OfflineBob"),
                BedrockManageForms.readNameInput(textInput("OfflineBob")));
        assertTrue(BedrockManageForms.readNameInput(textInput("   ")).isEmpty());
        assertTrue(BedrockManageForms.readNameInput(valid(0)).isEmpty());

        List<LandRuleView> rows = new ArrayList<>();
        for (LandRuleType rule : LandRuleType.values()) {
            rows.add(new LandRuleView(rule, PermissionState.INHERIT,
                    PermissionState.DENY, LandRuleView.Source.BUILT_IN));
        }
        assertEquals(11, rows.size());
        assertEquals(2, BedrockManageForms.ruleListPageCount(rows.size()));
        FormSpec.Simple page = BedrockManageForms.ruleListMenu(rows, 0, texts);
        assertEquals(10, page.buttons().size(), "eight rows plus Next plus Back");

        FormSpec.Simple confirm = BedrockManageForms.ruleConfirmMenu(LandRuleType.PVP,
                PermissionState.INHERIT, PermissionState.DENY, "BUILT_IN", texts);
        assertEquals(4, confirm.buttons().size());

        FormSpec.Modal permission = BedrockManageForms.permissionConfirmModal("BLOCK_BREAK",
                PermissionState.DENY, PermissionState.ALLOW, texts);
        assertEquals(FormSpec.Kind.MODAL, permission.kind());

        assertEquals(6, BedrockManageForms.sublandMenu(texts).buttons().size());
        assertEquals(6, BedrockManageForms.groupMenu(texts).buttons().size());
        assertEquals(5, BedrockManageForms.profileMenu(texts).buttons().size());
        assertEquals(3, BedrockManageForms.deleteChoiceMenu("builders", texts).buttons().size());
        assertEquals(1, BedrockManageForms.errorPage("gone", texts).buttons().size());

        FormSpec.Custom member = BedrockManageForms.groupMemberForm("Add member", texts);
        assertEquals(2, member.components().size());
        Optional<BedrockManageForms.GroupMemberInput> parsed = BedrockManageForms.readGroupMember(
                new FormResponse(FormResponseStatus.VALID, null, List.of(
                        new FormValue.Text("builders"), new FormValue.Text("Alice"))));
        assertEquals(Optional.of(new BedrockManageForms.GroupMemberInput("builders", "Alice")),
                parsed);

        FormSpec.Custom set = BedrockManageForms.profileSetForm(texts);
        assertEquals(3, set.components().size());
    }

    // ------------------------------------------------------------------
    // 沒有聊天可點擊元件專屬的管理操作
    // ------------------------------------------------------------------

    @Test
    void everyChatClickableManagementStepHasABedrockFormPath() {
        Map<BedrockManageForms.Capability, BedrockManageForms.BedrockPath> paths =
                new EnumMap<>(BedrockManageForms.Capability.class);
        for (BedrockManageForms.CapabilityRoute route : BedrockManageForms.routes()) {
            paths.put(route.capability(), route.path());
        }
        // 聊天確認點擊：圈地確認走既有的 Bedrock 確認 Modal 流程。
        assertEquals(BedrockManageForms.BedrockPath.EXISTING_FLOW,
                paths.get(BedrockManageForms.Capability.CLAIM));
        // 刪除填入點擊：基岩刪除確認框走同一個 delete 槽位。
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM,
                paths.get(BedrockManageForms.Capability.DELETE));
        // 查詢結果的三顆管理點擊：管理介面、信任、封鎖。
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM,
                paths.get(BedrockManageForms.Capability.TRUST_UNTRUST));
        assertEquals(BedrockManageForms.BedrockPath.MANAGE_FORM,
                paths.get(BedrockManageForms.Capability.BAN_UNBAN));
        assertTrue(ConfirmClick.CONFIRM_KEYS.contains(ConfirmClick.CONFIRM_KEY));
        assertTrue(ConfirmClick.CONFIRM_KEYS.contains(ConfirmClick.DELETE_CONFIRM_KEY));
        // 查詢結果的重新命名點擊只是填入文字，不執行任何專屬操作；
        // 它填入的 /land rename 槽位本來就不在管理表單的能力範圍內。
        assertFalse(paths.containsValue(null));
    }
}
