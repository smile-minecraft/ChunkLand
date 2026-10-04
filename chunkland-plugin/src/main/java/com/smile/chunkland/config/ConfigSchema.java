package com.smile.chunkland.config;

import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.selection.SelectionVisualizationBudget;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * Parser + validator for the YAML structure produced by {@code config.yml}.
 *
 * <p>This is intentionally a small, hand-written parser rather than a Jackson /
 * snakeyaml-binding generator: the schema is tiny, the failure messages need
 * to point at the offending key path, and the result must be a fully typed
 * {@link ChunkLandConfig} with no {@code null} or {@code Object} escape hatches
 * left in. The YAML library is only used to turn text into a generic
 * {@code Map}/{@code List}/{@code String} tree that this class then validates.</p>
 *
 * <p>Supported top-level structure:</p>
 * <pre>
 * worlds:
 *   &lt;world-name&gt;:
 *     claim-enabled: true|false
 *     vertical-mode: PER_CHUNK_DEPTH|FULL_HEIGHT
 * selection:
 *   session-timeout-seconds: 600
 * subject-defaults:            # optional; absent means every subject default stays INHERIT
 *   global:
 *     BLOCK_BREAK: ALLOW|DENY|INHERIT
 *   worlds:
 *     &lt;world-name&gt;:
 *       BLOCK_BREAK: ALLOW|DENY|INHERIT
 * rule-defaults:               # optional; absent means every rule default stays INHERIT
 *   global:
 *     PVP: ALLOW|DENY|INHERIT
 *   worlds:
 *     &lt;world-name&gt;:
 *       PVP: ALLOW|DENY|INHERIT
 * </pre>
 *
 * <p>Action and rule keys are matched case-insensitively ({@code pvp} and
 * {@code PVP} are the same key); repeating a key in different cases is
 * rejected instead of silently overwritten. World names stay case-sensitive,
 * matching the existing {@code worlds} map. An explicit {@code INHERIT} value
 * is identical to omitting the entry: both fall through to the next resolver
 * layer.</p>
 *
 * <p>Unknown top-level keys are rejected; unknown sub-keys under a world are
 * rejected. The validator never silently drops data — that is how a future
 * refactor that renames a key would otherwise become a silent runtime bug.</p>
 */
public final class ConfigSchema {

    private ConfigSchema() {
        // utility class
    }

    /**
     * Parse {@code rawYamlRoot} (as produced by snakeyaml) into a
     * {@link ChunkLandConfig} with epoch counters set to zero. Validation
     * failures throw {@link ConfigValidationException} with a message that
     * includes the dotted key path that failed.
     *
     * @param rawYamlRoot snakeyaml output (typically a {@code Map<String,Object>},
     *                    but a {@code null} root is accepted and treated as an
     *                    empty document).
     */
    public static ChunkLandConfig parseAndValidate(Object rawYamlRoot) {
        if (rawYamlRoot == null) {
            return ChunkLandConfig.defaults();
        }
        if (!(rawYamlRoot instanceof Map<?, ?> rootMap)) {
            throw new ConfigValidationException(
                    "top-level YAML root must be a mapping (worlds: { ... }), got "
                            + rawYamlRoot.getClass().getSimpleName());
        }
        Map<String, Object> root = castStringKeyMap(rootMap, "");
        // Reject unknown top-level keys explicitly so future renames become
        // loud failures instead of silent runtime bugs.
        for (String key : root.keySet()) {
            if (!"worlds".equals(key) && !"limits".equals(key) && !"messages".equals(key)
                    && !"selection".equals(key) && !"economy".equals(key) && !"audit".equals(key)
                    && !"feedback".equals(key)
                    && !"subject-defaults".equals(key) && !"rule-defaults".equals(key)) {
                throw new ConfigValidationException(
                        "unknown top-level key '" + key
                                + "' (only 'worlds', 'limits', 'messages', 'selection', 'economy', 'audit', "
                                + "'feedback', 'subject-defaults' and 'rule-defaults' are supported in the "
                                + "current schema)");
            }
        }
        Map<String, WorldSettings> worlds = parseWorlds(root.get("worlds"), "worlds");
        LimitSettings limits;
        if (!root.containsKey("limits")) {
            limits = LimitSettings.defaults();
        } else {
            Object rawLimits = root.get("limits");
            if (rawLimits == null) {
                throw new ConfigValidationException("limits must not be null");
            }
            limits = parseLimits(rawLimits, "limits");
        }
        MessageSettings messages;
        if (!root.containsKey("messages")) {
            messages = MessageSettings.defaults();
        } else {
            Object rawMessages = root.get("messages");
            if (rawMessages == null) {
                throw new ConfigValidationException("messages must not be null");
            }
            messages = parseMessages(rawMessages, "messages");
        }
        SelectionSettings selection;
        if (!root.containsKey("selection")) {
            selection = SelectionSettings.defaults();
        } else {
            Object rawSelection = root.get("selection");
            if (rawSelection == null) {
                throw new ConfigValidationException("selection must not be null");
            }
            selection = parseSelection(rawSelection, "selection");
        }
        SubjectDefaultsConfig subjectDefaults;
        if (!root.containsKey("subject-defaults")) {
            subjectDefaults = SubjectDefaultsConfig.empty();
        } else {
            Object rawSubjectDefaults = root.get("subject-defaults");
            if (rawSubjectDefaults == null) {
                throw new ConfigValidationException("subject-defaults must not be null");
            }
            subjectDefaults = parseSubjectDefaults(rawSubjectDefaults, "subject-defaults");
        }
        RuleDefaultsConfig ruleDefaults;
        if (!root.containsKey("rule-defaults")) {
            ruleDefaults = RuleDefaultsConfig.empty();
        } else {
            Object rawRuleDefaults = root.get("rule-defaults");
            if (rawRuleDefaults == null) {
                throw new ConfigValidationException("rule-defaults must not be null");
            }
            ruleDefaults = parseRuleDefaults(rawRuleDefaults, "rule-defaults");
        }
        int decisionCacheMaxEntries = parseDecisionCacheMaxEntries(root.get("limits"));
        EconomySettings economy = parseEconomyOptional(root.get("economy"), "economy",
                root.containsKey("economy"));
        AuditSettings audit = parseAuditOptional(root.get("audit"), "audit", root.containsKey("audit"));
        FeedbackSettings feedback = parseFeedbackOptional(root.get("feedback"), "feedback",
                root.containsKey("feedback"));
        return new ChunkLandConfig(worlds, limits, messages, selection,
                subjectDefaults, ruleDefaults, economy, audit, feedback, 0L,
                deriveWorldEpochs(worlds), decisionCacheMaxEntries);
    }

