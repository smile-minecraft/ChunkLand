package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.capability.Capabilities;
import com.smile.chunkland.adapter.gui.ManagementRosterController;
import com.smile.chunkland.command.BedrockManageFormHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandRuleCommandHandler;
import com.smile.chunkland.command.ManageGuiCommandHandler;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.OfflinePlayerResolver;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.command.RosterChangeOperation;
import com.smile.chunkland.gui.BedrockFormNavigator;
import com.smile.chunkland.gui.BedrockManageForms;
import com.smile.chunkland.gui.GuiClickContext;
import com.smile.chunkland.gui.GuiNavigator;
import com.smile.chunkland.gui.GuiPage;
import com.smile.chunkland.gui.ManagementGuiModel;
import com.smile.chunkland.gui.ManagementRosterTexts;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /land manage} 的正式接線：Bedrock 玩家走表單分支，Java 玩家保留
 * 原本 GUI 路徑；半接線時沒有 Bedrock 分支；disable 清掉 Bedrock 導航追蹤。
 * 全部以假服務固定，零 sleep。
 */
class BedrockManageWiringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    static final class StubFormService implements FormService {
        final AtomicLong sends = new AtomicLong();
        volatile FormSendResult next = FormSendResult.SENT;
        volatile FormSpec lastSpec;
        volatile Consumer<FormResponse> lastCallback;

        @Override
        public FormSendResult sendForm(UUID player, FormSpec spec) {
            sends.incrementAndGet();
            lastSpec = spec;
            return next;
        }

        @Override
        public FormSendResult sendForm(UUID player, FormSpec spec, Consumer<FormResponse> consumer) {
            sends.incrementAndGet();
            lastSpec = spec;
            lastCallback = consumer;
            return next;
        }

        @Override
        public String getModuleStatus() {
            return "test-forms";
        }

