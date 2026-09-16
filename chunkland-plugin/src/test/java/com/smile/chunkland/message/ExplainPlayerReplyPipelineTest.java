package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormService;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.command.ExplainCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.protection.ConfigSubjectPermissionLookup;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsSnapshot;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Player-facing regression for {@code /land explain}: the real
 * {@link LandCommand#dispatch} path must deliver a chat response to a Player
 * through the real {@code PipelineReplySink} and the production
 * {@link ChunkLandMessagePipeline}, whether the handler produces a result or a
 * fail-closed generic denial.
 *
 * <p>Why the earlier handler tests missed it: they injected a {@code ReplySink}
 * that only captured {@code (key, vars)} and never rendered, so the result vars
 * {@code coveringSubLandId} / {@code isOwner} / {@code adminBypass} were never
 * handed to MiniMessage. MiniMessage placeholder names must match
 * {@code [!?#]?[a-z0-9_-]*}, so a mixed-case var key throws while AceLib's
 * {@code MessageService#parseMiniMessage} builds the placeholder resolver. That
 * runtime exception is wrapped by {@link ChunkLandMessagePipeline#render} into a
 * {@link MessageException} and then swallowed by {@code PipelineReplySink}, so
 * an authorized player saw nothing. This test uses the pipeline's explicit
 * parser seam with a parser that mirrors AceLib's resolver construction, so the
 * case-rule boundary is exercised end to end without a live server.</p>
 */
class ExplainPlayerReplyPipelineTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final String EXPLAIN_PERM = "chunkland.command.land.explain";
    private static final String DENIED_TEXT = "Explain unavailable.";

    /** Strong references for proxy worlds held weakly by Paper Location. */
    private static final List<World> PINNED_WORLDS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Records every Component the pipeline hands to a player. */
    private static final class PlayerCapture {
        final List<Component> messages = new ArrayList<>();
    }

    private record Fixture(LandRegistryStore store, LandId landId,
            SnapshotPermissionContextProvider provider) {
    }

    /**
     * Mirrors AceLib {@code MessageService#parseMiniMessage}: the placeholder
     * resolver is built from the caller's raw var keys, and an illegal tag name
     * fails closed instead of being sanitised.
     */
    private static final class AceLibLikeParser implements ChunkLandMessagePipeline.MessageParser {
        @Override
        public Component parse(String template, Map<String, Object> vars) {
            TagResolver resolver;
            if (vars == null || vars.isEmpty()) {
                resolver = TagResolver.empty();
            } else {
                TagResolver[] resolvers = new TagResolver[vars.size()];
                int i = 0;
                for (Map.Entry<String, Object> entry : vars.entrySet()) {
                    resolvers[i++] = Placeholder.unparsed(
                            entry.getKey(), String.valueOf(entry.getValue()));
                }
                resolver = TagResolver.resolver(resolvers);
            }
            return MiniMessage.miniMessage().deserialize(template, resolver);
        }
    }

    /** Records chat sends; mirrors the AceLib component send seam. */
    private static final class RecordingSender implements ChunkLandMessagePipeline.PipelineSender {
        final PlayerCapture capture;
        final boolean failChat;

        RecordingSender(PlayerCapture capture, boolean failChat) {
            this.capture = capture;
            this.failChat = failChat;
        }

        @Override
        public void sendChat(Player player, Component message) {
            if (failChat) {
                throw new IllegalStateException("transport exploded");
            }
            capture.messages.add(message);
        }

        @Override
        public void sendChatWithFallback(Player player, Component message, Locale locale) {
            sendChat(player, message);
        }

        @Override
        public void sendActionBar(Player player, Component message) {
        }

        @Override
        public void sendActionBarWithFallback(Player player, Component message, Locale locale) {
        }

        @Override
        public void sendTitle(Player player, Component title, Component subtitle) {
        }

        @Override
        public void sendTitleWithFallback(Player player, Component title, Component subtitle,
                Locale locale) {
        }

        @Override
        public void broadcastWithFallback(Component message, Locale locale) {
        }
    }

    private static final class NoBedrockService implements BedrockService {
        @Override
        public boolean isBedrockPlayer(UUID uuid) {
            return false;
        }

        @Override
        public Optional<BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
            return Optional.empty();
        }

        @Override
        public FormService forms() {
            return null;
        }

        @Override
        public String getModuleStatus() {
            return "";
        }

        @Override
        public void shutdown() {
        }
    }

    private static ChunkLandMessagePipeline.LangProvider langProvider() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/en_US.yml"));
        Map<String, String> map = new HashMap<>();
        for (String key : cfg.getKeys(true)) {
            Object value = cfg.get(key);
            if (value instanceof String s) {
                map.put(key, s);
            }
        }
        return (locale, key) -> Optional.ofNullable(map.get(key));
    }

    private static ChunkLandMessagePipeline pipeline(RecordingSender sender) throws Exception {
        return new ChunkLandMessagePipeline(sender, new AceLibLikeParser(), langProvider(),
                new NoBedrockService(), Locale.US);
    }

    private static World proxyWorld(UUID uid) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        return uid;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "World-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == double.class) {
                        return 0d;
                    }
                    return null;
                });
        PINNED_WORLDS.add(world);
        return world;
    }

    private static Player player(UUID uuid, UUID worldId, double x, double y, double z,
            Map<String, Boolean> perms) {
        Location location = new Location(proxyWorld(worldId), x, y, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("getLocation")) {
                        return location;
                    }
                    if (name.equals("locale")) {
                        return Locale.US;
                    }
                    if (name.equals("isOnline")) {
                        return true;
                    }
                    if (name.equals("hasPermission")) {
                        Object node = args == null || args.length == 0 ? null : args[0];
                        return node instanceof String perm && perms.getOrDefault(perm, false);
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Producer";
                    }
                    if (name.equals("sendMessage")) {
                        return null;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Producer-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == double.class) {
                        return 0d;
                    }
                    return null;
                });
    }

    private static Fixture fixture() {
        LandId landId = new LandId(UUID.randomUUID());
        LandName name = LandName.of("Home");
        LandSnapshot land = new LandSnapshot(landId, name.displayName(), name.nameKey(),
                OwnerRef.player(OWNER), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)), List.of(),
                0L, 0L, Instant.EPOCH, Instant.EPOCH);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land)));
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(PermissionDefaultsSnapshot::empty,
                        LandAuthorisationSnapshot::empty));
        return new Fixture(store, landId, provider);
    }

    private static ManagementGateResolver ownerResolver(Fixture fixture) {
        return (sender, action, args) -> Optional.of(new ManagementGateResolver.Request(
                OWNER, fixture.landId(), fixture.store().snapshot(), false, false,
                fixture.provider()));
    }

    private static ExplainCommandHandler handler(Fixture fixture) {
        return new ExplainCommandHandler(fixture.store()::snapshot, fixture::provider);
    }

    private static boolean dispatch(Player player, ExplainCommandHandler handler,
            ManagementGateResolver resolver, ChunkLandMessagePipeline pipeline, String action) {
        LandCommand cmd = new LandCommand(Map.of("explain", handler), null, resolver);
        return cmd.dispatch(player, new String[] {"explain", action}, pipeline);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void authorizedPlayerSeesResultThroughProductionPlayerPath() throws Exception {
        Fixture fixture = fixture();
        PlayerCapture capture = new PlayerCapture();
        ChunkLandMessagePipeline pipeline = pipeline(new RecordingSender(capture, false));
        Player player = player(OWNER, WORLD, 5, 64, 5, Map.of(EXPLAIN_PERM, true));

        assertTrue(dispatch(player, handler(fixture), ownerResolver(fixture), pipeline,
                "BLOCK_PLACE"));

        assertEquals(1, capture.messages.size(),
                "authorized explain must reach the player through the real pipeline");
        String rendered = plain(capture.messages.get(0));
        assertTrue(rendered.contains("BLOCK_PLACE"),
                "player result must name the explained action: " + rendered);
        assertTrue(rendered.contains("Covering"),
                "player result must render the covering summary: " + rendered);
        assertFalse(rendered.contains("command.land.explain"),
                "player must never see the raw message key: " + rendered);
    }

    @Test
    void missingCommandPermissionStaysGenericDeniedForPlayer() throws Exception {
        Fixture fixture = fixture();
        PlayerCapture capture = new PlayerCapture();
        ChunkLandMessagePipeline pipeline = pipeline(new RecordingSender(capture, false));
        Player player = player(OWNER, WORLD, 5, 64, 5, Map.of());

        assertTrue(dispatch(player, handler(fixture), ownerResolver(fixture), pipeline,
                "BLOCK_PLACE"));

        assertEquals(1, capture.messages.size(),
                "missing permission must still produce a visible denial");
        String rendered = plain(capture.messages.get(0));
        assertTrue(rendered.contains(DENIED_TEXT), "expected generic denial: " + rendered);
        assertFalse(rendered.contains(EXPLAIN_PERM),
                "denial must not leak the permission node: " + rendered);
    }

    @Test
    void gateDenyStaysGenericDeniedForPlayer() throws Exception {
        Fixture fixture = fixture();
        PlayerCapture capture = new PlayerCapture();
        ChunkLandMessagePipeline pipeline = pipeline(new RecordingSender(capture, false));
        Player player = player(OWNER, WORLD, 5, 64, 5, Map.of(EXPLAIN_PERM, true));

        ManagementGateResolver denying = (sender, action, args) -> Optional.empty();
        assertTrue(dispatch(player, handler(fixture), denying, pipeline, "BLOCK_PLACE"));

        assertEquals(1, capture.messages.size(),
                "domain-gate deny must still produce a visible denial");
        String rendered = plain(capture.messages.get(0));
        assertTrue(rendered.contains(DENIED_TEXT), "expected generic denial: " + rendered);
        assertFalse(rendered.contains("MANAGE_PERMISSION"),
                "denial must not leak the action: " + rendered);
    }

    @Test
    void handlerDenialReachesPlayerAsGenericDenied() throws Exception {
        Fixture fixture = fixture();
        PlayerCapture capture = new PlayerCapture();
        ChunkLandMessagePipeline pipeline = pipeline(new RecordingSender(capture, false));
        Player player = player(OWNER, WORLD, 5, 64, 5, Map.of(EXPLAIN_PERM, true));

        assertTrue(dispatch(player, handler(fixture), ownerResolver(fixture), pipeline,
                "FLY_FOREVER"));

        assertEquals(1, capture.messages.size(),
                "handler-level denial must still produce a visible denial");
        assertTrue(plain(capture.messages.get(0)).contains(DENIED_TEXT));
    }

    @Test
    void playerSendFailureDoesNotEscapeAndDoesNotLeak() throws Exception {
        Fixture fixture = fixture();
        PlayerCapture capture = new PlayerCapture();
        ChunkLandMessagePipeline pipeline = pipeline(new RecordingSender(capture, true));
        Player player = player(OWNER, WORLD, 5, 64, 5, Map.of(EXPLAIN_PERM, true));

        assertTrue(dispatch(player, handler(fixture), ownerResolver(fixture), pipeline,
                "BLOCK_PLACE"), "a sender failure must not break dispatch");
        assertTrue(capture.messages.isEmpty(),
                "a failed send must stay fail-closed, never a raw-key fallback");
    }
}
