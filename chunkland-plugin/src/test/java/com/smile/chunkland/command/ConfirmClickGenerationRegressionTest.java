package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Debugger regression: click payload must be executable and
 * replacement sessions must not accept old numeric revisions.
 */
class ConfirmClickGenerationRegressionTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class Reply {
        final String key;
        final Map<String, Object> vars;
        Reply(String key, Map<String, Object> vars) {
            this.key = key;
            this.vars = Map.copyOf(vars);
        }
    }

    static final class LatchSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;
        LatchSink(int expected) { this.latch = new CountDownLatch(expected); }
        @Override public void reply(String k, Map<String, Object> v) {
            replies.add(new Reply(k, v));
            latch.countDown();
        }
        @Override public void reply(String k, Map<String, Object> v, Locale l) { reply(k, v); }
        void await() throws Exception {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "handler must reply");
        }
    }

    static final class CapturingRunner implements ClaimCommandHandler.ClaimRunner {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<ClaimRequest> last = new AtomicReference<>();
        volatile ClaimOutcome next = ClaimOutcome.success(new LandId(UUID.randomUUID()));
        @Override public CompletionStage<ClaimOutcome> claim(ClaimRequest r) {
            calls.incrementAndGet();
            last.set(r);
            return CompletableFuture.completedFuture(next);
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class[]{Player.class}, (proxy, method, args) -> {
                String n = method.getName();
                if (n.equals("getUniqueId")) return id;
                if (n.equals("hasPermission")) return true;
                if (n.equals("getName")) return "TestPlayer";
                if (n.equals("equals") && args != null && args.length == 1) return proxy == args[0];
                if (n.equals("hashCode")) return System.identityHashCode(proxy);
                if (n.equals("toString")) return "Player-proxy:" + id;
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                return null;
            });
    }

    private static SelectionSessionManager selections() {
        return new SelectionSessionManager(
            (pid, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
            SelectionVisualizationTaskController.noop(),
            SelectionNotifier.noop(),
            (SelectionClock) () -> NOW,
            Duration.ofMinutes(10),
            ignored -> Optional.of("world"),
            SelectionStructureRevisionLookup.unavailable());
    }

    private static SelectionSession selectSingleChunk(SelectionSessionManager m, UUID actor, UUID world) {
        SelectionSession initial = SelectionSession.initial(actor, world, SelectionMode.CREATE_LAND,
            Optional.empty(), Optional.empty(),
            Optional.of(new SelectionPoint(world, 0, 64, 0)),
            Optional.of(new SelectionPoint(world, 16, 64, 16)), 0, NOW);
        SelectionSession stamped = m.start(initial);
        return m.updateSelection(actor, stamped, new SelectionUpdate(stamped.pointA(), stamped.pointB(),
                Set.of(new ChunkKey(world, 3, 4)), Map.of()))
            .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
    }

    private static ChunkLandMessagePipeline pipeline() throws Exception {
        Map<Locale, Map<String, String>> data = new java.util.HashMap<>();
        for (String tag : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
            Map<String, String> map = new java.util.HashMap<>();
            for (String key : cfg.getKeys(true)) {
                Object v = cfg.get(key);
                if (v instanceof String s) map.put(key, s);
            }
            Locale loc = tag.equals("en_US") ? Locale.US : Locale.forLanguageTag("zh-TW");
            data.put(loc, map);
            data.put(new Locale("en", "US"), data.getOrDefault(Locale.US, map));
        }
        ChunkLandMessagePipeline.LangProvider provider = (locale, key) -> {
            Map<String, String> mm = data.get(locale);
            if (mm != null && mm.containsKey(key)) return Optional.of(mm.get(key));
            Map<String, String> def = data.get(Locale.US);
            if (def != null && def.containsKey(key)) return Optional.of(def.get(key));
            return Optional.empty();
        };
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver;
            if (vars == null || vars.isEmpty()) resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty();
            else {
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] rs =
                    new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[vars.size()];
                int i = 0;
                for (var e : vars.entrySet())
                    rs[i++] = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                        e.getKey(), String.valueOf(e.getValue()));
                resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(rs);
            }
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(template, resolver);
        };
        ChunkLandMessagePipeline.PipelineSender sender = new ChunkLandMessagePipeline.PipelineSender() {
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
        return (ChunkLandMessagePipeline) ctor.newInstance(sender, parser, provider, null, Locale.US);
    }

    private static String findClick(Component root) {
        if (root.clickEvent() != null) return root.clickEvent().value();
        for (Component child : root.children()) {
            String n = findClick(child);
            if (n != null) return n;
        }
        return null;
    }

    @Test
    void renderedClickIsExecutableAndReachesHandler() throws Exception {
        ChunkLandMessagePipeline pipe = pipeline();
        SelectionSessionManager manager = selections();
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        SelectionSession live = selectSingleChunk(manager, actor, world);
        Component rendered = pipe.render("land.claim.confirm",
            Map.of("land_name", "Home", "chunk_count", "1", "price", "$10",
                "conflict_count", "0", "min_y", "59",
                "generation", String.valueOf(live.sessionGeneration()),
                "revision", String.valueOf(live.selectionRevision())),
            Locale.US, null);
        String click = findClick(rendered);
        assertNotNull(click, "confirm must keep run_command click");
        assertEquals("/land confirm " + live.sessionGeneration() + " " + live.selectionRevision() + " Home", click,
                "click must carry the executable generation, revision and land name");
        assertFalse(click.contains("<"), "click must not keep placeholders: " + click);
        // Click execution seam: parse click command and dispatch to handler.
        String body = click.trim();
        assertTrue(body.startsWith("/land "));
        String[] parts = body.substring("/land ".length()).trim().split("\\s+");
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler = new ConfirmCommandHandler(manager, runner, null,
            SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);
        LandCommand cmd = new LandCommand(Map.of("confirm", handler), (s, p) -> sink);
        assertTrue(cmd.dispatch(player(actor), parts, null));
        sink.await();
        assertEquals(1, runner.calls.get(), "executable click must enter saga, got replies=" + sink.replies.get(0).key);
        assertEquals(live.sessionGeneration(), runner.last.get().sessionGeneration());
        assertEquals(live.selectionRevision(), runner.last.get().selectionRevision());
        assertEquals("Home", runner.last.get().displayName());
    }

    @Test
    void replacementWithReusedNumericRevisionRejectsOldToken() throws Exception {
        SelectionSessionManager manager = selections();
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        SelectionSession first = selectSingleChunk(manager, actor, world);
        long staleGeneration = first.sessionGeneration();
        long staleToken = first.selectionRevision();
        // Replacement that reuses the same numeric revision (fresh session edited once).
        SelectionSession replacement = selectSingleChunk(manager, actor, world);
        assertEquals(staleToken, replacement.selectionRevision(),
            "test needs numeric revision reuse to prove generation binding");
        assertNotEquals(staleGeneration, replacement.sessionGeneration(),
            "replacement must advance the generation");
        CapturingRunner runner = new CapturingRunner();
        ConfirmCommandHandler handler = new ConfirmCommandHandler(manager, runner, null,
            SelectionStructureRevisionLookup.unavailable());
        LatchSink sink = new LatchSink(1);
        LandCommand cmd = new LandCommand(Map.of("confirm", handler), (s, p) -> sink);
        assertTrue(cmd.dispatch(player(actor),
            new String[]{"confirm", Long.toString(staleGeneration), Long.toString(staleToken), "Home"}, null));
        sink.await();
        assertEquals(0, runner.calls.get(),
            "old token with reused numeric revision must not enter saga");
        assertEquals("command.land.confirm.stale", sink.replies.get(0).key);
        // Validator level: the same old ClaimRequest must be rejected even
        // though its numeric revision matches the live session.
        com.smile.chunkland.claim.SnapshotClaimValidator validator =
            new com.smile.chunkland.claim.SnapshotClaimValidator(
                new com.smile.chunkland.runtime.index.LandRegistryStore(),
                actorUuid -> manager.sessionFor(actorUuid)
                    .map(session -> java.util.OptionalLong.of(session.selectionRevision()))
                    .orElseGet(java.util.OptionalLong::empty),
                actorUuid -> manager.sessionFor(actorUuid)
                    .map(session -> java.util.OptionalLong.of(session.sessionGeneration()))
                    .orElseGet(java.util.OptionalLong::empty),
                chunk -> 64,
                owner -> 0L,
                com.smile.chunkland.claim.ClaimValidator.StructureRevisionSource.none());
        com.smile.chunkland.claim.ClaimRequest oldRequest = new com.smile.chunkland.claim.ClaimRequest(
            com.smile.chunkland.api.land.OwnerRef.player(actor), actor, world,
            replacement.selectedChunks(), "Home", staleToken, staleGeneration);
        com.smile.chunkland.claim.ClaimRejectedException rejected = assertThrows(
            com.smile.chunkland.claim.ClaimRejectedException.class, () -> validator.validate(oldRequest));
        assertEquals("selection.stale", rejected.diagnosticKey());
    }

    @Test
    void maliciousLandNameDoesNotInject() throws Exception {
        ChunkLandMessagePipeline pipe = pipeline();
        String evil = "<red>evil</red>";
        Component rendered = pipe.render("land.claim.confirm",
            Map.of("land_name", evil, "chunk_count", "1", "price", "$10",
                "conflict_count", "0", "min_y", "59", "generation", "0", "revision", "1"),
            Locale.US, null);
        String click = findClick(rendered);
        assertNotNull(click);
        // Click payload must not turn the evil name into executable MiniMessage tags;
        // it must stay a single confirm command carrying the literal name.
        assertEquals("/land confirm 0 1 " + evil, click,
            "evil name must ride literally in a single confirm command, never parsed");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(rendered);
        assertTrue(plain.contains(evil), "evil name must render literally in body text");
        // Control characters and blank names fail closed before any click exists.
        Map<String, Object> control = Map.of("land_name", "Ho\nme", "chunk_count", "1", "price", "$10",
                "conflict_count", "0", "min_y", "59", "generation", "0", "revision", "1");
        assertThrows(com.smile.chunkland.message.MessageException.class,
                () -> pipe.render("land.claim.confirm", control, Locale.US, null),
                "control characters must fail closed");
        Map<String, Object> blank = Map.of("land_name", "   ", "chunk_count", "1", "price", "$10",
                "conflict_count", "0", "min_y", "59", "generation", "0", "revision", "1");
        assertThrows(com.smile.chunkland.message.MessageException.class,
                () -> pipe.render("land.claim.confirm", blank, Locale.US, null),
                "blank land name must fail closed");
    }
}