        @Override
        public void shutdown() {
        }
    }

    static final class StubBedrockService implements BedrockService {
        volatile boolean bedrock;
        final FormService forms;

        StubBedrockService(FormService forms) {
            this.forms = forms;
        }

        @Override
        public boolean isBedrockPlayer(UUID uuid) {
            return bedrock;
        }

        @Override
        public Optional<com.smile.acelib.bedrock.BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
            return Optional.empty();
        }

        @Override
        public FormService forms() {
            return forms;
        }

        @Override
        public String getModuleStatus() {
            return "test-bedrock";
        }

        @Override
        public void shutdown() {
        }
    }

    static final class CompletedTask implements com.smile.acelib.scheduler.ScheduledTask {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public org.bukkit.plugin.java.JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public com.smile.acelib.scheduler.TaskType getType() {
            return com.smile.acelib.scheduler.TaskType.PLAYER;
        }

        @Override
        public long getCreationTick() {
            return 0L;
        }
    }

    static final class InlineScheduler implements com.smile.acelib.scheduler.SafeScheduler {
        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayer(Player player, Runnable runnable) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(runnable, "runnable");
            runnable.run();
            return new CompletedTask();
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runGlobal(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAsync(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runLater(Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runTimer(Runnable runnable, long delay, long period) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayerLater(
                Player player, Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForEntity(
                org.bukkit.entity.Entity entity, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAtLocation(
                org.bukkit.Location location, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public java.util.List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) {
            return java.util.List.of();
        }

        @Override
        public com.smile.acelib.scheduler.TaskScope scopeFor(Player player) {
            throw new UnsupportedOperationException("InlineScheduler does not support scopeFor(Player)");
        }

        @Override
        public com.smile.acelib.scheduler.TaskScope scopeFor(org.bukkit.entity.Entity entity) {
            throw new UnsupportedOperationException("InlineScheduler does not support scopeFor(Entity)");
        }

        @Override
        public void cancelAll() {
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
                        return "WiringPlayer";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "WiringPlayer-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
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

    private static Capabilities fullCapabilities(StubBedrockService bedrock, InlineScheduler scheduler) {
        return Capabilities.builder()
                .bedrockService(bedrock)
                .scheduler(scheduler)
                .build();
    }

    private static ManagementGateResolver fixed(ManagementGateResolver.Request request) {
        return (sender, action, args) -> Optional.ofNullable(request);
    }

    private static BedrockManageFormHandler buildForms(Capabilities capabilities,
            Map<String, LandCommand.Handler> handlers) {
        return ChunkLandPlugin.buildBedrockManageForms(capabilities, fixed(ownerRequest()),
                (actor, landId) -> ManagementGuiModel.unavailable(), handlers, List::of);
    }

    static final class RecordingSink implements ReplySink {
        final List<String> keys = new CopyOnWriteArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            keys.add(messageKey);
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale locale) {
            reply(messageKey, vars);
        }
    }

    // ------------------------------------------------------------------
    // 工廠：半接線回傳 null，全接線才有分支
    // ------------------------------------------------------------------

    @Test
    void navigatorFactoryNullWithoutCapabilitiesOrServices() {
        assertNull(ChunkLandPlugin.buildBedrockFormNavigator(null),
                "missing capabilities keep the legacy path");

        Capabilities noBedrock = Capabilities.builder()
                .scheduler(new InlineScheduler())
                .build();
        assertNull(ChunkLandPlugin.buildBedrockFormNavigator(noBedrock),
                "missing BedrockService keeps the legacy path");

        Capabilities noScheduler = Capabilities.builder()
                .bedrockService(new StubBedrockService(new StubFormService()))
                .build();
        assertNull(ChunkLandPlugin.buildBedrockFormNavigator(noScheduler),
                "missing scheduler keeps the legacy path");

        StubBedrockService nullForms = new StubBedrockService(null);
        Capabilities noForms = Capabilities.builder()
                .bedrockService(nullForms)
                .scheduler(new InlineScheduler())
                .build();
        assertNull(ChunkLandPlugin.buildBedrockFormNavigator(noForms),
                "missing FormService keeps the legacy path");
    }

    @Test
    void navigatorFactoryBuildsAvailableNavigatorWhenFullyWired() {
        StubFormService forms = new StubFormService();
        StubBedrockService bedrock = new StubBedrockService(forms);
        BedrockFormNavigator navigator = ChunkLandPlugin.buildBedrockFormNavigator(
                fullCapabilities(bedrock, new InlineScheduler()));

        assertNotNull(navigator, "fully-wired capabilities build the navigator");
        assertTrue(navigator.isAvailable());
    }

    @Test
    void manageFactoryNullWhenHalfWired() {
        StubFormService forms = new StubFormService();
        StubBedrockService bedrock = new StubBedrockService(forms);
        Capabilities capabilities = fullCapabilities(bedrock, new InlineScheduler());
        Map<String, LandCommand.Handler> handlers = new HashMap<>();

        assertNull(ChunkLandPlugin.buildBedrockManageForms(null, fixed(ownerRequest()),
                (actor, landId) -> ManagementGuiModel.unavailable(), handlers, List::of));
        assertNull(ChunkLandPlugin.buildBedrockManageForms(capabilities, null,
                (actor, landId) -> ManagementGuiModel.unavailable(), handlers, List::of));
        assertNull(ChunkLandPlugin.buildBedrockManageForms(capabilities, fixed(ownerRequest()),
                null, handlers, List::of));
        assertNull(ChunkLandPlugin.buildBedrockManageForms(capabilities, fixed(ownerRequest()),
                (actor, landId) -> ManagementGuiModel.unavailable(), null, List::of));
        assertNull(ChunkLandPlugin.buildBedrockManageForms(capabilities, fixed(ownerRequest()),
                (actor, landId) -> ManagementGuiModel.unavailable(), handlers, null));
        assertNull(buildForms(Capabilities.builder().build(), handlers),
                "empty capabilities build no manage branch");
    }

    @Test
    void manageFactoryBuildsWhenFullyWired() {
        StubFormService forms = new StubFormService();
        StubBedrockService bedrock = new StubBedrockService(forms);

        assertNotNull(buildForms(fullCapabilities(bedrock, new InlineScheduler()), new HashMap<>()));
    }

    // ------------------------------------------------------------------
    // 分支選擇：Java 保留原路徑，Bedrock 接管
    // ------------------------------------------------------------------

    @Test
    void javaPlayerKeepsJavaManagePath() {
        StubFormService forms = new StubFormService();
        StubBedrockService bedrock = new StubBedrockService(forms);
        bedrock.bedrock = false;
        Map<String, LandCommand.Handler> handlers = new HashMap<>();
        BedrockManageFormHandler bedrockManage =
                buildForms(fullCapabilities(bedrock, new InlineScheduler()), handlers);
        assertNotNull(bedrockManage);
        List<String> opened = new CopyOnWriteArrayList<>();
        ManageGuiCommandHandler javaManage = new ManageGuiCommandHandler(
                fixed(ownerRequest()), (player, landId) -> opened.add(landId.value().toString()));
        LandCommand.Handler wrapped =
                ChunkLandPlugin.wrapManageWithBedrock(javaManage, bedrockManage);
        RecordingSink sink = new RecordingSink();

        wrapped.handle(player(OWNER), new String[] {"manage"}, sink);

        assertEquals(1, opened.size(), "Java players keep the Java GUI path");
        assertEquals(0, forms.sends.get(), "Java players never trigger a form send");
        assertTrue(sink.keys.isEmpty());
    }

    @Test
    void bedrockPlayerTakesBedrockFormBranch() {
        StubFormService forms = new StubFormService();
        StubBedrockService bedrock = new StubBedrockService(forms);
        bedrock.bedrock = true;
        Map<String, LandCommand.Handler> handlers = new HashMap<>();
        BedrockManageFormHandler bedrockManage =
                buildForms(fullCapabilities(bedrock, new InlineScheduler()), handlers);
        assertNotNull(bedrockManage);
        List<String> opened = new CopyOnWriteArrayList<>();
        ManageGuiCommandHandler javaManage = new ManageGuiCommandHandler(
                fixed(ownerRequest()), (player, landId) -> opened.add(landId.value().toString()));
        LandCommand.Handler wrapped =
                ChunkLandPlugin.wrapManageWithBedrock(javaManage, bedrockManage);
        RecordingSink sink = new RecordingSink();

        wrapped.handle(player(OWNER), new String[] {"manage"}, sink);

        assertEquals(1, forms.sends.get(), "Bedrock players get the form branch");
        assertTrue(opened.isEmpty(), "Bedrock players leave the Java GUI untouched");
        assertTrue(sink.keys.isEmpty(), "showing the menu sends no chat reply");
        assertTrue(forms.lastSpec instanceof FormSpec.Simple);
        assertEquals(13, ((FormSpec.Simple) forms.lastSpec).buttons().size());
    }

    @Test
    void wrapWithoutBedrockKeepsJavaPath() {
        List<String> opened = new CopyOnWriteArrayList<>();
        ManageGuiCommandHandler javaManage = new ManageGuiCommandHandler(
                fixed(ownerRequest()), (player, landId) -> opened.add(landId.value().toString()));
        LandCommand.Handler wrapped = ChunkLandPlugin.wrapManageWithBedrock(javaManage, null);
        RecordingSink sink = new RecordingSink();

        wrapped.handle(player(OWNER), new String[] {"manage"}, sink);

        assertEquals(1, opened.size(), "half-wired servers keep the Java path");
    }

    // ------------------------------------------------------------------
    // disable：Bedrock 導航追蹤不殘留
    // ------------------------------------------------------------------

    @Test
    void disableCleanupDropsBedrockStacks() {
        StubFormService forms = new StubFormService();
        StubBedrockService bedrock = new StubBedrockService(forms);
        BedrockFormNavigator navigator = ChunkLandPlugin.buildBedrockFormNavigator(
                fullCapabilities(bedrock, new InlineScheduler()));
        assertNotNull(navigator);
        BedrockFormNavigator.Dispatcher dispatch = task -> new CompletedTask();
        BedrockFormNavigator.FailureReply failure = (key, vars) -> {
        };
        navigator.open(OWNER,
                new BedrockFormNavigator.FormPage("test-root",
                        FormSpec.simple("Root").content("menu").button("Back").build()),
                response -> {
                }, dispatch, failure);
        assertEquals(1, navigator.depth(OWNER));

        ChunkLandPlugin.closeBedrockForms(navigator);

        assertEquals(0, navigator.depth(OWNER), "disable leaves no Bedrock stack behind");
        assertTrue(navigator.trackedPlayers().isEmpty());
        ChunkLandPlugin.closeBedrockForms(null);
        ChunkLandPlugin.closeBedrockForms(navigator);
    }

    // ------------------------------------------------------------------
    // 訊息資源：降級通知中英 parity
    // ------------------------------------------------------------------

    @Test
    void unsupportedNoticeHasBilingualParity() throws Exception {
        YamlConfiguration en = new YamlConfiguration();
        en.load(new File("src/main/resources/lang/en_US.yml"));
        YamlConfiguration zh = new YamlConfiguration();
        zh.load(new File("src/main/resources/lang/zh_TW.yml"));
        String key = "command.land.manage.bedrock.unsupported";

        String enTemplate = en.getString(key);
        String zhTemplate = zh.getString(key);
        assertNotNull(enTemplate, "en_US must define " + key);
        assertNotNull(zhTemplate, "zh_TW must define " + key);
        assertFalse(enTemplate.isBlank(), "en template must not be blank");
        assertFalse(zhTemplate.isBlank(), "zh template must not be blank");
        strictValidate(key, enTemplate);
        strictValidate(key, zhTemplate);
        assertEquals(placeholders(enTemplate), placeholders(zhTemplate),
                "placeholder parity broken for " + key);
        assertNotEquals(enTemplate, zhTemplate, "zh render must differ from en for " + key);
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

    // ------------------------------------------------------------------
    // 正式接線：全部接縫加晚註冊槽位解析
    // ------------------------------------------------------------------

    private static int rootIndexOf(
            com.smile.chunkland.gui.BedrockManageForms.Capability capability) {
        java.util.List<com.smile.chunkland.gui.BedrockManageForms.CapabilityRoute> routes =
                com.smile.chunkland.gui.BedrockManageForms.routes();
        for (int index = 0; index < routes.size(); index++) {
            if (routes.get(index).capability() == capability) {
                return index;
            }
        }
        throw new IllegalStateException("no route for " + capability);
    }

    private static com.smile.acelib.form.FormResponse valid(int button) {
        return new com.smile.acelib.form.FormResponse(
                com.smile.acelib.form.FormResponseStatus.VALID, button, java.util.List.of());
    }

    static final class RecordingHandler implements LandCommand.Handler {
        final AtomicLong calls = new AtomicLong();
        volatile String[] lastArgs;

        @Override
        public void handle(org.bukkit.command.CommandSender sender, String[] args,
                ReplySink sink) {
            calls.incrementAndGet();
            lastArgs = args == null ? null : args.clone();
        }
    }

    /**
     * 接線測試用的真實名單控制器：與正式外掛同一個原始字串入口，
     * 回報只記錄鍵。規則路徑不會碰到它，断言保持為空即證明分流正確。
     */
    private static ManagementRosterController wiringRoster(
            java.util.List<String> tells) {
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
        RosterChangeOperation operation = new RosterChangeOperation(
                name -> Optional.empty(),
                new OfflinePlayerResolver(name -> Optional.empty(), null, null),
                PlayerScheduler.direct(),
                (viewer, landId, slot) -> true, viewer -> true, landId -> true,
                () -> true, null, slot -> null);
        ManagementRosterController.People people =
                new ManagementRosterController.People() {
                    @Override
                    public String nameOf(UUID playerId) {
                        return null;
                    }

                    @Override
                    public java.util.List<com.smile.chunkland.gui.ManagementRosterPages.Entry> online() {
                        return java.util.List.of();
                    }
                };
        return new ManagementRosterController(navigation, rosters,
                (viewer, landId, slot) -> true,
                new ManagementRosterController.Changes(null, null, null, null),
                people, (viewer, key, vars) -> tells.add(key),
                (viewer, task) -> task.run(),
                viewer -> ManagementRosterTexts.english(),
                operation, viewer -> player(viewer));
    }

    private static java.util.List<com.smile.chunkland.rule.LandRuleView> elevenRuleViews() {
        java.util.List<com.smile.chunkland.rule.LandRuleView> views = new java.util.ArrayList<>();
        for (com.smile.chunkland.api.rule.LandRuleType rule
                : com.smile.chunkland.api.rule.LandRuleType.values()) {
            views.add(new com.smile.chunkland.rule.LandRuleView(rule,
                    com.smile.chunkland.api.permission.PermissionState.INHERIT,
                    com.smile.chunkland.api.permission.PermissionState.DENY,
                    com.smile.chunkland.rule.LandRuleView.Source.BUILT_IN));
        }
        return views;
    }

    /**
     * 正式的完整組裝：傳入全部接縫，並且規則槽位在組裝「之後」才放入
     * 同一份活的處理器對應表（正式外掛就是這個順序）。規則表單送出必須
     * 到達規則槽位並完成寫入，證明晚註冊的槽位解析得到。
     */
    @Test
    void fullAssemblyResolvesLateRegisteredRuleSlot() {
        StubFormService forms = new StubFormService();
        StubBedrockService bedrock = new StubBedrockService(forms);
        bedrock.bedrock = true;
        Capabilities capabilities = fullCapabilities(bedrock, new InlineScheduler());
        Map<String, LandCommand.Handler> handlers = new HashMap<>();
        RecordingHandler rule = new RecordingHandler();
        java.util.List<String> rosterTells = new java.util.concurrent.CopyOnWriteArrayList<>();
        BedrockManageFormHandler manage = ChunkLandPlugin.buildBedrockManageForms(
                capabilities, fixed(ownerRequest()),
                (actor, landId) -> ManagementGuiModel.unavailable(), handlers, List::of, null,
                wiringRoster(rosterTells),
                landId -> Optional.of(elevenRuleViews()),
                (actor, landId, action) -> Optional.of(
                        com.smile.chunkland.api.permission.PermissionState.DENY),
                (landId, ruleType) -> Optional.of(new LandRuleCommandHandler.Baseline(
                        com.smile.chunkland.api.permission.PermissionState.INHERIT, 11L)),
                actor -> Optional.of(new BedrockManageFormHandler.SublandTokens(7L, 3L)),
                viewer -> true, () -> true);
        assertNotNull(manage, "the full assembly wires the manage branch");

        // 規則槽位在組裝之後才註冊，與正式外掛的順序一致。
        handlers.put("rule", rule);

        RecordingSink sink = new RecordingSink();
        manage.handle(player(OWNER), new String[] {"manage"}, sink);
        assertEquals(1, forms.sends.get(), "the root menu opens");

        forms.lastCallback.accept(valid(rootIndexOf(BedrockManageForms.Capability.LAND_RULE)));
        forms.lastCallback.accept(valid(0));
        forms.lastCallback.accept(valid(BedrockManageForms.RULE_ALLOW_BUTTON));

        assertEquals(1, rule.calls.get(),
                "the rule submit reaches the late-registered rule slot");
        String expectedRule =
                com.smile.chunkland.api.rule.LandRuleType.values()[0].name();
        assertEquals(java.util.List.of("rule", expectedRule, "ALLOW"),
                java.util.List.of(rule.lastArgs),
                "Bedrock submits the same args the chat command would");
        assertTrue(rosterTells.isEmpty(), "the rule path never touches the roster entry");
    }

    private static Set<String> placeholders(String template) {
        Set<String> out = new java.util.HashSet<>();
        var matcher = java.util.regex.Pattern.compile("<([a-z_0-9]+)>").matcher(template);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }
}