    /**
     * Optional {@code limits.max-decision-cache-entries}: the protection
     * decision-cache budget. Absent means the default (4096); explicit zero
     * disables the cache; negative or non-integer values fail closed with a
     * validation error so a bad edit can never silently unbind the cache.
     */
    static int parseDecisionCacheMaxEntries(Object rawLimits) {
        if (rawLimits == null) {
            return 4096;
        }
        if (!(rawLimits instanceof Map<?, ?> rawMap)) {
            // parseLimits reports the shape error; keep this helper total.
            return 4096;
        }
        Map<String, Object> map = castStringKeyMap(rawMap, "limits");
        if (!map.containsKey("max-decision-cache-entries")) {
            return 4096;
        }
        Object raw = map.get("max-decision-cache-entries");
        if (raw == null) {
            throw new ConfigValidationException("limits.max-decision-cache-entries must not be null");
        }
        return parseIntLimit(raw, "limits.max-decision-cache-entries");
    }

    /** Build the worldPolicyEpochs map (every known world starts at 0). */
    static Map<String, Long> deriveWorldEpochs(Map<String, WorldSettings> worlds) {
        Map<String, Long> out = new LinkedHashMap<>(worlds.size());
        for (String name : worlds.keySet()) {
            out.put(name, 0L);
        }
        return out;
    }

    /**
     * Optional {@code economy} section: the purchase switch, the claim
     * currency and the owner-total price-per-chunk tiers.
     *
     * <p>{@code enabled} is optional and defaults to {@code false}: claims
     * are free until an operator turns purchases on. An absent section
     * returns {@code null}, which downstream reads the same way (purchases
     * off). A present section must be complete and exact whatever the
     * switch says: unknown keys, a non-boolean {@code enabled}, missing
     * currency/pricing, blank codes, out-of-range scales, non-integer or
     * non-positive bounds, overlapping tiers, a finite top tier (gap at
     * infinity), negative, non-finite, or overflowing prices all fail here,
     * so turning purchases on can never meet a table nobody validated.
     *
     * <p>Shape:
     * <pre>
     * economy:
     *   enabled: false
     *   currency:
     *     code: EMC
     *     scale: 2
     *   pricing:
     *     tiers:
     *       - until: 20
     *         price-per-chunk: 1.00
     *       - until: unbounded
     *         price-per-chunk: 2.00
     * </pre>
     *
     * <p>{@code price-per-chunk} is major units (what the Vault provider
     * moves as {@code double}); it is converted to exact minor units with
     * the currency scale here, so sub-minor fractions and overflow fail at
     * load instead of rounding silently.
     */
    static EconomySettings parseEconomyOptional(Object raw, String path, boolean present) {
        if (!present) {
            return null;
        }
        if (raw == null) {
            throw new ConfigValidationException(
                    path + " must not be null; remove the key or provide 'currency' and 'pricing'");
        }
        Map<String, Object> map = requireMapping(raw, path, "'currency' and 'pricing'");
        rejectUnknownKeys(map, path, Set.of("enabled", "currency", "pricing"));
        boolean enabled = map.containsKey("enabled")
                && parseClaimEnabled(map.get("enabled"), path + ".enabled");
        if (!map.containsKey("currency")) {
            throw new ConfigValidationException(path + ".currency is required");
        }
        if (!map.containsKey("pricing")) {
            throw new ConfigValidationException(path + ".pricing is required");
        }
        Currency currency = parseEconomyCurrency(map.get("currency"), path + ".currency");
        PricingTable pricing = parseEconomyPricing(map.get("pricing"), path + ".pricing", currency);
        try {
            return new EconomySettings(enabled, currency, pricing);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(path + " is inconsistent: " + e.getMessage());
        }
    }

