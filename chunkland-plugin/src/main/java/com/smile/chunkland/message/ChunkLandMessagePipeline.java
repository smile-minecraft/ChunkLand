package com.smile.chunkland.message;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.message.MessageService;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin-internal message pipeline.
 *
 * <p>Callers supply only {@code messageKey + Map<String,Object> + nullable Locale override};
 * the pipeline owns {@link LangManager}, {@link MessageService} and {@link BedrockService}
 * so no caller touches MiniMessage or fallback rendering directly.</p>
 *
 * <p>Template caching is per {@code (messageKey, locale)} and stores the raw template
 * string only — never a pre-rendered Component that could leak player-specific vars
 * across players. Every call with vars renders a fresh Component.</p>
 *
 * <p>Unknown keys, missing resources and malformed MiniMessage are fail-closed
 * via {@link MessageException} — never silent {@code Component.empty()}.</p>
 */
public class ChunkLandMessagePipeline {

    static final boolean LANG_RESOURCE_OVERWRITE = false;
    private static final java.util.List<String> LANG_RESOURCE_PATHS = java.util.List.of("lang/en_US.yml", "lang/zh_TW.yml");

    /** Seam for message dispatch. */
    public interface PipelineSender {
        void sendChat(Player player, Component message);
        void sendChatWithFallback(Player player, Component message, Locale locale);
        void sendActionBar(Player player, Component message);
        void sendActionBarWithFallback(Player player, Component message, Locale locale);
        void sendTitle(Player player, Component title, Component subtitle);
        void sendTitleWithFallback(Player player, Component title, Component subtitle, Locale locale);
        void broadcastWithFallback(Component message, Locale locale);
    }

    static final class MessageServiceSender implements PipelineSender {
        private final MessageService delegate;
        MessageServiceSender(MessageService delegate) { this.delegate = delegate; }
        @Override public void sendChat(Player p, Component m) { delegate.sendChat(p, m); }
        @Override public void sendChatWithFallback(Player p, Component m, Locale l) { delegate.sendChatWithFallback(p, m, l); }
        @Override public void sendActionBar(Player p, Component m) { delegate.sendActionBar(p, m); }
        @Override public void sendActionBarWithFallback(Player p, Component m, Locale l) { delegate.sendActionBarWithFallback(p, m, l); }
        @Override public void sendTitle(Player p, Component t, Component s) { delegate.sendTitle(p, t, s); }
        @Override public void sendTitleWithFallback(Player p, Component t, Component s, Locale l) { delegate.sendTitleWithFallback(p, t, s, l); }
        @Override public void broadcastWithFallback(Component m, Locale l) { delegate.broadcastWithFallback(m, l); }
    }

    /** Seam for language lookup. */
    public interface LangProvider {
        Optional<String> get(Locale locale, String key);
    }

    static final class LangManagerProvider implements LangProvider {
        private final LangManager delegate;
        LangManagerProvider(LangManager delegate) { this.delegate = delegate; }
        @Override public Optional<String> get(Locale locale, String key) { return delegate.get(locale, key); }
    }

    /** Parser seam — production delegates to AceLib {@link MessageService#parseMiniMessage}. */
    public interface MessageParser {
        Component parse(String template, Map<String, Object> vars);
    }

    static final class AceLibParser implements MessageParser {
        private final MessageService service;
        AceLibParser(MessageService service) { this.service = Objects.requireNonNull(service); }
        @Override public Component parse(String template, Map<String, Object> vars) {
            return service.parseMiniMessage(template, vars);
        }
    }

    private final PipelineSender sender;
    private final MessageParser parser;
    private final LangProvider lang;
    private final BedrockService bedrock;
    private final Locale defaultLocale;

    /** Cache key for template reuse. */
    private record CacheKey(String messageKey, Locale locale) {}

    private final ConcurrentMap<CacheKey, String> templateCache = new ConcurrentHashMap<>();

