package com.smile.chunkland.message.rejection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.protection.EntryProtectionAdapter;
import com.smile.chunkland.protection.ProtectionEngine;
import com.smile.chunkland.protection.ProtectionListener;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionClock;
import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

/**
 * Rejection feedback policy pins: throttle window, ENTRY templates,
 * banned-inside marker, orphan key removal, and silence rules.
 */
class RejectionFeedbackTest {

    private static final class FakeClock implements SelectionClock {
        private final AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-09-24T00:00:00Z"));

        @Override
        public Instant now() {
            return now.get();
        }

        void advance(Duration delta) {
            now.updateAndGet(t -> t.plus(delta));
        }
    }

    private static World worldProxy(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    private static Player playerProxy(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getName": return "TestPlayer";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static LandSnapshot landAt(UUID worldId, LandId id, int chunkX, int chunkZ) {
        return new LandSnapshot(id, "TestLand", "testland", OwnerRef.player(UUID.randomUUID()),
                worldId, Set.of(new ChunkKey(worldId, chunkX, chunkZ)),
                List.of(), 0, 0, Instant.now(), Instant.now());
    }

    private static ProtectionEngine engineWithSubjectDefault(LandRegistryStore store,
            PermissionState state) {
        return new ProtectionEngine(store::snapshot, (actor, landId, action, snapshot) -> {
            if (action.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(action, false, List.of(),
                        PermissionState.INHERIT, state);
            }
            return new PermissionContext(action, false, List.of(), state,
                    PermissionState.INHERIT);
        });
    }

    private static final class CapturingMessaging {
        final FakeClock clock = new FakeClock();
        final AtomicInteger sends = new AtomicInteger();
        final AtomicReference<ProtectionActionType> lastAction = new AtomicReference<>();
        final AtomicReference<PermissionDecision> lastDecision = new AtomicReference<>();

        RejectionNotifier notifier(Duration window) {
            RejectionNotifier.Sender sender = (player, message) -> sends.incrementAndGet();
            RejectionNotifier.Renderer renderer = (player, action, decision) -> {
                lastAction.set(action);
                lastDecision.set(decision);
                return Component.text("captured: " + action.name());
            };
            return new RejectionNotifier(sender, renderer,
                    new RejectionCooldown(clock, window), Set.of());
        }
    }

    private static PermissionDecision denySubject(String reason) {
        return new PermissionDecision(PermissionState.DENY,
                DecisionSource.SUBJECT_PERMISSION, reason);
    }

    private static YamlConfiguration loadLang(String localeTag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
        return cfg;
    }

    @Test
    void defaultCooldownIsTwoSeconds() {
        assertEquals(Duration.ofSeconds(2), RejectionCooldown.DEFAULT_COOLDOWN,
                "rejection throttle default must be 2s per policy");
    }

    @Test
    void throttleWindowMatchesTwoSecondDefault() {
        CapturingMessaging messaging = new CapturingMessaging();
        RejectionNotifier notifier = messaging.notifier(RejectionCooldown.DEFAULT_COOLDOWN);
        Player target = playerProxy(UUID.randomUUID());
        assertTrue(notifier.notifyDenied(target, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")));
        messaging.clock.advance(Duration.ofSeconds(1));
        assertFalse(notifier.notifyDenied(target, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")), "1s inside the 2s window must stay silent");
        messaging.clock.advance(Duration.ofSeconds(1));
        assertTrue(notifier.notifyDenied(target, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")), "at the 2s boundary the window must reopen");
        assertEquals(2, messaging.sends.get());
    }

    @Test
    void bannedInsideCarriesStableMarker() {
        UUID worldId = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(
                List.of(landAt(worldId, new LandId(UUID.randomUUID()), 0, 0))));
        World world = worldProxy(worldId);
        Player player = playerProxy(UUID.randomUUID());
        CapturingMessaging messaging = new CapturingMessaging();
        EntryProtectionAdapter.BanLookup bans = (playerId, wid, cx, cz) -> Optional.of(true);
        ProtectionListener listener = new ProtectionListener(
                engineWithSubjectDefault(store, PermissionState.DENY),
                messaging.notifier(Duration.ofSeconds(2)), bans);
        PlayerMoveEvent event = new PlayerMoveEvent(player,
                new Location(world, 21, 64, 5), new Location(world, 5, 64, 5));
        listener.onPlayerMove(event);
        assertTrue(event.isCancelled(), "banned-inside stop must still cancel");
        assertEquals(1, messaging.sends.get(), "banned-inside stop must notify once");
        assertEquals(ProtectionActionType.ENTRY, messaging.lastAction.get());
        assertNotNull(messaging.lastDecision.get());
        assertEquals("chunkland:banned-inside", messaging.lastDecision.get().explanation(),
                "ban-inside must carry the stable marker, never a raw English sentence");
    }

    @Test
    void orphanKeysRemovedFromBothLocales() throws Exception {
        String[] orphans = {
            "protection.rejection.denied",
            "protection.rejection.limit",
            "protection.rejection.cooldown",
            "protection.rejection.economy",
            "protection.allow.silent",
        };
        for (String localeTag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = loadLang(localeTag);
            for (String key : orphans) {
                assertNull(cfg.getString(key), localeTag + " must not carry orphan key " + key);
            }
        }
    }

    @Test
    void entryTemplatesExistInBothLocales() throws Exception {
        for (String localeTag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = loadLang(localeTag);
            String entryDenied = cfg.getString("protection.rejection.entry_denied");
            String bannedInside = cfg.getString("protection.rejection.banned_inside");
            assertNotNull(entryDenied, localeTag + " missing protection.rejection.entry_denied");
            assertNotNull(bannedInside, localeTag + " missing protection.rejection.banned_inside");
            assertFalse(entryDenied.isBlank(), localeTag + " entry_denied must not be blank");
            assertFalse(bannedInside.isBlank(), localeTag + " banned_inside must not be blank");
        }
    }

    @Test
    void landRuleDenyStaysSilent() {
        CapturingMessaging messaging = new CapturingMessaging();
        RejectionNotifier notifier = messaging.notifier(Duration.ofSeconds(2));
        boolean sent = notifier.notifyDenied(playerProxy(UUID.randomUUID()),
                ProtectionActionType.PLAYER_DAMAGE_PLAYER,
                new PermissionDecision(PermissionState.DENY, DecisionSource.LAND_RULE, "pvp off"));
        assertFalse(sent, "LAND_RULE deny must stay silent");
        assertEquals(0, messaging.sends.get());
    }

    @Test
    void nullSenderStaysSilent() {
        FakeClock clock = new FakeClock();
        RejectionNotifier notifier = new RejectionNotifier(null,
                (player, action, decision) -> Component.text("never"),
                new RejectionCooldown(clock, Duration.ofSeconds(2)));
        assertFalse(notifier.notifyDenied(playerProxy(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access")));
    }

    // -----------------------------------------------------------------
    // Routing: ENTRY branches vs shared template
    // -----------------------------------------------------------------

    @Test
    void routingSelectsEntryAndBannedInsideBranches() {
        assertEquals("protection.rejection.entry_denied",
                PipelineRejectionRenderer.routeKey(
                        ProtectionActionType.ENTRY, "entry denied by rule"),
                "ordinary ENTRY deny must use entry_denied");
        assertEquals("protection.rejection.entry_denied",
                PipelineRejectionRenderer.routeKey(ProtectionActionType.ENTRY, null),
                "ENTRY deny without explanation must use entry_denied");
        assertEquals("protection.rejection.banned_inside",
                PipelineRejectionRenderer.routeKey(
                        ProtectionActionType.ENTRY, RejectionNotifier.BANNED_INSIDE_REASON),
                "ENTRY deny with the stable marker must use banned_inside");
        assertEquals("protection.rejection.action_denied",
                PipelineRejectionRenderer.routeKey(
                        ProtectionActionType.BLOCK_BREAK, "no access"),
                "non-ENTRY deny must keep the shared template");
        assertEquals("protection.rejection.action_denied",
                PipelineRejectionRenderer.routeKey(
                        ProtectionActionType.BLOCK_BREAK, RejectionNotifier.BANNED_INSIDE_REASON),
                "the marker is only special for ENTRY");
        assertEquals(PipelineRejectionRenderer.ENTRY_DENIED_KEY,
                "protection.rejection.entry_denied");
        assertEquals(PipelineRejectionRenderer.BANNED_INSIDE_KEY,
                "protection.rejection.banned_inside");
    }

    @Test
    void newTemplatesCarryNoPlaceholdersInEitherLocale() throws Exception {
        for (String localeTag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = loadLang(localeTag);
            for (String key : new String[]{
                    "protection.rejection.entry_denied", "protection.rejection.banned_inside"}) {
                String template = cfg.getString(key);
                assertNotNull(template, localeTag + " missing " + key);
                assertTrue(placeholders(template).isEmpty(),
                        localeTag + " " + key + " must carry no vars");
                assertDoesNotThrow(() -> MiniMessage.builder().strict(true).build()
                        .deserialize(template), localeTag + " " + key + " must be strict-valid");
            }
        }
        YamlConfiguration en = loadLang("en_US");
        YamlConfiguration zh = loadLang("zh_TW");
        assertEquals(placeholders(en.getString("protection.rejection.entry_denied")),
                placeholders(zh.getString("protection.rejection.entry_denied")));
        assertEquals(placeholders(en.getString("protection.rejection.banned_inside")),
                placeholders(zh.getString("protection.rejection.banned_inside")));
    }

    // -----------------------------------------------------------------
    // Sender: hop to the player thread, fail-closed everywhere
    // -----------------------------------------------------------------

    private static Player actionBarPlayer(UUID id, List<Component> delivered) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getName": return "TestPlayer";
                        case "locale": return Locale.US;
                        case "sendActionBar":
                            delivered.add((Component) args[0]);
                            return null;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    @Test
    void senderHopsBeforeTouchingPlayer() {
        List<Runnable> hopped = new CopyOnWriteArrayList<>();
        PlayerScheduler scheduler = (player, task) -> hopped.add(task);
        List<Component> delivered = new CopyOnWriteArrayList<>();
        Player player = actionBarPlayer(UUID.randomUUID(), delivered);
        Component message = Component.text("denied");
        new PlayerRegionRejectionSender(scheduler).send(player, message);
        assertEquals(1, hopped.size(), "send must hop through the scheduler");
        assertTrue(delivered.isEmpty(), "nothing may touch the player before the hop runs");
        hopped.get(0).run();
        assertEquals(List.of(message), delivered, "the hop must deliver the exact notice");
    }

    @Test
    void senderFailClosed() {
        PlayerScheduler throwing = (player, task) -> {
            throw new IllegalStateException("retired scheduler");
        };
        PlayerRegionRejectionSender sender = new PlayerRegionRejectionSender(throwing);
        List<Component> delivered = new CopyOnWriteArrayList<>();
        Player player = actionBarPlayer(UUID.randomUUID(), delivered);
        assertDoesNotThrow(() -> sender.send(player, Component.text("denied")),
                "a refused hop must drop the notice without throwing");
        assertDoesNotThrow(() -> sender.send(null, Component.text("denied")));
        assertDoesNotThrow(() -> sender.send(player, null));
        assertTrue(delivered.isEmpty());
    }

    @Test
    void senderSendFailureOnPlayerThreadStaysSilent() {
        PlayerScheduler direct = PlayerScheduler.direct();
        Player exploding = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return UUID.randomUUID();
                        case "sendActionBar": throw new IllegalStateException("departed");
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
        assertDoesNotThrow(() -> new PlayerRegionRejectionSender(direct)
                .send(exploding, Component.text("denied")),
                "player-thread send failure must stay silent");
    }

    // -----------------------------------------------------------------
    // End to end: listener -> notifier -> renderer -> template -> hop
    // -----------------------------------------------------------------

    private static ChunkLandMessagePipeline testPipeline() throws Exception {
        YamlConfiguration en = loadLang("en_US");
        ChunkLandMessagePipeline.LangProvider lang =
                (locale, key) -> Optional.ofNullable(en.getString(key));
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            List<TagResolver> resolvers = new ArrayList<>();
            if (vars != null) {
                for (Map.Entry<String, Object> entry : vars.entrySet()) {
                    resolvers.add(Placeholder.unparsed(entry.getKey(),
                            String.valueOf(entry.getValue())));
                }
            }
            return MiniMessage.miniMessage().deserialize(template,
                    TagResolver.resolver(resolvers));
        };
        ChunkLandMessagePipeline.PipelineSender sender =
                new ChunkLandMessagePipeline.PipelineSender() {
                    @Override public void sendChat(Player p, Component m) {}
                    @Override public void sendChatWithFallback(Player p, Component m, Locale l) {}
                    @Override public void sendActionBar(Player p, Component m) {}
                    @Override public void sendActionBarWithFallback(Player p, Component m, Locale l) {}
                    @Override public void sendTitle(Player p, Component t, Component s) {}
                    @Override public void sendTitleWithFallback(Player p, Component t, Component s, Locale l) {}
                    @Override public void broadcastWithFallback(Component m, Locale l) {}
                };
        var ctor = ChunkLandMessagePipeline.class.getDeclaredConstructor(
                ChunkLandMessagePipeline.PipelineSender.class,
                ChunkLandMessagePipeline.MessageParser.class,
                ChunkLandMessagePipeline.LangProvider.class,
                com.smile.acelib.bedrock.BedrockService.class,
                Locale.class);
        ctor.setAccessible(true);
        return ctor.newInstance(sender, parser, lang, null, Locale.US);
    }

    private static final class EndToEnd {
        final FakeClock clock = new FakeClock();
        final List<Component> delivered = new CopyOnWriteArrayList<>();
        ChunkLandMessagePipeline pipeline;
        RejectionNotifier notifier;

        EndToEnd build() throws Exception {
            pipeline = testPipeline();
            notifier = new RejectionNotifier(
                    new PlayerRegionRejectionSender(PlayerScheduler.direct()),
                    new PipelineRejectionRenderer(pipeline),
                    new RejectionCooldown(clock, Duration.ofSeconds(2)), Set.of());
            return this;
        }

        ProtectionListener listener(LandRegistryStore store,
                EntryProtectionAdapter.BanLookup bans) {
            return new ProtectionListener(
                    engineWithSubjectDefault(store, PermissionState.DENY), notifier, bans);
        }
    }

    private static LandRegistryStore storeWithLandAt(UUID worldId, int chunkX, int chunkZ) {
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(
                List.of(landAt(worldId, new LandId(UUID.randomUUID()), chunkX, chunkZ))));
        return store;
    }

    private static Block blockProxy(World world, int x, int y, int z) {
        Location loc = new Location(world, x, y, z);
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld": return world;
                        case "getX": return x;
                        case "getY": return y;
                        case "getZ": return z;
                        case "getType": return Material.STONE;
                        case "getLocation": return loc;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeBlock";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void moveDeniedRendersEntryTemplate() throws Exception {
        UUID worldId = UUID.randomUUID();
        EndToEnd fx = new EndToEnd().build();
        World world = worldProxy(worldId);
        Player player = actionBarPlayer(UUID.randomUUID(), fx.delivered);
        ProtectionListener listener = fx.listener(storeWithLandAt(worldId, 0, 0), null);
        PlayerMoveEvent event = new PlayerMoveEvent(player,
                new Location(world, 21, 64, 5), new Location(world, 5, 64, 5));
        listener.onPlayerMove(event);
        assertTrue(event.isCancelled(), "ENTRY deny must still cancel");
        assertEquals(1, fx.delivered.size(), "ENTRY deny must notify once");
        assertEquals("You cannot enter this land.", plain(fx.delivered.get(0)));
    }

    @Test
    void teleportDeniedRendersEntryTemplate() throws Exception {
        UUID worldId = UUID.randomUUID();
        EndToEnd fx = new EndToEnd().build();
        World world = worldProxy(worldId);
        Player player = actionBarPlayer(UUID.randomUUID(), fx.delivered);
        ProtectionListener listener = fx.listener(storeWithLandAt(worldId, 0, 0), null);
        PlayerTeleportEvent event = new PlayerTeleportEvent(player,
                new Location(world, 21, 64, 5), new Location(world, 5, 64, 5),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onPlayerTeleport(event);
        assertTrue(event.isCancelled(), "teleport ENTRY deny must still cancel");
        assertEquals(1, fx.delivered.size(), "teleport ENTRY deny must notify once");
        assertEquals("You cannot enter this land.", plain(fx.delivered.get(0)));
    }

    @Test
    void bannedInsideRendersBannedTemplateWithoutEnglishReason() throws Exception {
        UUID worldId = UUID.randomUUID();
        EndToEnd fx = new EndToEnd().build();
        World world = worldProxy(worldId);
        Player player = actionBarPlayer(UUID.randomUUID(), fx.delivered);
        EntryProtectionAdapter.BanLookup bans = (playerId, wid, cx, cz) -> Optional.of(true);
        ProtectionListener listener = fx.listener(storeWithLandAt(worldId, 0, 0), bans);
        PlayerMoveEvent event = new PlayerMoveEvent(player,
                new Location(world, 21, 64, 5), new Location(world, 5, 64, 5));
        listener.onPlayerMove(event);
        assertTrue(event.isCancelled(), "banned-inside stop must still cancel");
        assertEquals(1, fx.delivered.size(), "banned-inside stop must notify once");
        String text = plain(fx.delivered.get(0));
        assertEquals("You are inside a land you are banned from.", text);
        assertFalse(text.contains("Banned inside this land"),
                "the raw English reason must never reach the player");
    }

    @Test
    void nonEntryDenyKeepsSharedTemplate() throws Exception {
        UUID worldId = UUID.randomUUID();
        EndToEnd fx = new EndToEnd().build();
        World world = worldProxy(worldId);
        Player player = actionBarPlayer(UUID.randomUUID(), fx.delivered);
        ProtectionListener listener = fx.listener(storeWithLandAt(worldId, 0, 0), null);
        Block block = blockProxy(world, 5, 64, 5);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);
        assertTrue(event.isCancelled(), "DENY must still cancel");
        assertEquals(1, fx.delivered.size(), "subject DENY must notify once");
        String text = plain(fx.delivered.get(0));
        assertTrue(text.contains("BLOCK_BREAK"), "shared template must name the action: " + text);
    }

    @Test
    void entryAndBannedInsideShareOneCooldownWindow() throws Exception {
        EndToEnd fx = new EndToEnd().build();
        Player player = playerProxy(UUID.randomUUID());
        assertTrue(fx.notifier.notifyDenied(player, ProtectionActionType.ENTRY,
                denySubject("entry denied by rule")));
        assertFalse(fx.notifier.notifyDenied(player, ProtectionActionType.ENTRY,
                new PermissionDecision(PermissionState.DENY,
                        DecisionSource.SUBJECT_PERMISSION,
                        RejectionNotifier.BANNED_INSIDE_REASON)),
                "banned-inside shares the ENTRY window per policy");
        fx.clock.advance(Duration.ofSeconds(2));
        assertTrue(fx.notifier.notifyDenied(player, ProtectionActionType.ENTRY,
                denySubject("entry denied by rule")), "window must reopen at 2s");
    }

    @Test
    void entryAndOtherActionsThrottleIndependently() throws Exception {
        EndToEnd fx = new EndToEnd().build();
        Player player = playerProxy(UUID.randomUUID());
        assertTrue(fx.notifier.notifyDenied(player, ProtectionActionType.ENTRY,
                denySubject("entry denied by rule")));
        assertTrue(fx.notifier.notifyDenied(player, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")), "another action must have its own window");
    }

    // -----------------------------------------------------------------
    // Production wiring helper
    // -----------------------------------------------------------------

    @Test
    void wiringHelperFailsClosedWithoutPipeline() {
        RejectionNotifier notifier = com.smile.chunkland.ChunkLandPlugin
                .buildRejectionNotifier(null, PlayerScheduler.direct(), 2);
        assertNotNull(notifier);
        assertFalse(notifier.notifyDenied(playerProxy(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access")),
                "absent pipeline must stay silent without throwing");
    }

    @Test
    void wiringHelperBuildsLiveNotifier() throws Exception {
        EndToEnd fx = new EndToEnd().build();
        RejectionNotifier notifier = com.smile.chunkland.ChunkLandPlugin
                .buildRejectionNotifier(fx.pipeline, PlayerScheduler.direct(), 2);
        List<Component> delivered = new CopyOnWriteArrayList<>();
        Player player = actionBarPlayer(UUID.randomUUID(), delivered);
        assertTrue(notifier.notifyDenied(player, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")));
        assertEquals(1, delivered.size());
        assertTrue(plain(delivered.get(0)).contains("BLOCK_BREAK"));
    }

    private static Set<String> placeholders(String template) {
        Set<String> names = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<([a-z_]+)>").matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        names.remove("yellow");
        names.remove("red");
        names.remove("green");
        names.remove("gray");
        return names;
    }
}
