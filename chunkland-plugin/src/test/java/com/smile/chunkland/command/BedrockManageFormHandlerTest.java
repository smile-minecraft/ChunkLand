package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.gui.BedrockFormNavigator;
import com.smile.chunkland.gui.BedrockManageForms;
import com.smile.chunkland.gui.ManagementGuiModel;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainLayer;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /land manage} 的 Bedrock 分支：Bedrock 玩家看到共用模型的表單流程，
 * Java 玩家保留原本 GUI 路徑。變更一律轉交既有 handler 槽位，沒有 EVERYONE
 * 入口；過期、重複、未知回應不重複提交。全部以假服務固定，零 sleep。
 */
class BedrockManageFormHandlerTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    static final class FakeSender implements BedrockFormNavigator.FormSender {
        final AtomicLong sends = new AtomicLong();
        volatile FormSendResult next = FormSendResult.SENT;
        volatile boolean throwSend;
        volatile FormSpec lastSpec;
        volatile Consumer<FormResponse> lastCallback;

        @Override
        public FormSendResult send(UUID playerId, FormSpec spec, Consumer<FormResponse> callback) {
            sends.incrementAndGet();
            lastSpec = spec;
            lastCallback = callback;
            if (throwSend) {
                throw new IllegalStateException("form send down");
            }
            return next;
        }