    /** Allowed placeholders — must stay in sync with lang resources. */
    static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
        "value", "payload", "land_name", "chunk_count", "price", "conflict_count",
        "min_y", "revision", "added_count", "new_name", "old_name", "refund",
        "action", "owner", "reason", "limit_type", "current", "max", "remaining",
        "subcommand", "permission"
    );

    private static final MiniMessage STRICT_MINIMESSAGE = MiniMessage.builder().strict(true).build();
    private static final Pattern LEFTOVER_TAG = Pattern.compile("<[^>]+>");

    public ChunkLandMessagePipeline(MessageService service, LangManager lang, BedrockService bedrock, Locale defaultLocale) {
        this(new MessageServiceSender(Objects.requireNonNull(service, "service")),
             new AceLibParser(service),
             new LangManagerProvider(Objects.requireNonNull(lang, "lang")),
             bedrock, defaultLocale);
    }

    public ChunkLandMessagePipeline(MessageService service, LangProvider lang, BedrockService bedrock, Locale defaultLocale) {
        this(new MessageServiceSender(Objects.requireNonNull(service, "service")),
             new AceLibParser(service),
             lang, bedrock, defaultLocale);
    }

    /** Seam for injecting custom sender. */
    public ChunkLandMessagePipeline(PipelineSender sender, MessageService service, LangManager lang, BedrockService bedrock, Locale defaultLocale) {
        this(sender, new AceLibParser(Objects.requireNonNull(service, "service")),
             new LangManagerProvider(Objects.requireNonNull(lang, "lang")), bedrock, defaultLocale);
    }

    public ChunkLandMessagePipeline(PipelineSender sender, MessageService service, LangProvider lang, BedrockService bedrock, Locale defaultLocale) {
        this(sender, new AceLibParser(Objects.requireNonNull(service, "service")), lang, bedrock, defaultLocale);
    }

    /** Constructor with explicit parser and sender — package-private seam for verification. */
    ChunkLandMessagePipeline(PipelineSender sender, MessageParser parser, LangProvider lang, BedrockService bedrock, Locale defaultLocale) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.bedrock = bedrock;
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        if (defaultLocale.getLanguage().isEmpty()) {
            throw new IllegalArgumentException("defaultLocale must have language: " + defaultLocale);
        }
    }

    /**
     * Production factory: copies bundled {@code lang/*.yml} without overwriting
     * player edits, loads the LangManager with the configured default locale,
     * and builds the pipeline. Returns empty on fail-closed.
     */
    public static Optional<ChunkLandMessagePipeline> tryBuild(JavaPlugin plugin, com.smile.acelib.AceLibApi api, Locale defaultLocale) {
        if (plugin == null || api == null || !api.isReady() || api.getBedrockService() == null) {
            return Optional.empty();
        }
        if (defaultLocale == null || defaultLocale.getLanguage().isEmpty()) {
            return Optional.empty();
        }
        try {
            for (String path : LANG_RESOURCE_PATHS) {
                try {
                    plugin.saveResource(path, LANG_RESOURCE_OVERWRITE);
                } catch (IllegalArgumentException ignored) {
                    // resource not on classpath — LangManager may still resolve from jar
                }
            }
            LangManager lang = new LangManager(plugin, defaultLocale);
            lang.load();
            BedrockService bedrock = api.getBedrockService();
            MessageService service = new MessageService(plugin, lang, bedrock);
            return Optional.of(new ChunkLandMessagePipeline(service, lang, bedrock, defaultLocale));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------
    // Locale chain: override -> player.locale() -> Bedrock languageCode -> default
    // -----------------------------------------------------------------

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

    Locale resolveLocale(Player player, Locale override) {
        Locale playerLocale = null;
        if (player != null) {
            try {
                playerLocale = player.locale();
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        String bedrockTag = null;
        if (player != null && bedrock != null) {
            try {
                bedrockTag = bedrock.getPlayerInfo(player.getUniqueId())
                    .map(BedrockPlayerInfo::languageCode)
                    .orElse(null);
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return resolveLocaleChain(override, playerLocale, bedrockTag, defaultLocale);
    }

    static boolean useBedrockFallback(BedrockService bedrock, UUID uuid) {
        if (bedrock == null || uuid == null) return false;
        try {
            return bedrock.isBedrockPlayer(uuid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Template resolution + caching with strict validation
    // -----------------------------------------------------------------

    /**
     * Resolve raw MiniMessage template for key+effective locale (fail-closed).
     * Cached per (key, locale) on the raw string after strict validation.
     */
    String resolveTemplate(String messageKey, Locale effectiveLocale) {
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(effectiveLocale, "effectiveLocale");
        if (messageKey.isBlank()) {
            throw new MessageException(messageKey, "messageKey must not be blank");
        }
        CacheKey cacheKey = new CacheKey(messageKey, effectiveLocale);
        String cached = templateCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Optional<String> template = lang.get(effectiveLocale, messageKey);
        if (template.isEmpty()) {
            throw new MessageException(messageKey, "unknown message key: " + messageKey + " locale=" + effectiveLocale);
        }
        String value = template.get();
        if (value == null) {
            throw new MessageException(messageKey, "template is null for key: " + messageKey);
        }
        validateTemplateStrict(messageKey, value);
        templateCache.put(cacheKey, value);
        return value;
    }

    int cachedTemplateCount() {
        return templateCache.size();
    }

    /**
     * Strict validation: unclosed/mismatched tags must fail, unknown tags must fail,
     * allowed placeholders and standard tags must pass.
     * Uses strict MiniMessage plus leftover-tag detection on visible text and
     * recursive hover/click payloads.
     */
    static void validateTemplateStrict(String messageKey, String template) {
        TagResolver dummyResolver = buildDummyPlaceholderResolver();
        Component parsed;
        try {
            parsed = STRICT_MINIMESSAGE.deserialize(template, dummyResolver);
        } catch (RuntimeException e) {
            throw new MessageException(messageKey, "malformed MiniMessage template for key: " + messageKey, e);
        }
        checkComponentTreeForLeftover(messageKey, parsed);
    }

    private static void checkComponentTreeForLeftover(String messageKey, Component component) {
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        Matcher m = LEFTOVER_TAG.matcher(plain);
        if (m.find()) {
            String found = m.group();
            String name = extractTagName(found);
            if (!ALLOWED_PLACEHOLDERS.contains(name)) {
                throw new MessageException(messageKey, "unknown MiniMessage tag in template for key: " + messageKey + ": " + found);
            }
        }
        ClickEvent click = component.clickEvent();
        if (click != null) {
            String value = click.value();
            if (value != null) {
                Matcher cm = LEFTOVER_TAG.matcher(value);
                while (cm.find()) {
                    String found = cm.group();
                    String name = extractTagName(found);
                    if (!ALLOWED_PLACEHOLDERS.contains(name)) {
                        throw new MessageException(messageKey, "unknown MiniMessage tag in click payload for key: " + messageKey + ": " + found);
                    }
                }
            }
        }
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null) {
            Object hv = hover.value();
            if (hv instanceof Component hoverComp) {
                String hoverPlain = PlainTextComponentSerializer.plainText().serialize(hoverComp);
                Matcher hm = LEFTOVER_TAG.matcher(hoverPlain);
                while (hm.find()) {
                    String found = hm.group();
                    String name = extractTagName(found);
                    if (!ALLOWED_PLACEHOLDERS.contains(name)) {
                        throw new MessageException(messageKey, "unknown MiniMessage tag in hover for key: " + messageKey + ": " + found);
                    }
                }
                checkComponentTreeForLeftover(messageKey, hoverComp);
            } else if (hv instanceof String hoverStr) {
                Matcher hm = LEFTOVER_TAG.matcher(hoverStr);
                while (hm.find()) {
                    String found = hm.group();
                    String name = extractTagName(found);
                    if (!ALLOWED_PLACEHOLDERS.contains(name)) {
                        throw new MessageException(messageKey, "unknown MiniMessage tag in hover for key: " + messageKey + ": " + found);
                    }
                }
            }
        }
        for (Component child : component.children()) {
            checkComponentTreeForLeftover(messageKey, child);
        }
    }

    private static String extractTagName(String tag) {
        String inner = tag.substring(1, tag.length() - 1).trim();
        if (inner.startsWith("/")) inner = inner.substring(1).trim();
        int colon = inner.indexOf(':');
        int space = inner.indexOf(' ');
        int slash = inner.indexOf('/');
        int end = inner.length();
        if (colon != -1) end = Math.min(end, colon);
        if (space != -1) end = Math.min(end, space);
        if (slash != -1) end = Math.min(end, slash);
        return inner.substring(0, end).trim().toLowerCase(Locale.ROOT);
    }

    private static TagResolver buildDummyPlaceholderResolver() {
        TagResolver[] resolvers = new TagResolver[ALLOWED_PLACEHOLDERS.size()];
        int i = 0;
        for (String key : ALLOWED_PLACEHOLDERS) {
            resolvers[i++] = Placeholder.unparsed(key, "X");
        }
        return TagResolver.resolver(resolvers);
    }

    // -----------------------------------------------------------------
    // Rendering: template + vars -> Component (per-call, not cached)
    //
    // Nested diagnostic reason: when the {@code reason} var holds a known
    // language key (for example the claim/pricing/economy diagnostics), it
    // is resolved to that entry's plain sentence before the outer template
    // renders. Unknown values stay literal so arbitrary input is never
    // treated as a MiniMessage template.
    // -----------------------------------------------------------------

    /**
     * Resolve a nested {@code reason} var to display text when it names a
     * known language entry. Unknown keys return empty so callers keep the
     * original literal (fail-safe, never parsed as a template).
     */
    Optional<String> resolveNestedReasonText(Locale effectiveLocale, String outerKey, Object reasonValue) {
        if (effectiveLocale == null || reasonValue == null) {
            return Optional.empty();
        }
        if (!(reasonValue instanceof String reasonKey) || reasonKey.isBlank()) {
            return Optional.empty();
        }
        if (reasonKey.equals(outerKey)) {
            return Optional.empty();
        }
        Optional<String> nested = lang.get(effectiveLocale, reasonKey);
        Locale nestedLocale = effectiveLocale;
        if (nested.isEmpty() && !effectiveLocale.equals(defaultLocale)) {
            nested = lang.get(defaultLocale, reasonKey);
            nestedLocale = defaultLocale;
        }
        if (nested.isEmpty()) {
            return Optional.empty();
        }
        String nestedTemplate;
        try {
            nestedTemplate = resolveTemplate(reasonKey, nestedLocale);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (nestedTemplate.contains("<reason>")) {
            return Optional.empty();
        }
        Component nestedComponent;
        try {
            nestedComponent = parser.parse(nestedTemplate, Map.of());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (nestedComponent == null) {
            return Optional.empty();
        }
        String plainReason = PlainTextComponentSerializer.plainText().serialize(nestedComponent);
        if (plainReason == null || plainReason.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(plainReason);
    }

    private Map<String, Object> withNestedReason(String messageKey, Map<String, Object> vars, Locale effective) {
        if (vars == null || vars.isEmpty() || !vars.containsKey("reason")) {
            return vars;
        }
        Optional<String> resolved = resolveNestedReasonText(effective, messageKey, vars.get("reason"));
        if (resolved.isEmpty()) {
            return vars;
        }
        java.util.HashMap<String, Object> copy = new java.util.HashMap<>(vars);
        copy.put("reason", resolved.get());
        return Map.copyOf(copy);
    }

    public Component render(String messageKey, Map<String, Object> vars, Locale localeOverride, Player contextPlayer) {
        Locale effective = resolveLocale(contextPlayer, localeOverride);
        String template = resolveTemplate(messageKey, effective);
        vars = withNestedReason(messageKey, vars, effective);
        try {
            Component parsed = parser.parse(template, vars);
            if (parsed == null) {
                throw new MessageException(messageKey, "parser returned null for key: " + messageKey);
            }
            if (parsed.equals(Component.empty())) {
                if (!template.isBlank()) {
                    throw new MessageException(messageKey, "parser produced empty component for key: " + messageKey);
                }
            }
            return parsed;
        } catch (MessageException me) {
            throw me;
        } catch (RuntimeException e) {
            throw new MessageException(messageKey, "failed to render template for key: " + messageKey, e);
        }
    }

    /**
     * Render without player context (broadcast / console). Uses override -> default chain only.
     */
    public Component renderForBroadcast(String messageKey, Map<String, Object> vars, Locale localeOverride) {
        Locale effective = localeOverride != null ? localeOverride : defaultLocale;
        String template = resolveTemplate(messageKey, effective);
        vars = withNestedReason(messageKey, vars, effective);
        try {
            Component parsed = parser.parse(template, vars);
            if (parsed == null) {
                throw new MessageException(messageKey, "parser returned null for broadcast key: " + messageKey);
            }
            if (parsed.equals(Component.empty()) && !template.isBlank()) {
                throw new MessageException(messageKey, "parser produced empty component for broadcast key: " + messageKey);
            }
            return parsed;
        } catch (MessageException me) {
            throw me;
        } catch (RuntimeException e) {
            throw new MessageException(messageKey, "failed to render broadcast template for key: " + messageKey, e);
        }
    }

    private static TagResolver buildResolver(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return TagResolver.empty();
        }
        TagResolver[] resolvers = new TagResolver[vars.size()];
        int i = 0;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            resolvers[i++] = Placeholder.unparsed(e.getKey(), String.valueOf(e.getValue()));
        }
        return TagResolver.resolver(resolvers);
    }

    // -----------------------------------------------------------------
    // Dedicated ALLOW/silent guard: must be checked BEFORE any render/send
    // -----------------------------------------------------------------

    /**
     * Dispatch guard. When allow or silent returns true, caller must return immediately
     * without touching the message layer (zero cost). This method itself does not
     * render or call the service.
     */
    public static boolean shouldBlockMessage(boolean allow, boolean silent) {
        return allow || silent;
    }

    /**
     * Convenience: if allow or silent, return false without rendering/sending;
     * otherwise render and send via the correct AceLib entry point.
     *
     * @return true if a message was sent, false if suppressed by guard
     */
    public boolean dispatchIfDenied(Player player, String messageKey, Map<String, Object> vars, Locale override, boolean allow, boolean silent) {
        if (shouldBlockMessage(allow, silent)) {
            return false;
        }
        sendChat(player, messageKey, vars, override);
        return true;
    }

    // -----------------------------------------------------------------
    // Send paths: Java Component vs Bedrock *WithFallback
    // -----------------------------------------------------------------

    public void sendChat(Player player, String messageKey, Map<String, Object> vars, Locale localeOverride) {
        Objects.requireNonNull(player, "player");
        Locale effective = resolveLocale(player, localeOverride);
        Component component = render(messageKey, vars, localeOverride, player);
        if (useBedrockFallback(bedrock, player.getUniqueId())) {
            sender.sendChatWithFallback(player, component, effective);
        } else {
            sender.sendChat(player, component);
        }
    }

    public void sendActionBar(Player player, String messageKey, Map<String, Object> vars, Locale localeOverride) {
        Objects.requireNonNull(player, "player");
        Locale effective = resolveLocale(player, localeOverride);
        Component component = render(messageKey, vars, localeOverride, player);
        if (useBedrockFallback(bedrock, player.getUniqueId())) {
            sender.sendActionBarWithFallback(player, component, effective);
        } else {
            sender.sendActionBar(player, component);
        }
    }

    public void sendTitle(Player player, String titleKey, Map<String, Object> titleVars,
                          String subtitleKey, Map<String, Object> subtitleVars, Locale localeOverride) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(titleKey, "titleKey");
        Locale effective = resolveLocale(player, localeOverride);
        Component title = render(titleKey, titleVars, localeOverride, player);
        Component subtitle = subtitleKey == null ? Component.empty() : render(subtitleKey, subtitleVars, localeOverride, player);
        if (useBedrockFallback(bedrock, player.getUniqueId())) {
            sender.sendTitleWithFallback(player, title, subtitle, effective);
        } else {
            sender.sendTitle(player, title, subtitle);
        }
    }

    public void sendTitle(Player player, String messageKey, Map<String, Object> vars, Locale localeOverride) {
        sendTitle(player, messageKey, vars, null, Map.of(), localeOverride);
    }

    public void broadcast(String messageKey, Map<String, Object> vars, Locale localeOverride) {
        Locale effective = localeOverride != null ? localeOverride : defaultLocale;
        Component component = renderForBroadcast(messageKey, vars, localeOverride);
        sender.broadcastWithFallback(component, effective);
    }

    // -----------------------------------------------------------------
    // Accessors for checks
    // -----------------------------------------------------------------

    MessageParser parser() { return parser; }
    LangProvider lang() { return lang; }
    BedrockService bedrock() { return bedrock; }
    Locale defaultLocale() { return defaultLocale; }
}