    /**
     * Optional {@code audit} section: only {@code retention-days} is
     * supported. Absent means the default history window; an explicit
     * {@code 0} means retain forever. Negative or non-integer values fail
     * closed so a bad edit can never silently shrink or wipe history.
     */
    static AuditSettings parseAuditOptional(Object raw, String path, boolean present) {
        if (!present) {
            return AuditSettings.defaults();
        }
        if (raw == null) {
            throw new ConfigValidationException(
                    path + " must not be null; remove the key or provide 'retention-days'");
        }
        Map<String, Object> map = requireMapping(raw, path, "'retention-days'");
        rejectUnknownKeys(map, path, Set.of("retention-days"));
        int retentionDays = AuditSettings.DEFAULT_RETENTION_DAYS;
        if (map.containsKey("retention-days")) {
            Object rawRetention = map.get("retention-days");
            if (rawRetention == null) {
                throw new ConfigValidationException(path + ".retention-days must not be null");
            }
            retentionDays = parseIntLimit(rawRetention, path + ".retention-days");
        }
        try {
            return new AuditSettings(retentionDays);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(path + " is inconsistent: " + e.getMessage());
        }
    }

    /**
     * Optional {@code feedback} section: deny particles and push-out tuning.
     * Absent means the defaults; every key is optional and independently
     * defaulted. Unknown keys, non-integers, non-booleans and out-of-range
     * values fail closed so a typo never silently disables the feedback.
     */
    static FeedbackSettings parseFeedbackOptional(Object raw, String path, boolean present) {
        FeedbackSettings defaults = FeedbackSettings.defaults();
        if (!present) {
            return defaults;
        }
        if (raw == null) {
            throw new ConfigValidationException(
                    path + " must not be null; remove the key or provide its settings");
        }
        Map<String, Object> map = requireMapping(raw, path, "feedback settings");
        rejectUnknownKeys(map, path, Set.of(
                "entry-wall-enabled", "entry-wall-radius-blocks",
                "entry-wall-particle-size-percent", "entry-wall-points-per-block",
                "entry-wall-cooldown-millis", "action-mark-enabled",
                "action-mark-particle-size-percent", "action-mark-cooldown-millis",
                "push-out-distance-blocks", "push-out-cooldown-millis"));
        boolean wallEnabled = map.containsKey("entry-wall-enabled")
                ? parseClaimEnabled(map.get("entry-wall-enabled"), path + ".entry-wall-enabled")
                : defaults.entryWallEnabled();
        boolean markEnabled = map.containsKey("action-mark-enabled")
                ? parseClaimEnabled(map.get("action-mark-enabled"), path + ".action-mark-enabled")
                : defaults.actionMarkEnabled();
        int wallRadius = parseVisualizationField(map, "entry-wall-radius-blocks", path,
                defaults.entryWallRadiusBlocks(), FeedbackSettings.MIN_WALL_RADIUS_BLOCKS,
                FeedbackSettings.MAX_WALL_RADIUS_BLOCKS);
        int wallSize = parseVisualizationField(map, "entry-wall-particle-size-percent", path,
                defaults.entryWallParticleSizePercent(), FeedbackSettings.MIN_PARTICLE_SIZE_PERCENT,
                FeedbackSettings.MAX_PARTICLE_SIZE_PERCENT);
        int wallPoints = parseVisualizationField(map, "entry-wall-points-per-block", path,
                defaults.entryWallPointsPerBlock(), FeedbackSettings.MIN_WALL_POINTS_PER_BLOCK,
                FeedbackSettings.MAX_WALL_POINTS_PER_BLOCK);
        int wallCooldown = parseVisualizationField(map, "entry-wall-cooldown-millis", path,
                defaults.entryWallCooldownMillis(), FeedbackSettings.MIN_COOLDOWN_MILLIS,
                FeedbackSettings.MAX_COOLDOWN_MILLIS);
        int markSize = parseVisualizationField(map, "action-mark-particle-size-percent", path,
                defaults.actionMarkParticleSizePercent(), FeedbackSettings.MIN_PARTICLE_SIZE_PERCENT,
                FeedbackSettings.MAX_PARTICLE_SIZE_PERCENT);
        int markCooldown = parseVisualizationField(map, "action-mark-cooldown-millis", path,
                defaults.actionMarkCooldownMillis(), FeedbackSettings.MIN_COOLDOWN_MILLIS,
                FeedbackSettings.MAX_COOLDOWN_MILLIS);
        int pushDistance = parseVisualizationField(map, "push-out-distance-blocks", path,
                defaults.pushOutDistanceBlocks(), FeedbackSettings.MIN_PUSH_OUT_DISTANCE_BLOCKS,
                FeedbackSettings.MAX_PUSH_OUT_DISTANCE_BLOCKS);
        int pushCooldown = parseVisualizationField(map, "push-out-cooldown-millis", path,
                defaults.pushOutCooldownMillis(), FeedbackSettings.MIN_COOLDOWN_MILLIS,
                FeedbackSettings.MAX_COOLDOWN_MILLIS);
        try {
            return new FeedbackSettings(wallEnabled, wallRadius, wallSize, wallPoints,
                    wallCooldown, markEnabled, markSize, markCooldown, pushDistance, pushCooldown);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(path + " is inconsistent: " + e.getMessage());
        }
    }