        void fire(FormResponse response) {
            assertNotNull(lastCallback, "a form must have been sent before firing a response");
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
        volatile boolean bedrock;
        volatile boolean throwLookup;

        @Override
        public boolean isBedrock(UUID playerId) {
            if (throwLookup) {
                throw new IllegalStateException("bedrock lookup down");
            }
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

    private static ManagementGateResolver.Request strangerRequest() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id, OWNER)));
        PermissionContextProvider provider = new SnapshotPermissionContextProvider(null, null);
        return new ManagementGateResolver.Request(
                UUID.randomUUID(), id, snapshot, false, false, provider);
    }

    private static PermissionExplain explainOf(ProtectionActionType action,
            PermissionState outcome, PermissionExplainLayer layer) {
        return new PermissionExplain(action, outcome,
                action.decisionSource(), layer,
                "test reason -> " + outcome, null, false, false, false);
    }

    private static ManagementGuiModel twoRowModel() {
        UUID holder = UUID.randomUUID();
        List<PermissionExplain> explains = List.of(
                explainOf(ProtectionActionType.BLOCK_BREAK,
                        PermissionState.ALLOW, PermissionExplainLayer.LAND_DEFAULT),
                explainOf(ProtectionActionType.CONTAINER_OPEN,
                        PermissionState.DENY, PermissionExplainLayer.LAND_BINDING));
        Map<ProtectionActionType, PermissionContext> contexts = new EnumMap<>(ProtectionActionType.class);
        contexts.put(ProtectionActionType.BLOCK_BREAK, PermissionContext.builder(
                ProtectionActionType.BLOCK_BREAK).build());
        contexts.put(ProtectionActionType.CONTAINER_OPEN, PermissionContext.builder(
                ProtectionActionType.CONTAINER_OPEN)
                .landBindings(List.of(new PermissionBinding(PermissionSubject.player(holder),
                        new Permission(ProtectionActionType.CONTAINER_OPEN, PermissionState.DENY))))
                .build());
        return ManagementGuiModel.fromExplains(explains, contexts);
    }

    private static Map<String, RecordingHandler> handlers() {
        Map<String, RecordingHandler> recorders = new HashMap<>();
        for (String slot : List.of("trust", "untrust", "ban", "unban", "inspect",
                "explain", "delete", "binding", "default")) {
            recorders.put(slot, new RecordingHandler());
        }
        return recorders;
    }

    private static Map<String, LandCommand.Handler> handlerMap(Map<String, RecordingHandler> recorders) {
        Map<String, LandCommand.Handler> map = new HashMap<>();
        map.putAll(recorders);
        return map;
    }

    private static BedrockManageFormHandler handler(FakeLookup lookup,
            ManagementGateResolver resolver, BedrockManageFormHandler.ModelSource models,
            Map<String, RecordingHandler> recorders, FakeSender sender,
            com.smile.acelib.scheduler.SafeScheduler scheduler) {
        return new BedrockManageFormHandler(lookup, resolver, models, handlerMap(recorders),
                new BedrockFormNavigator(sender), List::of, scheduler);
    }

    private static BedrockManageFormHandler handler(FakeLookup lookup,
            ManagementGateResolver resolver, BedrockManageFormHandler.ModelSource models,
            Map<String, RecordingHandler> recorders, FakeSender sender,
            com.smile.acelib.scheduler.SafeScheduler scheduler,
            BedrockManageFormHandler.OnlineNames names) {
        return new BedrockManageFormHandler(lookup, resolver, models, handlerMap(recorders),
                new BedrockFormNavigator(sender), names, scheduler);
    }

    private static ManagementGateResolver fixed(ManagementGateResolver.Request request) {
        return (sender, action, args) -> Optional.ofNullable(request);
    }

    private static FormResponse valid(int button) {
        return new FormResponse(FormResponseStatus.VALID, button, List.of());
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

    private static long totalHandlerCalls(Map<String, RecordingHandler> recorders) {
        return recorders.values().stream().mapToLong(recorder -> recorder.calls.get()).sum();
    }

    // ------------------------------------------------------------------
    // 入口路由：Java 保留原路徑，Bedrock 接管
    // ------------------------------------------------------------------

    @Test
    void nonBedrockKeepsJavaPath() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = false;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        BedrockManageFormHandler manage = handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler());

        assertFalse(manage.handle(player(OWNER), new String[] {"manage"}, sink));

        assertEquals(0, sender.sends.get(), "Java players must never trigger a form send");
        assertEquals(0, totalHandlerCalls(recorders));
        assertTrue(sink.keys.isEmpty());
    }

    @Test
    void bedrockLookupFailureFailsClosed() {
        FakeLookup lookup = new FakeLookup();
        lookup.throwLookup = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();

        boolean tookOver = handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        assertTrue(tookOver, "unknown player kind still takes over fail-closed");
        assertEquals(0, sender.sends.get());
        assertEquals(List.of("command.land.manage.denied"), sink.keys);
    }

    @Test
    void gateDenyAndUnresolvableTargetFailClosed() {
        for (ManagementGateResolver resolver : List.of(
                fixed(strangerRequest()),
                (sender, action, args) -> Optional.empty())) {
            FakeLookup lookup = new FakeLookup();
            lookup.bedrock = true;
            FakeSender sender = new FakeSender();
            Map<String, RecordingHandler> recorders = handlers();
            RecordingSink sink = new RecordingSink();

            boolean tookOver = handler(lookup, resolver,
                    (actor, landId) -> twoRowModel(), recorders, sender,
                    new BedrockClaimFormHandlerTest.ImmediateScheduler())
                    .handle(player(UUID.randomUUID()), new String[] {"manage"}, sink);

            assertTrue(tookOver);
            assertEquals(0, sender.sends.get(), "gate deny must not show any form");
            assertEquals(0, totalHandlerCalls(recorders));
            assertEquals(List.of("command.land.manage.denied"), sink.keys);
        }
    }

    @Test
    void ownerOpensRootMenuWithThirteenEntries() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();

        boolean tookOver = handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        assertTrue(tookOver);
        assertEquals(1, sender.sends.get());
        assertEquals(0, totalHandlerCalls(recorders), "showing the menu touches no mutation");
        assertTrue(sink.keys.isEmpty(), "showing the menu sends no chat reply");
        assertTrue(sender.lastSpec instanceof FormSpec.Simple, "root menu is a Simple form");
        assertEquals(13, ((FormSpec.Simple) sender.lastSpec).buttons().size());
    }

    @Test
    void missingSchedulerOrSendFailureFailsClosed() {
        for (int mode = 0; mode < 3; mode++) {
            FakeLookup lookup = new FakeLookup();
            lookup.bedrock = true;
            FakeSender sender = new FakeSender();
            if (mode == 1) {
                sender.next = FormSendResult.REJECTED;
            } else if (mode == 2) {
                sender.throwSend = true;
            }
            Map<String, RecordingHandler> recorders = handlers();
            RecordingSink sink = new RecordingSink();
            com.smile.acelib.scheduler.SafeScheduler scheduler = mode == 0
                    ? null : new BedrockClaimFormHandlerTest.ImmediateScheduler();

            boolean tookOver = handler(lookup, fixed(ownerRequest()),
                    (actor, landId) -> twoRowModel(), recorders, sender, scheduler)
                    .handle(player(OWNER), new String[] {"manage"}, sink);

            assertTrue(tookOver);
            assertEquals(0, totalHandlerCalls(recorders), "mode " + mode + " touches no mutation");
            assertEquals(List.of("command.land.manage.denied"), sink.keys, "mode " + mode);
        }
    }

    // ------------------------------------------------------------------
    // 主選單回應：無效選項 fail-closed，關閉靜默清理
    // ------------------------------------------------------------------

    @Test
    void invalidButtonFailsClosedWithoutMutation() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(99));

        assertEquals(0, totalHandlerCalls(recorders));
        assertEquals(List.of("command.land.manage.denied"), sink.keys);
    }

    @Test
    void closedMenuClearsWithoutHandlerOrReply() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(new FormResponse(FormResponseStatus.CLOSED, null, List.of()));

        assertEquals(0, totalHandlerCalls(recorders));
        assertTrue(sink.keys.isEmpty(), "player dismissal is silent");
        assertEquals(1, sender.sends.get(), "dismissal never reopens");
    }

    @Test
    void staleResponseAfterMenuIsNoOp() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        BedrockFormNavigator navigator = new BedrockFormNavigator(sender);
        new BedrockManageFormHandler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), handlerMap(recorders), navigator,
                List::of, new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        long live = navigator.currentGeneration(OWNER).orElseThrow();
        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.INSPECT)));
        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.INSPECT)));

        assertEquals(1, recorders.get("inspect").calls.get(), "duplicate menu tap submits once");
        navigator.handleResponse(OWNER, live + 999L, valid(0));
        assertEquals(1, recorders.get("inspect").calls.get(), "stale generation is dropped");
    }

    // ------------------------------------------------------------------
    // 明細與轉交：共用模型、既有 slot、同形參數
    // ------------------------------------------------------------------

    @Test
    void permissionDetailPushesFromSharedModelAndBackReturns() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.BASIC_PERMISSION)));

        assertEquals(2, sender.sends.get(), "detail opens on top of the menu");
        assertTrue(sender.lastSpec instanceof FormSpec.Simple);
        FormSpec.Simple detail = (FormSpec.Simple) sender.lastSpec;
        assertTrue(detail.content().contains("CONTAINER_OPEN: DENY @ LAND_BINDING"),
                "detail renders the shared model: " + detail.content());
        assertEquals(0, totalHandlerCalls(recorders), "opening detail touches no mutation");

        sender.fire(valid(detail.buttons().size() - 1));

        assertEquals(3, sender.sends.get(), "Back resends the root menu");
        assertEquals(0, totalHandlerCalls(recorders));
    }

    @Test
    void inspectDelegatesToExistingSlotWithChatEquivalentArgs() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.INSPECT)));

        assertEquals(1, recorders.get("inspect").calls.get());
        assertArrayEquals(new String[] {"inspect"}, recorders.get("inspect").lastArgs);
    }

    @Test
    void detailRowDelegatesToExplainWithRowAction() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.BASIC_PERMISSION)));
        String rowAction = twoRowModel().rows().get(0).action().name();
        sender.fire(valid(0));

        assertEquals(1, recorders.get("explain").calls.get());
        assertArrayEquals(new String[] {"explain", rowAction}, recorders.get("explain").lastArgs);
    }

    @Test
    void deleteConfirmModalDelegates() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.DELETE)));

        assertTrue(sender.lastSpec instanceof FormSpec.Modal, "delete needs a confirm modal");
        sender.fire(valid(0));

        assertEquals(1, recorders.get("delete").calls.get());
        assertArrayEquals(new String[] {"delete"}, recorders.get("delete").lastArgs);
    }

    @Test
    void deleteModalCancelReturnsToRootMenu() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler())
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.DELETE)));
        assertTrue(sender.lastSpec instanceof FormSpec.Modal);
        sender.fire(valid(1));

        assertEquals(0, recorders.get("delete").calls.get(), "cancel never submits");
        assertEquals(3, sender.sends.get(), "cancel returns to the root menu");
        assertTrue(sender.lastSpec instanceof FormSpec.Simple);
    }

    @Test
    void trustFlowDelegatesWithChosenOnlineName() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler(),
                () -> List.of("Alice", "Bob"))
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.TRUST_UNTRUST)));
        sender.fire(valid(1));
        sender.fire(valid(1));

        assertEquals(1, recorders.get("untrust").calls.get());
        assertArrayEquals(new String[] {"untrust", "Bob"}, recorders.get("untrust").lastArgs);
        assertEquals(0, recorders.get("trust").calls.get());
    }

    @Test
    void banFlowDelegatesWithChosenOnlineName() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender,
                new BedrockClaimFormHandlerTest.ImmediateScheduler(),
                () -> List.of("Alice"))
                .handle(player(OWNER), new String[] {"manage"}, sink);

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.BAN_UNBAN)));
        sender.fire(valid(0));
        sender.fire(valid(0));

        assertEquals(1, recorders.get("ban").calls.get());
        assertArrayEquals(new String[] {"ban", "Alice"}, recorders.get("ban").lastArgs);
    }

    @Test
    void downgradeCapabilitiesReplyExplicitlyWithoutClaimingSuccess() {
        for (BedrockManageForms.Capability capability : List.of(
                BedrockManageForms.Capability.SUBLAND,
                BedrockManageForms.Capability.GROUP,
                BedrockManageForms.Capability.PROFILE,
                BedrockManageForms.Capability.LAND_RULE,
                BedrockManageForms.Capability.CLAIM,
                BedrockManageForms.Capability.EXPAND,
                BedrockManageForms.Capability.SHRINK)) {
            FakeLookup lookup = new FakeLookup();
            lookup.bedrock = true;
            FakeSender sender = new FakeSender();
            Map<String, RecordingHandler> recorders = handlers();
            RecordingSink sink = new RecordingSink();
            handler(lookup, fixed(ownerRequest()),
                    (actor, landId) -> twoRowModel(), recorders, sender,
                    new BedrockClaimFormHandlerTest.ImmediateScheduler())
                    .handle(player(OWNER), new String[] {"manage"}, sink);

            sender.fire(valid(rootIndexOf(capability)));

            assertEquals(0, totalHandlerCalls(recorders),
                    capability + " must not touch any mutation through the menu");
            assertEquals(List.of("command.land.manage.bedrock.unsupported"), sink.keys,
                    capability + " replies its downgrade explicitly");
        }
    }

    @Test
    void responseTravelsViaPlayerSchedulerNotInline() {
        FakeLookup lookup = new FakeLookup();
        lookup.bedrock = true;
        FakeSender sender = new FakeSender();
        Map<String, RecordingHandler> recorders = handlers();
        RecordingSink sink = new RecordingSink();
        BedrockClaimFormHandlerTest.CapturingScheduler capturing =
                new BedrockClaimFormHandlerTest.CapturingScheduler();
        handler(lookup, fixed(ownerRequest()),
                (actor, landId) -> twoRowModel(), recorders, sender, capturing)
                .handle(player(OWNER), new String[] {"manage"}, sink);
        assertEquals(1, sender.sends.get());

        sender.fire(valid(rootIndexOf(BedrockManageForms.Capability.INSPECT)));

        assertEquals(1, capturing.dispatches.get(), "menu response travels via runForPlayer");
        assertEquals(0, recorders.get("inspect").calls.get(), "handler waits for the dispatch");
        capturing.runAll();
        assertEquals(1, recorders.get("inspect").calls.get());
    }

    // ------------------------------------------------------------------
    // 訊息資源：降級通知在兩種語系都存在
    // ------------------------------------------------------------------

    @Test
    void unsupportedNoticeExistsInBothLocales() throws Exception {
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
            String template = cfg.getString("command.land.manage.bedrock.unsupported");
            assertNotNull(template, tag + " must define command.land.manage.bedrock.unsupported");
            assertFalse(template.isBlank(), tag + " unsupported notice must not be blank");
            strictValidate("command.land.manage.bedrock.unsupported", template);
        }
    }

    private static void strictValidate(String key, String template) throws Exception {
        var method = com.smile.chunkland.message.ChunkLandMessagePipeline.class
                .getDeclaredMethod("validateTemplateStrict", String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, key, template);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw (RuntimeException) failure.getCause();
        }
    }
}
