package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.command.AdminBypassCommandHandler;
import com.smile.chunkland.command.AdminBypassState;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.command.PipelineReplySink;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.AuditSearchQuery;
import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Render contract of the bypass success terminals through a real sink, a
 * real pipeline and the real language files.
 *
 * <p>The language files must declare the success entries under quoted
 * {@code "on":}/{@code "off":}: unquoted, YAML 1.1 loads them as boolean
 * keys {@code true}/{@code false}, the semantic lookup misses, and the
 * success reply drops silently.
 */
class BypassReplyRenderTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String BYPASS_NODE = "chunkland.admin.bypass";

    private static ChunkLandMessagePipeline pipelineWithSender(
            ChunkLandMessagePipelineTest.CountingSender sender, Locale def) {
        ChunkLandMessagePipeline.LangProvider provider;
        try {
            provider = buildLangProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ChunkLandMessagePipelineTest.FakeBedrockService bedrock =
                new ChunkLandMessagePipelineTest.FakeBedrockService(false);
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            TagResolver resolver;
            if (vars == null || vars.isEmpty()) {
                resolver = TagResolver.empty();
            } else {
                TagResolver[] rs = new TagResolver[vars.size()];
                int i = 0;
                for (var e : vars.entrySet()) {
                    rs[i++] = Placeholder.unparsed(e.getKey(), String.valueOf(e.getValue()));
                }
                resolver = TagResolver.resolver(rs);
            }
            return MiniMessage.miniMessage().deserialize(template, resolver);
        };
        return new ChunkLandMessagePipeline(sender, parser, provider, bedrock, def);
    }

    private static ChunkLandMessagePipeline.LangProvider buildLangProvider() throws Exception {
        Map<Locale, Map<String, String>> data = new java.util.HashMap<>();
        for (String localeTag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
            Map<String, String> map = new java.util.HashMap<>();
            for (String key : cfg.getKeys(true)) {
                Object val = cfg.get(key);
                if (val instanceof String s) {
                    map.put(key, s);
                }
            }
            Locale loc = localeTag.equals("en_US") ? Locale.US : Locale.forLanguageTag("zh-TW");
            data.put(loc, map);
            data.put(Locale.forLanguageTag(localeTag.replace('_', '-')), map);
        }
        data.put(new Locale("en", "US"), data.get(Locale.US));
        return (locale, key) -> {
            Map<String, String> m = data.get(locale);
            if (m != null && m.containsKey(key)) {
                return Optional.of(m.get(key));
            }
            Map<String, String> defMap = data.get(Locale.US);
            if (defMap != null && defMap.containsKey(key)) {
                return Optional.of(defMap.get(key));
            }
            return Optional.empty();
        };
    }

    private static Player playerWithLocaleAndPermission(UUID uuid, Locale locale) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return uuid;
                        case "hasPermission":
                            return args != null && args.length > 0 && BYPASS_NODE.equals(args[0]);
                        case "locale":
                            return locale;
                        case "isOnline":
                            return true;
                        case "getName":
                            return "Bypass-render";
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "Bypass-render-proxy";
                        default:
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
                            if (rt == float.class) {
                                return 0f;
                            }
                            return null;
                    }
                });
    }

    private static final class DeferredScheduler implements PlayerScheduler {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void runForPlayer(Player player, Runnable task) {
            pending.add(task);
        }

        void drain() {
            List<Runnable> due = List.copyOf(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    private static final class AsyncAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final AtomicInteger ids = new AtomicInteger();
        final ExecutorService persistence = Executors.newSingleThreadExecutor();

        @Override
        public CompletionStage<Long> insert(AuditEntry entry) {
            CompletableFuture<Long> result = new CompletableFuture<>();
            persistence.execute(() -> {
                try {
                    AuditEntry stored = new AuditEntry(ids.incrementAndGet(), entry.timestamp(),
                            entry.actor(), entry.action(), entry.landId(), entry.worldId(),
                            entry.singleChunkPacked(), entry.metadataVersion(), entry.beforeJson(),
                            entry.afterJson(), entry.metadataJson(), entry.chunks());
                    inserted.add(stored);
                    result.complete(stored.id());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        @Override
        public CompletionStage<Optional<AuditEntry>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override
        public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override
        public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
            return CompletableFuture.completedFuture(0);
        }

        void shutdown() {
            persistence.shutdownNow();
        }
    }

    private static int sendChat(String key, Map<String, Object> vars, Locale playerLocale,
            Locale override, Locale def) throws Exception {
        ChunkLandMessagePipelineTest.CountingSender sender =
                new ChunkLandMessagePipelineTest.CountingSender();
        ChunkLandMessagePipeline pipeline = pipelineWithSender(sender, def);
        Player player = playerWithLocaleAndPermission(UUID.randomUUID(), playerLocale);
        PipelineReplySink sink = new PipelineReplySink(player, pipeline);
        if (override == null) {
            sink.reply(key, vars);
        } else {
            sink.reply(key, vars, override);
        }
        return sender.chatCalls;
    }

    @Test
    void unquotedOnOffLoadAsBooleanKeys() throws Exception {
        YamlConfiguration snippet = new YamlConfiguration();
        snippet.loadFromString("probe:\n  on: \"x\"\n  off: \"y\"\n");
        assertTrue(snippet.get("probe.on") == null && snippet.get("probe.true") != null,
                "unquoted on: must keep loading as boolean true; quote keys in language files");
        assertTrue(snippet.get("probe.off") == null && snippet.get("probe.false") != null,
                "unquoted off: must keep loading as boolean false; quote keys in language files");
    }

    @Test
    void semanticKeysRenderInBothLocales() throws Exception {
        ChunkLandMessagePipelineTest.CountingSender sender =
                new ChunkLandMessagePipelineTest.CountingSender();
        ChunkLandMessagePipeline pipeline = pipelineWithSender(sender, Locale.US);
        Player player = playerWithLocaleAndPermission(UUID.randomUUID(), Locale.US);
        for (Locale effective : List.of(Locale.US, Locale.forLanguageTag("zh-TW"))) {
            assertTrue(!pipeline.render("command.land.bypass.on", Map.of(), effective, player)
                    .equals(net.kyori.adventure.text.Component.empty()));
            assertTrue(!pipeline.render("command.land.bypass.off", Map.of(), effective, player)
                    .equals(net.kyori.adventure.text.Component.empty()));
        }
        for (Locale playerLocale : List.of(Locale.US, Locale.forLanguageTag("zh-TW"))) {
            for (Locale override : new Locale[] {null, Locale.US, Locale.forLanguageTag("zh-TW"),
                    Locale.FRANCE, Locale.ROOT}) {
                assertEquals(1,
                        sendChat("command.land.bypass.on", Map.of(),
                                playerLocale, override, Locale.US),
                        "on success must render: player=" + playerLocale + " override=" + override);
                assertEquals(1,
                        sendChat("command.land.bypass.off", Map.of(),
                                playerLocale, override, Locale.US),
                        "off success must render: player=" + playerLocale + " override=" + override);
            }
        }
        assertEquals(1, sendChat("command.land.bypass.failed",
                Map.of("reason", "bypass.failed"), Locale.US, Locale.US, Locale.US));
        assertEquals(1, sendChat("command.land.admin.ledger.line",
                Map.of("value", "type=CLAIM op=abc state=ACTIVE"), Locale.US, Locale.US, Locale.US));
    }

    @Test
    void handlerDeliversOneChatPerToggleThroughRealSink() throws Exception {
        ChunkLandMessagePipelineTest.CountingSender sender =
                new ChunkLandMessagePipelineTest.CountingSender();
        ChunkLandMessagePipeline pipeline = pipelineWithSender(sender, Locale.US);
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        AsyncAudits audits = new AsyncAudits();
        DeferredScheduler scheduler = new DeferredScheduler();
        AdminBypassCommandHandler handler = new AdminBypassCommandHandler(
                () -> audits, () -> NOW, states, scheduler);
        Player player = playerWithLocaleAndPermission(actor, Locale.US);
        ReplySink sink = new PipelineReplySink(player, pipeline);
        try {
            handler.handle(player, new String[] {"bypass", "on"}, sink);
            handler.handle(player, new String[] {"bypass", "off"}, sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline
                    && (sinkDelivered(sender) < 2 || audits.inserted.size() < 4)) {
                scheduler.drain();
                Thread.sleep(10);
            }
            scheduler.drain();
            assertEquals(4, audits.inserted.size(), "on plus off must write two pairs");
            assertEquals(2, sinkDelivered(sender), "on plus off must deliver exactly one chat each");
            assertTrue(!states.isOn(actor), "the off toggle must win");
        } finally {
            audits.shutdown();
        }
    }

    private static int sinkDelivered(ChunkLandMessagePipelineTest.CountingSender sender) {
        return sender.chatCalls + sender.chatFallbackCalls;
    }
}