    private static Currency parseEconomyCurrency(Object raw, String path) {
        Map<String, Object> map = requireMapping(raw, path, "'code' and 'scale'");
        rejectUnknownKeys(map, path, Set.of("code", "scale"));
        if (!map.containsKey("code")) {
            throw new ConfigValidationException(path + ".code is required");
        }
        Object rawCode = map.get("code");
        if (!(rawCode instanceof String code) || code.isBlank()) {
            throw new ConfigValidationException(
                    path + ".code must be a non-blank currency code string");
        }
        if (!map.containsKey("scale")) {
            throw new ConfigValidationException(path + ".scale is required");
        }
        Object rawScale = map.get("scale");
        if (rawScale == null) {
            throw new ConfigValidationException(path + ".scale must not be null");
        }
        int scale = parseIntLimit(rawScale, path + ".scale");
        try {
            return Currency.of(code.trim(), scale);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(path + " is invalid: " + e.getMessage());
        }
    }

    private static PricingTable parseEconomyPricing(Object raw, String path, Currency currency) {
        Map<String, Object> map = requireMapping(raw, path, "'tiers'");
        rejectUnknownKeys(map, path, Set.of("tiers"));
        if (!map.containsKey("tiers")) {
            throw new ConfigValidationException(path + ".tiers is required");
        }
        Object rawTiers = map.get("tiers");
        if (!(rawTiers instanceof List<?> list)) {
            throw new ConfigValidationException(
                    path + ".tiers must be a list of {until, price-per-chunk}, got "
                            + (rawTiers == null ? "null" : rawTiers.getClass().getSimpleName()));
        }
        if (list.isEmpty()) {
            throw new ConfigValidationException(path + ".tiers must not be empty");
        }
        List<PricingTier> tiers = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            tiers.add(parseEconomyTier(list.get(i), path + ".tiers[" + i + "]", currency));
        }
        try {
            return PricingTable.of(tiers);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ConfigValidationException(
                    path + ".tiers are not a contiguous partition: " + e.getMessage());
        }
    }

    private static PricingTier parseEconomyTier(Object raw, String path, Currency currency) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping with 'until' and 'price-per-chunk', got "
                            + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        rejectUnknownKeys(map, path, Set.of("until", "price-per-chunk"));
        if (!map.containsKey("until")) {
            throw new ConfigValidationException(path + ".until is required");
        }
        if (!map.containsKey("price-per-chunk")) {
            throw new ConfigValidationException(path + ".price-per-chunk is required");
        }
        long until = parseTierUntil(map.get("until"), path + ".until");
        Money price = parseTierPrice(map.get("price-per-chunk"), path + ".price-per-chunk", currency);
        try {
            return PricingTier.of(until, price);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(path + " is invalid: " + e.getMessage());
        }
    }

    private static long parseTierUntil(Object raw, String path) {
        if (raw instanceof String text) {
            if (text.trim().equalsIgnoreCase("unbounded")) {
                return PricingTier.UNBOUNDED;
            }
            throw new ConfigValidationException(
                    path + " must be a positive integer chunk upper bound or 'unbounded', got '"
                            + text + "'");
        }
        if (raw instanceof Double || raw instanceof Float) {
            throw new ConfigValidationException(
                    path + " must be an integer chunk upper bound (no floating point), got " + raw);
        }
        if (raw instanceof BigInteger bi) {
            if (bi.signum() <= 0) {
                throw new ConfigValidationException(path + " must be >= 1, got " + bi);
            }
            try {
                return bi.longValueExact();
            } catch (ArithmeticException e) {
                throw new ConfigValidationException(path + " out of range (> Long.MAX_VALUE): " + bi);
            }
        }
        if (raw instanceof BigDecimal bd) {
            long value;
            try {
                value = bd.longValueExact();
            } catch (ArithmeticException e) {
                throw new ConfigValidationException(path + " must be an integer, got " + raw);
            }
            if (value < 1) {
                throw new ConfigValidationException(path + " must be >= 1, got " + raw);
            }
            return value;
        }
        if (raw instanceof Number number) {
            long value = number.longValue();
            if (value < 1) {
                throw new ConfigValidationException(path + " must be >= 1, got " + raw);
            }
            return value;
        }
        throw new ConfigValidationException(
                path + " must be a positive integer chunk upper bound or 'unbounded', got "
                        + (raw == null ? "null"
                                : raw.getClass().getSimpleName() + " value '" + raw + "'"));
    }

    private static Money parseTierPrice(Object raw, String path, Currency currency) {
        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null");
        }
        BigDecimal major;
        if (raw instanceof BigDecimal bd) {
            major = bd;
        } else if (raw instanceof BigInteger bi) {
            major = new BigDecimal(bi);
        } else if (raw instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new ConfigValidationException(path + " must be a finite amount, got " + raw);
            }
            major = BigDecimal.valueOf(d);
        } else if (raw instanceof Float f) {
            if (!Float.isFinite(f)) {
                throw new ConfigValidationException(path + " must be a finite amount, got " + raw);
            }
            major = BigDecimal.valueOf(f.doubleValue());
        } else if (raw instanceof Number number) {
            major = BigDecimal.valueOf(number.longValue());
        } else if (raw instanceof String text) {
            try {
                major = new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(
                        path + " must be a decimal major-unit amount, got '" + text + "'");
            }
        } else {
            throw new ConfigValidationException(
                    path + " must be a decimal major-unit amount, got "
                            + raw.getClass().getSimpleName() + " value '" + raw + "'");
        }
        if (major.signum() < 0) {
            throw new ConfigValidationException(path + " must not be negative, got " + raw);
        }
        long minor;
        try {
            minor = major.movePointRight(currency.scale()).longValueExact();
        } catch (ArithmeticException e) {
            throw new ConfigValidationException(
                    path + " overflows minor units or carries a sub-minor fraction "
                            + "at scale " + currency.scale() + ": " + raw);
        }
        return new Money(minor, currency);
    }

    private static Map<String, WorldSettings> parseWorlds(Object rawWorlds, String path) {
        if (rawWorlds == null) {
            return Map.of();
        }
        if (!(rawWorlds instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping of world name -> settings, got "
                            + rawWorlds.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        Map<String, WorldSettings> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String worldName = Objects.requireNonNull(entry.getKey(), "world name");
            if (worldName.isBlank()) {
                throw new ConfigValidationException("world name must not be blank");
            }
            String worldPath = path + "." + worldName;
            Object rawSettings = entry.getValue();
            if (rawSettings == null) {
                throw new ConfigValidationException(
                        worldPath + " must be a mapping of settings, got null");
            }
            if (!(rawSettings instanceof Map<?, ?> rawSettingsMap)) {
                throw new ConfigValidationException(
                        worldPath + " must be a mapping of settings, got "
                                + rawSettings.getClass().getSimpleName());
            }
            Map<String, Object> settingsMap = castStringKeyMap(rawSettingsMap, worldPath);
            // Reject unknown world-level keys — explicit fail-fast over silent defaults.
            for (String key : settingsMap.keySet()) {
                if (!"claim-enabled".equals(key) && !"vertical-mode".equals(key)) {
                throw new ConfigValidationException(
                        worldPath + " has unknown key '" + key
                                + "' (only 'claim-enabled' and 'vertical-mode' are supported in the current schema)");
                }
            }
            boolean claimEnabled = parseClaimEnabled(
                    settingsMap.get("claim-enabled"),
                    worldPath + ".claim-enabled");
            VerticalMode verticalMode;
            if (!settingsMap.containsKey("vertical-mode")) {
                verticalMode = VerticalMode.defaultMode();
            } else {
                verticalMode = VerticalMode.parse(
                        settingsMap.get("vertical-mode"), worldPath + ".vertical-mode");
            }
            result.put(worldName, new WorldSettings(claimEnabled, verticalMode));
        }
        return result;
    }

    private static SubjectDefaultsConfig parseSubjectDefaults(Object raw, String path) {
        Map<String, Object> map = requireMapping(raw, path, "'global' and 'worlds'");
        rejectUnknownKeys(map, path, Set.of("global", "worlds"));
        Map<ProtectionActionType, PermissionState> global =
                parseSubjectStates(map, "global", path + ".global");
        Map<String, Map<ProtectionActionType, PermissionState>> worlds = new LinkedHashMap<>();
        if (map.containsKey("worlds")) {
            Object rawWorlds = map.get("worlds");
            if (rawWorlds == null) {
                throw new ConfigValidationException(path + ".worlds must not be null");
            }
            if (!(rawWorlds instanceof Map<?, ?> rawWorldsMap)) {
                throw new ConfigValidationException(
                        path + ".worlds must be a mapping of world name -> defaults, got "
                                + rawWorlds.getClass().getSimpleName());
            }
            Map<String, Object> worldsMap = castStringKeyMap(rawWorldsMap, path + ".worlds");
            for (Map.Entry<String, Object> entry : worldsMap.entrySet()) {
                String worldName = entry.getKey();
                if (worldName == null || worldName.isBlank()) {
                    throw new ConfigValidationException(path + ".worlds requires a non-blank world name");
                }
                if (worlds.containsKey(worldName)) {
                    throw new ConfigValidationException(
                            path + ".worlds has a duplicate world entry '" + worldName + "'");
                }
                worlds.put(worldName, parseSubjectWorldStates(entry.getValue(),
                        path + ".worlds." + worldName));
            }
        }
        return new SubjectDefaultsConfig(global, worlds);
    }

    private static Map<ProtectionActionType, PermissionState> parseSubjectStates(
            Map<String, Object> parent, String key, String path) {
        if (!parent.containsKey(key)) {
            return Map.of();
        }
        Object raw = parent.get(key);
        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null");
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping of subject action -> ALLOW|DENY|INHERIT, got "
                            + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        Map<ProtectionActionType, PermissionState> out = new EnumMap<>(ProtectionActionType.class);
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String rawName = entry.getKey();
            String normalized = normalizeEnumKey(rawName, path);
            if (!seen.add(normalized)) {
                throw new ConfigValidationException(
                        path + " has a duplicate subject action '" + rawName
                                + "' (action keys are case-insensitive)");
            }
            ProtectionActionType action;
            try {
                action = ProtectionActionType.valueOf(normalized);
            } catch (IllegalArgumentException unknown) {
                throw new ConfigValidationException(
                        path + "." + rawName + " is not a known SUBJECT_PERMISSION action "
                                + "(rule keys belong under 'rule-defaults')");
            }
            PermissionState state = parseDefaultState(entry.getValue(), path + "." + rawName);
            if (state == PermissionState.INHERIT) {
                continue;
            }
            out.put(action, state);
        }
        return out;
    }

    private static Map<ProtectionActionType, PermissionState> parseSubjectWorldStates(
            Object raw, String path) {
        if (raw == null) {
            throw new ConfigValidationException(path + " must be a mapping of subject action -> state, got null");
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping of subject action -> ALLOW|DENY|INHERIT, got "
                            + raw.getClass().getSimpleName());
        }
        Map<String, Object> wrapper = Map.of("states", raw);
        return parseSubjectStates(wrapper, "states", path);
    }

    private static RuleDefaultsConfig parseRuleDefaults(Object raw, String path) {
        Map<String, Object> map = requireMapping(raw, path, "'global' and 'worlds'");
        rejectUnknownKeys(map, path, Set.of("global", "worlds"));
        Map<LandRuleType, PermissionState> global =
                parseRuleStates(map, "global", path + ".global");
        Map<String, Map<LandRuleType, PermissionState>> worlds = new LinkedHashMap<>();
        if (map.containsKey("worlds")) {
            Object rawWorlds = map.get("worlds");
            if (rawWorlds == null) {
                throw new ConfigValidationException(path + ".worlds must not be null");
            }
            if (!(rawWorlds instanceof Map<?, ?> rawWorldsMap)) {
                throw new ConfigValidationException(
                        path + ".worlds must be a mapping of world name -> defaults, got "
                                + rawWorlds.getClass().getSimpleName());
            }
            Map<String, Object> worldsMap = castStringKeyMap(rawWorldsMap, path + ".worlds");
            for (Map.Entry<String, Object> entry : worldsMap.entrySet()) {
                String worldName = entry.getKey();
                if (worldName == null || worldName.isBlank()) {
                    throw new ConfigValidationException(path + ".worlds requires a non-blank world name");
                }
                if (worlds.containsKey(worldName)) {
                    throw new ConfigValidationException(
                            path + ".worlds has a duplicate world entry '" + worldName + "'");
                }
                worlds.put(worldName, parseRuleWorldStates(entry.getValue(),
                        path + ".worlds." + worldName));
            }
        }
        return new RuleDefaultsConfig(global, worlds);
    }

    private static Map<LandRuleType, PermissionState> parseRuleStates(
            Map<String, Object> parent, String key, String path) {
        if (!parent.containsKey(key)) {
            return Map.of();
        }
        Object raw = parent.get(key);
        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null");
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping of rule -> ALLOW|DENY|INHERIT, got "
                            + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        Map<LandRuleType, PermissionState> out = new EnumMap<>(LandRuleType.class);
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String rawName = entry.getKey();
            String normalized = normalizeEnumKey(rawName, path);
            if (!seen.add(normalized)) {
                throw new ConfigValidationException(
                        path + " has a duplicate rule '" + rawName
                                + "' (rule keys are case-insensitive)");
            }
            LandRuleType rule;
            try {
                rule = LandRuleType.valueOf(normalized);
            } catch (IllegalArgumentException unknown) {
                throw new ConfigValidationException(
                        path + "." + rawName + " is not a known LAND_RULE "
                                + "(subject action keys belong under 'subject-defaults')");
            }
            PermissionState state = parseDefaultState(entry.getValue(), path + "." + rawName);
            if (state == PermissionState.INHERIT) {
                continue;
            }
            out.put(rule, state);
        }
        return out;
    }

    private static Map<LandRuleType, PermissionState> parseRuleWorldStates(
            Object raw, String path) {
        if (raw == null) {
            throw new ConfigValidationException(path + " must be a mapping of rule -> state, got null");
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping of rule -> ALLOW|DENY|INHERIT, got "
                            + raw.getClass().getSimpleName());
        }
        Map<String, Object> wrapper = Map.of("states", raw);
        return parseRuleStates(wrapper, "states", path);
    }

    private static Map<String, Object> requireMapping(Object raw, String path, String expected) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping with " + expected + ", got "
                            + raw.getClass().getSimpleName());
        }
        return castStringKeyMap(rawMap, path);
    }

    private static void rejectUnknownKeys(Map<String, Object> map, String path, Set<String> allowed) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new ConfigValidationException(
                        path + " has unknown key '" + key + "' (only "
                                + String.join(", ", allowed.stream().map(k -> "'" + k + "'").toList())
                                + " are supported here)");
            }
        }
    }

    private static String normalizeEnumKey(String rawName, String path) {
        if (rawName == null || rawName.isBlank()) {
            throw new ConfigValidationException(path + " requires a non-blank key");
        }
        return rawName.trim().toUpperCase(Locale.ROOT);
    }

    private static PermissionState parseDefaultState(Object raw, String path) {
        if (!(raw instanceof String text)) {
            throw new ConfigValidationException(
                    path + " must be ALLOW, DENY or INHERIT, got "
                            + (raw == null ? "null" : raw.getClass().getSimpleName() + " value '" + raw + "'"));
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        try {
            return PermissionState.valueOf(normalized);
        } catch (IllegalArgumentException unknown) {
            throw new ConfigValidationException(
                    path + " must be ALLOW, DENY or INHERIT, got '" + text + "'");
        }
    }

    private static LimitSettings parseLimits(Object raw, String path) {        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null");
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping, got " + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        // Reject unknown keys under limits.
        for (String key : map.keySet()) {
            if (!key.equals("max-lands-per-player")
                    && !key.equals("max-total-chunks-per-player")
                    && !key.equals("max-chunks-per-land")
                    && !key.equals("max-sublands-per-land")
                    && !key.equals("max-selection-side-length")
                    && !key.equals("max-selection-chunks")
                    && !key.equals("max-decision-cache-entries")) {
                throw new ConfigValidationException(
                        path + " has unknown key '" + key + "'");
            }
        }
        LimitSettings defaults = LimitSettings.defaults();
        int maxLands = parseLimitField(map, "max-lands-per-player",
                path + ".max-lands-per-player", defaults.maxLandsPerPlayer());
        int maxChunksPerPlayer = parseLimitField(map, "max-total-chunks-per-player",
                path + ".max-total-chunks-per-player", defaults.maxTotalChunksPerPlayer());
        int maxChunksPerLand = parseLimitField(map, "max-chunks-per-land",
                path + ".max-chunks-per-land", defaults.maxChunksPerLand());
        int maxSublands = parseLimitField(map, "max-sublands-per-land",
                path + ".max-sublands-per-land", defaults.maxSublandsPerLand());
        int maxSelectionSide = parseLimitField(map, "max-selection-side-length",
                path + ".max-selection-side-length", defaults.maxSelectionSideLength());
        int maxSelectionChunks = parseLimitField(map, "max-selection-chunks",
                path + ".max-selection-chunks", defaults.maxSelectionChunks());
        return new LimitSettings(maxLands, maxChunksPerPlayer, maxChunksPerLand, maxSublands,
                maxSelectionSide, maxSelectionChunks);
    }

    private static int parseLimitField(Map<String, Object> map, String key, String path, int defaultValue) {
        if (!map.containsKey(key)) {
            return defaultValue;
        }
        Object raw = map.get(key);
        if (raw == null) {
            throw new ConfigValidationException(path + " must not be null");
        }
        return parseIntLimit(raw, path);
    }

    private static MessageSettings parseMessages(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping, got " + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        for (String key : map.keySet()) {
            if (!"default-locale".equals(key) && !"cooldown-seconds".equals(key)) {
                throw new ConfigValidationException(path + " has unknown key '" + key + "'");
            }
        }
        // default-locale
        Locale defaultLocale;
        if (!map.containsKey("default-locale")) {
            defaultLocale = MessageSettings.defaults().defaultLocale();
        } else {
            Object rawLocale = map.get("default-locale");
            if (rawLocale == null) {
                throw new ConfigValidationException(path + ".default-locale must not be null");
            }
            if (!(rawLocale instanceof String s)) {
                throw new ConfigValidationException(
                        path + ".default-locale must be a string, got " + rawLocale.getClass().getSimpleName());
            }
            defaultLocale = MessageSettings.parseLocaleTag(s, path + ".default-locale");
        }
        // cooldown-seconds
        int cooldown;
        if (!map.containsKey("cooldown-seconds")) {
            cooldown = MessageSettings.defaults().cooldownSeconds();
        } else {
            Object rawCooldown = map.get("cooldown-seconds");
            if (rawCooldown == null) {
                throw new ConfigValidationException(path + ".cooldown-seconds must not be null");
            }
            cooldown = parseIntLimit(rawCooldown, path + ".cooldown-seconds");
        }
        return new MessageSettings(defaultLocale, cooldown);
    }

    private static SelectionSettings parseSelection(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new ConfigValidationException(
                    path + " must be a mapping, got " + raw.getClass().getSimpleName());
        }
        Map<String, Object> map = castStringKeyMap(rawMap, path);
        for (String key : map.keySet()) {
            if (!"session-timeout-seconds".equals(key)
                    && !"visualization-max-segments".equals(key)
                    && !"visualization-max-particles-per-tick".equals(key)
                    && !"visualization-render-distance-blocks".equals(key)
                    && !"visualization-refresh-interval-ticks".equals(key)) {
                throw new ConfigValidationException(path + " has unknown key '" + key + "'");
            }
        }
        int timeout;
        if (!map.containsKey("session-timeout-seconds")) {
            timeout = SelectionSettings.DEFAULT_SESSION_TIMEOUT_SECONDS;
        } else {
            Object rawTimeout = map.get("session-timeout-seconds");
            if (rawTimeout == null) {
                throw new ConfigValidationException(path + ".session-timeout-seconds must not be null");
            }
            timeout = parseIntLimit(rawTimeout, path + ".session-timeout-seconds");
            if (timeout <= 0) {
                throw new ConfigValidationException(
                        path + ".session-timeout-seconds must be > 0, got " + timeout);
            }
        }
        SelectionSettings defaults = SelectionSettings.defaults();
        int maxSegments = parseVisualizationField(
                map, "visualization-max-segments", path, defaults.visualizationMaxSegments(), 1,
                SelectionVisualizationBudget.MAX_SEGMENTS_LIMIT);
        int maxParticles = parseVisualizationField(
                map, "visualization-max-particles-per-tick", path, defaults.visualizationMaxParticlesPerTick(), 1,
                SelectionVisualizationBudget.MAX_PARTICLES_PER_TICK_LIMIT);
        int renderDistance = parseVisualizationField(
                map, "visualization-render-distance-blocks", path, defaults.visualizationRenderDistanceBlocks(),
                SelectionVisualizationBudget.MIN_RENDER_DISTANCE_BLOCKS,
                SelectionVisualizationBudget.RENDER_DISTANCE_BLOCKS_LIMIT);
        int refreshInterval = parseVisualizationField(
                map, "visualization-refresh-interval-ticks", path, defaults.visualizationRefreshIntervalTicks(), 1,
                SelectionVisualizationBudget.REFRESH_INTERVAL_TICKS_LIMIT);
        try {
            return new SelectionSettings(timeout, maxSegments, maxParticles, renderDistance, refreshInterval);
        } catch (IllegalArgumentException ex) {
            throw new ConfigValidationException(path + " has an out-of-range visualization budget: " + ex.getMessage());
        }
    }

    private static int parseVisualizationField(
            Map<String, Object> map, String key, String path, int defaultValue, int min, int max) {
        if (!map.containsKey(key)) {
            return defaultValue;
        }
        Object raw = map.get(key);
        if (raw == null) {
            throw new ConfigValidationException(path + "." + key + " must not be null");
        }
        int value = parseIntLimit(raw, path + "." + key);
        if (value < min || value > max) {
            throw new ConfigValidationException(
                    path + "." + key + " must be in [" + min + ", " + max + "], got " + value);
        }
        return value;
    }

    private static int parseIntLimit(Object raw, String path) {
        if (raw instanceof Double || raw instanceof Float) {
            throw new ConfigValidationException(
                    path + " must be an integer (no floating point), got " + raw);
        }
        if (raw instanceof BigInteger bi) {
            if (bi.signum() < 0) {
                throw new ConfigValidationException(path + " must be >= 0, got " + bi);
            }
            if (bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new ConfigValidationException(path + " out of range (> Integer.MAX_VALUE): " + bi);
            }
            return bi.intValue();
        }
        if (raw instanceof BigDecimal bd) {
            BigInteger bi;
            try {
                bi = bd.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new ConfigValidationException(path + " must be an integer, got " + raw);
            }
            if (bi.signum() < 0) {
                throw new ConfigValidationException(path + " must be >= 0, got " + bi);
            }
            if (bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new ConfigValidationException(path + " out of range (> Integer.MAX_VALUE): " + bi);
            }
            return bi.intValue();
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            long lv = ((Number) raw).longValue();
            if (lv < 0) {
                throw new ConfigValidationException(path + " must be >= 0, got " + lv);
            }
            if (lv > Integer.MAX_VALUE) {
                throw new ConfigValidationException(path + " out of range (> Integer.MAX_VALUE): " + lv);
            }
            return (int) lv;
        }
        if (raw instanceof Number) {
            throw new ConfigValidationException(
                    path + " must be an integer, got " + raw.getClass().getSimpleName() + " value '" + raw + "'");
        }
        throw new ConfigValidationException(
                path + " must be an integer, got " + raw.getClass().getSimpleName() + " value '" + raw + "'");
    }

    private static boolean parseClaimEnabled(Object raw, String path) {
        if (raw == null) {
            throw new ConfigValidationException(
                    path + " must be a boolean (true/false), got null");
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        throw new ConfigValidationException(
                path + " must be a boolean (true/false), got "
                        + raw.getClass().getSimpleName()
                        + " value '" + raw + "'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeyMap(Map<?, ?> raw, String path) {
        Map<String, Object> result = new HashMap<>(raw.size());
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String s)) {
                throw new ConfigValidationException(
                        (path.isEmpty() ? "<root>" : path)
                                + " requires string keys, got "
                                + (key == null ? "null" : key.getClass().getSimpleName()));
            }
            result.put(s, entry.getValue());
        }
        return result;
    }

    /**
     * Convenience: parse raw YAML text (snakeyaml) and validate. Useful as a
     * single-call entry point when the loader only has access to a string.
     *
     * @throws ConfigValidationException for schema / type violations
     */
    public static ChunkLandConfig parseYamlText(String yaml) {
        Object root = new Yaml().load(yaml);
        return parseAndValidate(root);
    }
}
