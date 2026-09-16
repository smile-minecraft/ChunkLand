package com.smile.chunkland.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of the parsed {@code config.yml}.
 *
 * <p>This snapshot is the canonical, validation-passed view of the ChunkLand
 * configuration. It is the value type referenced by
 * {@link ConfigService#current()} and republished on every successful reload.
 * All collections are defensively copied on construction; no mutator exists.</p>
 *
 * <p>Two epoch counters travel with the snapshot:</p>
 * <ul>
     *   <li>{@code globalPolicyEpoch} — bumped on every successful reload so cache
     *       keys keyed on it become stale on reload. It does not by itself mean
     *       that global policy content changed.</li>
 *   <li>{@code worldPolicyEpochs} — per-world counter. The map is the union of
 *       worlds known to the previous and the new snapshot; every entry is
 *       bumped together on a successful reload so cache entries tied to a world
 *       become stale even if that world is dropped from the new config.</li>
     * </ul>
     *
     * <p>The limits, messages and selection timeout values are the global policy
     * content used to compute {@link ReloadDiff#globalPolicyChanged()}.</p>
 *
 * <p>The {@code worlds} map holds the typed per-world settings
 * ({@link WorldSettings}) keyed by world name. Names are case-sensitive.</p>
 *
 * <p>The {@code subjectDefaults} and {@code ruleDefaults} payloads hold the
 * world/global permission defaults from the {@code subject-defaults} and
 * {@code rule-defaults} sections, keyed by world name. They are separate
 * typed namespaces that never fall back to each other; world names are
 * resolved to UUIDs once at load/bootstrap by the permission defaults
 * snapshot, never on the hot path.</p>
 *
 * <p>{@code decisionCacheMaxEntries} bounds the protection decision cache
 * ({@code limits.max-decision-cache-entries}, default 4096, zero disables).
 * It is plain payload like the other limit values: reloads replace it from
 * the freshly loaded file while epochs keep their bumped counters.</p>
 */
public final class ChunkLandConfig {

    private final Map<String, WorldSettings> worlds;
    private final LimitSettings limits;
    private final MessageSettings messages;
    private final SelectionSettings selection;
    private final SubjectDefaultsConfig subjectDefaults;
    private final RuleDefaultsConfig ruleDefaults;
    private final EconomySettings economy;
    private final long globalPolicyEpoch;
    private final Map<String, Long> worldPolicyEpochs;
    private final int decisionCacheMaxEntries;

    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs) {
        this(worlds, LimitSettings.defaults(), MessageSettings.defaults(), SelectionSettings.defaults(),
                globalPolicyEpoch, worldPolicyEpochs);
    }

    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           LimitSettings limits,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs) {
        this(worlds, limits, MessageSettings.defaults(), SelectionSettings.defaults(), globalPolicyEpoch, worldPolicyEpochs);
    }

    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           LimitSettings limits,
                           MessageSettings messages,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs) {
        this(worlds, limits, messages, SelectionSettings.defaults(), globalPolicyEpoch, worldPolicyEpochs);
    }

    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           LimitSettings limits,
                           MessageSettings messages,
                           SelectionSettings selection,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs) {
        this(worlds, limits, messages, selection, SubjectDefaultsConfig.empty(), RuleDefaultsConfig.empty(),
                globalPolicyEpoch, worldPolicyEpochs);
    }

    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           LimitSettings limits,
                           MessageSettings messages,
                           SelectionSettings selection,
                           SubjectDefaultsConfig subjectDefaults,
                           RuleDefaultsConfig ruleDefaults,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs,
                           int decisionCacheMaxEntries) {
        this(worlds, limits, messages, selection, subjectDefaults, ruleDefaults, null,
                globalPolicyEpoch, worldPolicyEpochs, decisionCacheMaxEntries);
    }

    /**
     * Canonical snapshot with the typed economy section. A {@code null}
     * economy means {@code config.yml} carries no {@code economy} section:
     * old configs keep parsing, while pricing resolution fails closed
     * downstream instead of falling back to a zero table.
     */
    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           LimitSettings limits,
                           MessageSettings messages,
                           SelectionSettings selection,
                           SubjectDefaultsConfig subjectDefaults,
                           RuleDefaultsConfig ruleDefaults,
                           EconomySettings economy,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs,
                           int decisionCacheMaxEntries) {
        Objects.requireNonNull(worlds, "worlds");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(subjectDefaults, "subjectDefaults");
        Objects.requireNonNull(ruleDefaults, "ruleDefaults");
        Objects.requireNonNull(worldPolicyEpochs, "worldPolicyEpochs");
        if (globalPolicyEpoch < 0) {
            throw new IllegalArgumentException(
                    "globalPolicyEpoch must be non-negative: " + globalPolicyEpoch);
        }
        if (decisionCacheMaxEntries < 0) {
            throw new IllegalArgumentException(
                    "decisionCacheMaxEntries must be >= 0: " + decisionCacheMaxEntries);
        }
        Map<String, WorldSettings> defensiveWorlds = new HashMap<>(worlds.size());
        for (Map.Entry<String, WorldSettings> e : worlds.entrySet()) {
            String name = Objects.requireNonNull(e.getKey(), "world name");
            WorldSettings settings = Objects.requireNonNull(e.getValue(),
                    "world settings for " + name);
            if (name.isBlank()) {
                throw new IllegalArgumentException("world name must not be blank");
            }
            defensiveWorlds.put(name, settings);
        }
        Map<String, Long> defensiveEpochs = new HashMap<>(worldPolicyEpochs.size());
        for (Map.Entry<String, Long> e : worldPolicyEpochs.entrySet()) {
            String name = Objects.requireNonNull(e.getKey(), "worldPolicyEpoch key");
            Long epoch = Objects.requireNonNull(e.getValue(),
                    "worldPolicyEpoch value for " + name);
            if (epoch < 0) {
                throw new IllegalArgumentException(
                        "worldPolicyEpoch must be non-negative for " + name + ": " + epoch);
            }
            defensiveEpochs.put(name, epoch);
        }
        this.worlds = Collections.unmodifiableMap(defensiveWorlds);
        this.limits = limits;
        this.messages = messages;
        this.selection = selection;
        this.subjectDefaults = subjectDefaults;
        this.ruleDefaults = ruleDefaults;
        this.economy = economy;
        this.globalPolicyEpoch = globalPolicyEpoch;
        this.worldPolicyEpochs = Collections.unmodifiableMap(defensiveEpochs);
        this.decisionCacheMaxEntries = decisionCacheMaxEntries;
    }

    /**
     * Compatibility overload: the decision-cache budget defaults to
     * {@code 4096} (see the decision cache default). Prefer the canonical
     * constructor with an explicit budget for production snapshots.
     */
    public ChunkLandConfig(Map<String, WorldSettings> worlds,
                           LimitSettings limits,
                           MessageSettings messages,
                           SelectionSettings selection,
                           SubjectDefaultsConfig subjectDefaults,
                           RuleDefaultsConfig ruleDefaults,
                           long globalPolicyEpoch,
                           Map<String, Long> worldPolicyEpochs) {
        this(worlds, limits, messages, selection, subjectDefaults, ruleDefaults,
                globalPolicyEpoch, worldPolicyEpochs, 4096);
    }

    /** Default snapshot: no worlds, both epoch counters at zero. */
    public static ChunkLandConfig defaults() {
        return new ChunkLandConfig(Map.of(), LimitSettings.defaults(), MessageSettings.defaults(),
                SelectionSettings.defaults(), 0L, Map.of());
    }

    public Map<String, WorldSettings> worlds() {
        return worlds;
    }

    public LimitSettings limits() {
        return limits;
    }

    public MessageSettings messages() {
        return messages;
    }

    public SelectionSettings selection() {
        return selection;
    }

    public SubjectDefaultsConfig subjectDefaults() {
        return subjectDefaults;
    }

    public RuleDefaultsConfig ruleDefaults() {
        return ruleDefaults;
    }

    /**
     * Typed economy section, or {@code null} when {@code config.yml} carries
     * no {@code economy} key. Null is the backward-compatible state for
     * pre-economy configs; callers must treat it as pricing-unavailable,
     * never as a zero-price table.
     */
    public EconomySettings economy() {
        return economy;
    }

    public long globalPolicyEpoch() {
        return globalPolicyEpoch;
    }

    public Map<String, Long> worldPolicyEpochs() {
        return worldPolicyEpochs;
    }

    /**
     * Decision-cache entry budget from {@code limits.max-decision-cache-entries}.
     * Zero disables the cache; always non-negative.
     */
    public int decisionCacheMaxEntries() {
        return decisionCacheMaxEntries;
    }

    /** Lookup helper: returns the epoch for {@code worldName} or {@code null}. */
    public Long worldPolicyEpoch(String worldName) {
        return worldPolicyEpochs.get(worldName);
    }

    public WorldSettings worldSettings(String worldName) {
        return worlds.get(worldName);
    }

    public Set<String> worldNames() {
        return worlds.keySet();
    }

    /**
     * Return a new snapshot with {@code newWorlds} substituted. The epoch
     * counters are preserved so this helper can be used during validation
     * without accidentally bumping them; the {@link ConfigService} is the only
     * place that produces epoch-bumped snapshots.
     */
    public ChunkLandConfig withWorlds(Map<String, WorldSettings> newWorlds) {
        Objects.requireNonNull(newWorlds, "newWorlds");
        return new ChunkLandConfig(newWorlds, limits, messages, selection, subjectDefaults, ruleDefaults,
                economy, globalPolicyEpoch, worldPolicyEpochs, decisionCacheMaxEntries);
    }

    public ChunkLandConfig withLimits(LimitSettings newLimits) {
        Objects.requireNonNull(newLimits, "newLimits");
        return new ChunkLandConfig(worlds, newLimits, messages, selection, subjectDefaults, ruleDefaults,
                economy, globalPolicyEpoch, worldPolicyEpochs, decisionCacheMaxEntries);
    }

    public ChunkLandConfig withMessages(MessageSettings newMessages) {
        Objects.requireNonNull(newMessages, "newMessages");
        return new ChunkLandConfig(worlds, limits, newMessages, selection, subjectDefaults, ruleDefaults,
                economy, globalPolicyEpoch, worldPolicyEpochs, decisionCacheMaxEntries);
    }

    public ChunkLandConfig withSubjectDefaults(SubjectDefaultsConfig newSubjectDefaults) {
        Objects.requireNonNull(newSubjectDefaults, "newSubjectDefaults");
        return new ChunkLandConfig(worlds, limits, messages, selection, newSubjectDefaults, ruleDefaults,
                economy, globalPolicyEpoch, worldPolicyEpochs, decisionCacheMaxEntries);
    }

    public ChunkLandConfig withRuleDefaults(RuleDefaultsConfig newRuleDefaults) {
        Objects.requireNonNull(newRuleDefaults, "newRuleDefaults");
        return new ChunkLandConfig(worlds, limits, messages, selection, subjectDefaults, newRuleDefaults,
                economy, globalPolicyEpoch, worldPolicyEpochs, decisionCacheMaxEntries);
    }

    /**
     * Return a copy with the economy section substituted. {@code null}
     * clears it back to the pre-economy state.
     */
    public ChunkLandConfig withEconomy(EconomySettings newEconomy) {
        return new ChunkLandConfig(worlds, limits, messages, selection, subjectDefaults, ruleDefaults,
                newEconomy, globalPolicyEpoch, worldPolicyEpochs, decisionCacheMaxEntries);
    }

    /**
     * Return a new snapshot that is identical to this one except every epoch
     * has been incremented by one. The {@code worldPolicyEpochs} map is the
     * union of this snapshot's known worlds and the {@code nextWorlds}'s
     * known worlds, so removed worlds still see a bumped counter and any
     * cache key tied to them becomes stale.
     */
    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds) {
        Objects.requireNonNull(nextWorlds, "nextWorlds");
        // Compute the union of worlds known to either the previous snapshot
        // or the incoming payload, then bump each exactly once. Worlds that
        // appear in both still get a single bump; worlds that disappear
        // still get a bump so any cache key tied to them becomes stale.
        Set<String> union = new LinkedHashSet<>(this.worldPolicyEpochs.keySet());
        union.addAll(nextWorlds.keySet());
        Map<String, Long> bumped = new HashMap<>(union.size());
        for (String name : union) {
            long previousValue = this.worldPolicyEpochs.getOrDefault(name, 0L);
            bumped.put(name, Math.addExact(previousValue, 1L));
        }
        return new ChunkLandConfig(
                nextWorlds,
                this.limits,
                this.messages,
                this.selection,
                this.subjectDefaults,
                this.ruleDefaults,
                this.economy,
                Math.addExact(this.globalPolicyEpoch, 1L),
                bumped,
                this.decisionCacheMaxEntries);
    }

    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds, LimitSettings nextLimits) {
        Objects.requireNonNull(nextWorlds, "nextWorlds");
        Objects.requireNonNull(nextLimits, "nextLimits");
        Set<String> union = new LinkedHashSet<>(this.worldPolicyEpochs.keySet());
        union.addAll(nextWorlds.keySet());
        Map<String, Long> bumped = new HashMap<>(union.size());
        for (String name : union) {
            long previousValue = this.worldPolicyEpochs.getOrDefault(name, 0L);
            bumped.put(name, Math.addExact(previousValue, 1L));
        }
        return new ChunkLandConfig(
                nextWorlds,
                nextLimits,
                this.messages,
                this.selection,
                this.subjectDefaults,
                this.ruleDefaults,
                this.economy,
                Math.addExact(this.globalPolicyEpoch, 1L),
                bumped,
                this.decisionCacheMaxEntries);
    }

    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds, LimitSettings nextLimits, MessageSettings nextMessages) {
        return withEpochsBumped(nextWorlds, nextLimits, nextMessages, this.selection);
    }

    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds,
                                            LimitSettings nextLimits,
                                            MessageSettings nextMessages,
                                            SelectionSettings nextSelection) {
        return withEpochsBumped(nextWorlds, nextLimits, nextMessages, nextSelection,
                this.subjectDefaults, this.ruleDefaults);
    }

    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds,
                                              LimitSettings nextLimits,
                                              MessageSettings nextMessages,
                                              SelectionSettings nextSelection,
                                              SubjectDefaultsConfig nextSubjectDefaults,
                                              RuleDefaultsConfig nextRuleDefaults,
                                              int nextDecisionCacheMaxEntries) {
        return withEpochsBumped(nextWorlds, nextLimits, nextMessages, nextSelection,
                nextSubjectDefaults, nextRuleDefaults, this.economy, nextDecisionCacheMaxEntries);
    }

    /**
     * Reload-path overload: the freshly loaded economy replaces the previous
     * one (a removed section clears back to null) while epochs bump exactly
     * once. A {@code null} next economy is the backward-compatible
     * pre-economy state, not an error.
     */
    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds,
                                              LimitSettings nextLimits,
                                              MessageSettings nextMessages,
                                              SelectionSettings nextSelection,
                                              SubjectDefaultsConfig nextSubjectDefaults,
                                              RuleDefaultsConfig nextRuleDefaults,
                                              EconomySettings nextEconomy,
                                              int nextDecisionCacheMaxEntries) {
        Objects.requireNonNull(nextWorlds, "nextWorlds");
        Objects.requireNonNull(nextLimits, "nextLimits");
        Objects.requireNonNull(nextMessages, "nextMessages");
        Objects.requireNonNull(nextSelection, "nextSelection");
        Objects.requireNonNull(nextSubjectDefaults, "nextSubjectDefaults");
        Objects.requireNonNull(nextRuleDefaults, "nextRuleDefaults");
        Set<String> union = new LinkedHashSet<>(this.worldPolicyEpochs.keySet());
        union.addAll(nextWorlds.keySet());
        Map<String, Long> bumped = new HashMap<>(union.size());
        for (String name : union) {
            long previousValue = this.worldPolicyEpochs.getOrDefault(name, 0L);
            bumped.put(name, Math.addExact(previousValue, 1L));
        }
        return new ChunkLandConfig(
                nextWorlds,
                nextLimits,
                nextMessages,
                nextSelection,
                nextSubjectDefaults,
                nextRuleDefaults,
                nextEconomy,
                Math.addExact(this.globalPolicyEpoch, 1L),
                bumped,
                nextDecisionCacheMaxEntries);
    }

    /**
     * Compatibility overload: keeps this snapshot's decision-cache budget.
     * The reload path uses the seven-argument overload with the freshly
     * loaded budget instead.
     */
    public ChunkLandConfig withEpochsBumped(Map<String, WorldSettings> nextWorlds,
                                             LimitSettings nextLimits,
                                             MessageSettings nextMessages,
                                             SelectionSettings nextSelection,
                                             SubjectDefaultsConfig nextSubjectDefaults,
                                             RuleDefaultsConfig nextRuleDefaults) {
        return withEpochsBumped(nextWorlds, nextLimits, nextMessages, nextSelection,
                nextSubjectDefaults, nextRuleDefaults, this.decisionCacheMaxEntries);
    }
}
