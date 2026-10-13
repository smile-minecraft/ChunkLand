package com.smile.chunkland.config;

import com.smile.chunkland.api.money.Currency;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bukkit-free coordinator for the admin reload path.
 *
 * <p>One {@link #reload()} attempt runs the content gate
 * ({@link ConfigReloadPolicy}) inside the service reload lock. While the
 * startup snapshot is the conservative fallback, the content gate is
 * replaced by the conservative rule: the previous snapshot is synthetic
 * fail-closed content, so the candidate is checked against the startup
 * world set instead — the live server worlds and the candidate's worlds
 * must both still be exactly that set, the candidate economy must match
 * the footprint the startup assembly was built with (absent, or disabled
 * in the startup currency), and a parseable candidate then publishes as
 * the operator's real intent. Any drift refuses the whole candidate with a restart prompt. A committed publish that leaves
 * conservative mode flips the shared conservative flag afterwards
 * (fail-closed ordering: decisions between the publish and the flip still
 * deny, never loosen).
 *
 * <p>The coordinator never rewrites {@code config.yml} and never refreshes
 * the last-known-good copy; both stay exactly as startup left them.
 */
public final class ConfigReloadCoordinator {

    private final ConfigService service;
    private final BooleanSupplier conservativeNow;
    private final Runnable exitConservative;
    private final Supplier<Set<String>> currentWorldNames;
    private final Set<String> startupWorldNames;
    private final Currency startupCurrency;

    /**
     * @param service live config service; never null
     * @param conservativeNow reads the shared conservative flag; never null
     * @param exitConservative clears the shared conservative flag; never null
     * @param currentWorldNames live server world names; never null
     * @param startupWorldNames world names captured at startup; never null
     * @param startupCurrency claim currency the startup assembly priced and
     *        refunded with (the conservative fallback for a missing economy
     *        section); never null
     */
    public ConfigReloadCoordinator(ConfigService service,
                                   BooleanSupplier conservativeNow,
                                   Runnable exitConservative,
                                   Supplier<Set<String>> currentWorldNames,
                                   Set<String> startupWorldNames,
                                   Currency startupCurrency) {
        this.service = Objects.requireNonNull(service, "service");
        this.conservativeNow = Objects.requireNonNull(conservativeNow, "conservativeNow");
        this.exitConservative = Objects.requireNonNull(exitConservative, "exitConservative");
        this.currentWorldNames = Objects.requireNonNull(currentWorldNames, "currentWorldNames");
        this.startupWorldNames = Set.copyOf(Objects.requireNonNull(startupWorldNames, "startupWorldNames"));
        this.startupCurrency = Objects.requireNonNull(startupCurrency, "startupCurrency");
    }

    /**
     * Run one gated reload attempt.
     *
     * <p>The content gate plus the conservative world-set check run inside
     * the service reload lock, so the published generation always matches
     * the candidate the gate approved. The world-set supplier therefore runs
     * under that lock; it must stay a fast memory/Bukkit read and never
     * block on I/O.</p>
     */
    public ReloadOutcome reload() {
        ReloadGate gate = (previous, loaded) -> {
            if (conservativeNow.getAsBoolean()) {
                return conservativeGate(loaded);
            }
            return ConfigReloadPolicy.check(previous, loaded);
        };
        ReloadOutcome outcome = service.tryReload(gate);
        if (outcome.kind() == ReloadOutcome.Kind.SUCCESS && conservativeNow.getAsBoolean()) {
            exitConservative.run();
        }
        return outcome;
    }

    /**
     * Conservative-startup gate: the previous snapshot is synthetic
     * fail-closed content, so comparing the candidate against it would
     * refuse every exit. Instead the candidate must name exactly the
     * startup world set, and the live server world set must still be that
     * set. The candidate economy must also match the footprint the startup
     * claim, expand, shrink and delete flows were assembled with — those
     * hold a zero pricing table in the startup currency behind a disabled
     * wrapper, so only an absent section, or a disabled section in the same
     * currency, publishes. Anything else (enabled purchases, another
     * currency) would report success while live claims stay free, so it
     * refuses the whole candidate with a restart prompt. Everything else
     * in a parseable candidate is the operator's real intent and publishes
     * at once.
     */
    private ReloadGate.GateVerdict conservativeGate(ChunkLandConfig loaded) {
        Set<String> current;
        try {
            current = currentWorldNames.get();
        } catch (RuntimeException unreadable) {
            return ReloadGate.GateVerdict.rejectRestart(
                    "server worlds unreadable; restart to apply");
        }
        if (current == null || !current.equals(startupWorldNames)) {
            return ReloadGate.GateVerdict.rejectRestart(
                    "server worlds changed since startup; restart to apply");
        }
        if (!loaded.worlds().keySet().equals(startupWorldNames)) {
            return ReloadGate.GateVerdict.rejectRestart(
                    "config worlds changed since startup; restart to apply");
        }
        EconomySettings economy = loaded.economy();
        if (economy != null
                && (economy.enabled() || !economy.currency().equals(startupCurrency))) {
            return ReloadGate.GateVerdict.rejectRestart(
                    "economy changed since startup; restart to apply");
        }
        if (!loaded.audit().equals(AuditSettings.defaults())) {
            // The startup fallback carries the default history window and
            // nothing reads it live, so any other value refuses like the
            // normal path does instead of drifting between the two rules.
            return ReloadGate.GateVerdict.rejectRestart(
                    "audit changed since startup; restart to apply");
        }
        return ReloadGate.GateVerdict.allow();
    }
}
