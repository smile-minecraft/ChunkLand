package com.smile.chunkland.message;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.message.MessageService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Temporary M0-08 message smoke probe. It wires and observes the AceLib v1.2.0
 * Component + Bedrock-fallback pipeline for the M0 gate only; it is NOT a permanent
 * domain message abstraction (no {@code /land} UI, no custom gateway, no custom Bedrock
 * fallback renderer). Per {@code docs/decisions/D001-message-pipeline.md}, it calls the
 * upstream public API directly.
 *
 * <p>Four channels are wired, each with a Java Component overload and the matching upstream
 * {@code *WithFallback} entry point: chat, actionbar, title, broadcast. Java players always
 * take the Component overload (raw click+hover preserved); Bedrock players always take the
 * upstream {@code *WithFallback} (raw click never sent first). broadcast is server-wide and
 * has no Player, so it uses only {@code broadcastWithFallback} (the Bedrock-aware variant)
 * to avoid a double send.</p>
 *
 * <p>Construction is fail-closed: {@link #tryBuild} returns empty unless a ready
 * {@link AceLibApi} with a non-null {@link BedrockService} is supplied, and any resource
 * load failure during construction is swallowed into an empty result so the plugin never
 * pretends the message service is ready.</p>
 */
public final class M0MessageProbe {

    public static final String SMOKE_KEY = "m0.message.smoke";

    /** Lang fixtures live under the plugin data folder's {@code lang/} directory, matching AceLib's LangManager. */
    static final boolean LANG_RESOURCE_OVERWRITE = false;
    private static final List<String> LANG_RESOURCE_PATHS = List.of("lang/en_US.yml", "lang/zh_TW.yml");

    private final MessageService service;
    private final LangManager lang;
    private final BedrockService bedrock;

    /**
     * Production constructor: only ever called from {@link #tryBuild} with non-null
     * service/lang/bedrock. Package-private second constructor exists solely for unit
     * tests that exercise the fail-closed send paths without a live server.
     */
    M0MessageProbe(MessageService service, LangManager lang, BedrockService bedrock) {
        this.service = service;
        this.lang = lang;
        this.bedrock = bedrock;
    }

    /** Smoke channel selection for {@code /chunkland m0message [locale] [channel]}. */
    public enum Channel {
        CHAT,
        ACTIONBAR,
        TITLE,
        BROADCAST;

        /** Strict parse: only the four known keywords are channels; anything else is a locale, never a silent broadcast. */
        static Optional<Channel> fromString(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "chat" -> Optional.of(CHAT);
                case "actionbar" -> Optional.of(ACTIONBAR);
                case "title" -> Optional.of(TITLE);
                case "broadcast" -> Optional.of(BROADCAST);
                default -> Optional.empty();
            };
        }
    }

    /** Parsed probe arguments: an optional locale override and a channel (defaults to CHAT). */
    public record ProbeArgs(Locale override, Channel channel) {
    }

    /** Server-independent usability check used before any construction. */
    public static boolean isApiUsable(AceLibApi api) {
        return api != null && api.isReady() && api.getBedrockService() != null;
    }

    /**
     * Build the probe after a ready AceLib API is acquired. Returns empty (never throws)
     * when the API is not usable or any resource load fails, so callers can treat "no
     * probe" as "message layer not ready" without special-casing.
     */
    public static Optional<M0MessageProbe> tryBuild(JavaPlugin plugin, AceLibApi api) {
        if (!isApiUsable(api)) {
            return Optional.empty();
        }
        try {
            // Copy bundled lang fixtures into the data folder once, without overwriting any
            // player-edited file (overwrite=false). LangManager resolves them from lang/.
            for (String path : LANG_RESOURCE_PATHS) {
                safeSaveResource(plugin, path, LANG_RESOURCE_OVERWRITE);
            }
            LangManager lang = new LangManager(plugin, Locale.US);
            lang.load();
            BedrockService bedrock = api.getBedrockService();
            MessageService service = new MessageService(plugin, lang, bedrock);
            return Optional.of(new M0MessageProbe(service, lang, bedrock));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void safeSaveResource(JavaPlugin plugin, String resource, boolean overwrite) {
        try {
            plugin.saveResource(resource, overwrite);
        } catch (IllegalArgumentException ignored) {
            // Resource not at this path; LangManager may resolve from the jar instead.
        }
    }

    /**
     * Pure locale-chain resolver (testable without a server). Order:
     * explicit nullable override -> player locale -> Bedrock Floodgate languageCode -> default.
     * Each upstream layer is exception/blank-safe; an unparseable languageCode falls back to default.
     */
    static Locale resolveLocaleChain(Locale override, Locale playerLocale, String bedrockLanguageCode, Locale defaultLocale) {
        if (override != null) {
            return override;
        }
        if (playerLocale != null) {
            return playerLocale;
        }
        if (bedrockLanguageCode != null && !bedrockLanguageCode.isBlank()) {
            Locale fromTag = Locale.forLanguageTag(bedrockLanguageCode.replace('_', '-'));
            if (fromTag != null && !fromTag.getLanguage().isEmpty()) {
                return fromTag;
            }
        }
        return defaultLocale;
    }

    /** Resolve the effective locale for a player, consulting the Bedrock Floodgate languageCode as a fallback. */
    Locale resolveLocale(Player player, Locale override) {
        Locale playerLocale = null;
        if (player != null) {
            try {
                playerLocale = player.locale();
            } catch (RuntimeException ignored) {
                // player.locale() may be unavailable on some runtimes; fall through.
            }
        }
        String bedrockTag = null;
        if (player != null && bedrock != null) {
            try {
                bedrockTag = bedrock.getPlayerInfo(player.getUniqueId())
                    .map(BedrockPlayerInfo::languageCode)
                    .orElse(null);
            } catch (RuntimeException ignored) {
                // Bedrock lookup failed; fall through to default.
            }
        }
        return resolveLocaleChain(override, playerLocale, bedrockTag, Locale.US);
    }

    /** Whether the player should receive the Bedrock fallback path (exception/blank-safe). */
    static boolean useBedrockFallback(BedrockService bedrock, UUID uuid) {
        if (bedrock == null || uuid == null) {
            return false;
        }
        try {
            return bedrock.isBedrockPlayer(uuid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Resolve and parse a rich Component for the given locale override. Variable values are
     * passed raw to {@link MessageService#parseMiniMessage(String, Map)} so AceLib unparses
     * them (no MiniMessage tag injection from caller-supplied values). Key information is
     * expected to live in the template body, not only in hover (see D001裁定 4).
     */
    public Component renderRich(Player player, Locale override) {
        Locale locale = resolveLocale(player, override);
        Optional<String> template = lang.get(locale, SMOKE_KEY);
        if (template.isEmpty()) {
            template = lang.get(SMOKE_KEY);
        }
        Map<String, Object> vars = Map.of("value", "M0-smoke-123");
        return template.map(t -> service.parseMiniMessage(t, vars))
            .orElseGet(() -> Component.text("missing template: " + SMOKE_KEY));
    }

    /**
     * Isolated ALLOW/silent contract guard. When the protection decision is ALLOW the
     * message layer must NOT be touched (zero message cost); this returns false without
     * rendering or sending. DENY renders via the Bedrock-fallback chat path.
     *
     * <p>NOTE: this models only the contract; the real M2 protection engine is not wired
     * here. See docs/verification/m0-message-smoke.md.</p>
     */
    public boolean dispatch(boolean allow, Player player, Locale locale) {
        if (allow) {
            return false;
        }
        requireReady();
        Component rich = renderRich(player, locale);
        service.sendChatWithFallback(player, rich, locale);
        return true;
    }

    /**
     * Smoke probe: send via exactly ONE channel, chosen by the parsed {@link Channel}, and
     * within that channel exactly ONE path chosen by Bedrock detection. Java players get the
     * original Component overload (click+hover preserved, no fallback); Bedrock players get
     * only the upstream {@code *WithFallback} (raw click Component is never sent first). No
     * double-send to the same player. broadcast is server-wide and uses only
     * {@code broadcastWithFallback}.
     */
    public void sendProbe(Player player, Locale override, Channel channel) {
        requireReady();
        switch (channel) {
            case CHAT -> sendChatProbe(player, override);
            case ACTIONBAR -> sendActionBarProbe(player, override);
            case TITLE -> sendTitleProbe(player, override);
            case BROADCAST -> sendBroadcastProbe(override);
        }
    }

    private void sendChatProbe(Player player, Locale override) {
        Component rich = renderRich(player, override);
        if (useBedrockFallback(bedrock, player.getUniqueId())) {
            service.sendChatWithFallback(player, rich, resolveLocale(player, override));
        } else {
            service.sendChat(player, rich);
        }
    }

    private void sendActionBarProbe(Player player, Locale override) {
        Component rich = renderRich(player, override);
        if (useBedrockFallback(bedrock, player.getUniqueId())) {
            service.sendActionBarWithFallback(player, rich, resolveLocale(player, override));
        } else {
            service.sendActionBar(player, rich);
        }
    }

    private void sendTitleProbe(Player player, Locale override) {
        Component title = renderRich(player, override);
        Component subtitle = Component.text("M0 title subtitle");
        if (useBedrockFallback(bedrock, player.getUniqueId())) {
            service.sendTitleWithFallback(player, title, subtitle, resolveLocale(player, override));
        } else {
            service.sendTitle(player, title, subtitle);
        }
    }

    private void sendBroadcastProbe(Locale override) {
        // Server-wide: no single Player to split on. Use only the Bedrock-aware variant so
        // Java and Bedrock players are both reached without a second broadcast call.
        Component rich = renderRich(null, override);
        service.broadcastWithFallback(rich, resolveLocale(null, override));
    }

    /** Parse {@code /chunkland m0message [locale] [channel]} args into a locale override + channel (default CHAT). */
    static ProbeArgs parseProbeArgs(String[] args) {
        Locale override = null;
        Channel channel = Channel.CHAT;
        if (args != null) {
            for (int i = 1; i < args.length; i++) {
                String token = args[i];
                Optional<Channel> parsedChannel = Channel.fromString(token);
                if (parsedChannel.isPresent()) {
                    channel = parsedChannel.get();
                } else {
                    Locale loc = parseLocale(token);
                    if (override == null) {
                        override = loc;
                    }
                }
            }
        }
        return new ProbeArgs(override, channel);
    }

    /** Temporary {@code /chunkland m0message} command handler. */
    public static boolean handleCommand(CommandSender sender, String[] args, Optional<M0MessageProbe> probe) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("M0 訊息管線冒煙需要玩家執行（需觀察 Adventure Component / Bedrock fallback）。");
            return true;
        }
        ProbeArgs parsed = parseProbeArgs(args);
        Locale locale = parsed.override();
        Channel channel = parsed.channel();
        if (probe.isEmpty()) {
            sender.sendMessage("ChunkLand 訊息管線尚未就緒（AceLib 未 ready 或資源載入失敗）。");
            return true;
        }
        probe.get().sendProbe(player, locale, channel);
        if (channel == Channel.BROADCAST) {
            sender.sendMessage("已發送 M0 訊息管線冒煙（broadcast 頻道，廣播給伺服器玩家）。");
        } else {
            sender.sendMessage("已發送 M0 訊息管線冒煙（" + channel.name().toLowerCase(Locale.ROOT)
                + " 頻道）；Java 玩家收到原始 Component，Bedrock 玩家收到 fallback 路徑。");
        }
        return true;
    }

    private static Locale parseLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Locale.forLanguageTag(raw.replace('_', '-'));
    }

    private void requireReady() {
        if (service == null) {
            throw new IllegalStateException("ChunkLand message pipeline is not ready");
        }
    }
}
