package com.smile.chunkland;

import com.smile.chunkland.adapter.AceLibBridge;
import com.smile.chunkland.adapter.AceLibLifecycle;
import com.smile.chunkland.capability.Capabilities;
import com.smile.chunkland.capability.M0CapabilityProbe;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.config.YamlFileConfigLoader;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.message.M0MessageProbe;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ChunkLand server plugin entry point.
 *
 * <p>Lifecycle is intentionally thin: all AceLib acquisition and fail-closed policy
 * live in {@link AceLibBridge} / {@link AceLibLifecycle}, which are testable without a
 * server. This class only wires the Bukkit callbacks to those seams. After a ready
 * AceLib API is acquired it builds two temporary M0 probes:</p>
 * <ul>
 *   <li>{@link M0MessageProbe} for the M0-08 message pipeline smoke.</li>
 *   <li>{@link M0CapabilityProbe} for the M0-07 capability (scheduler / GUI / Form /
 *       {@code cancelAll}) smoke.</li>
 * </ul>
 *
 * <p>Both probes must be fail-closed (their {@code tryBuild} returns empty when the API
 * is missing or not ready), so this class never NPEs on a missing AceLib facade.</p>
 *
 * <p>The config-system wiring owns a {@link ConfigService} that holds the parsed
 * {@code config.yml} snapshot. Reload is exposed as a programmatic API on this class
 * so a future command-tree task can wire a {@code /land reload} subcommand without
 * touching the rest of the lifecycle. The Bukkit {@code /reload} command is
 * intentionally NOT supported.</p>
 *
 * <p>The {@code chunkland} command does NOT carry a top-level Bukkit {@code permission}
 * entry (see {@code plugin.yml}); instead each subcommand is gated explicitly here:</p>
 * <ul>
 *   <li>{@code m0message} requires {@link #PERMISSION_M0_MESSAGE}.</li>
 *   <li>{@code m0test} requires {@link #PERMISSION_M0_TEST}.</li>
 * </ul>
 *
 * <p>Denied senders see a localised notice and never reach the probe.</p>
 */
public final class ChunkLandPlugin extends JavaPlugin {
    public static final String NAME = "ChunkLand";

    /** Permission key for {@code /chunkland m0message}. */
    public static final String PERMISSION_M0_MESSAGE = "chunkland.debug.m0message";
    /** Permission key for {@code /chunkland m0test}. */
    public static final String PERMISSION_M0_TEST = "chunkland.debug.m0test";

    private final AceLibBridge bridge = new AceLibBridge();
    private Optional<M0MessageProbe> messagePipeline = Optional.empty();
    private Optional<M0CapabilityProbe> capabilityProbe = Optional.empty();
    private Optional<Capabilities> capabilities = Optional.empty();
    private Optional<ConfigService> configService = Optional.empty();
    private Optional<ChunkLandMessagePipeline> landMessagePipeline = Optional.empty();
    private LandCommand landCommand;

    public ChunkLandPlugin() {
    }

    /**
     * @return the AceLib bridge (non-null after construction); its state is populated
     *         during {@link #onEnable()} and cleared during {@link #onDisable()}.
     */
    public AceLibBridge getBridge() {
        return bridge;
    }

    /**
     * @return the live config service, or empty when bootstrap failed (the failure
     *         is logged in {@link #onEnable()}). Reload is a programmatic API —
     *         a future command-tree task will gate it behind a {@code /land reload}
     *         subcommand; this class intentionally does NOT register one yet.
     */
    public Optional<ConfigService> getConfigService() {
        return configService;
    }

    @Override
    public void onEnable() {
        AceLibLifecycle.enable(
            bridge,
            AceLibBridge.fromServicesManager(getServer().getServicesManager()),
            getLogger(),
            () -> getServer().getPluginManager().disablePlugin(this)
        );
        // Config-system wiring: bootstrap the config service from data-folder/config.yml.
        // On a missing or invalid file we keep the plugin alive with defaults
        // and log a warning — the admin can fix the file and call reload() later.
        try {
            YamlFileConfigLoader loader = new YamlFileConfigLoader(
                    getDataFolder().toPath().resolve("config.yml"));
            this.configService = Optional.of(new ConfigService(loader));
        } catch (RuntimeException ex) {
            getLogger().warning(
                    "ChunkLand config bootstrap failed; starting with defaults. "
                            + "Reason: " + ex.getMessage());
            // Fall back to a service backed by the embedded default YAML so
            // downstream readers always see a valid snapshot.
            this.configService = Optional.of(new ConfigService(
                    new com.smile.chunkland.config.ResourceConfigLoader(
                            getClass(), "/config.yml")));
        }
        // Only build the message probe after a ready AceLib API is held. If AceLib is
        // missing/not-ready, bridge.getApi() is null and tryBuild returns empty (fail-closed,
        // no NPE). The probe command reports "not ready" instead of pretending to send.
        this.messagePipeline = M0MessageProbe.tryBuild(this, bridge.getApi());
        // M0-07 capability probe: independent of the message probe; both return empty when
        // the API is not ready. The probe owns a SafeScheduler created via the public
        // AceLibScheduler.create(...) factory and exposes four smoke paths.
        this.capabilityProbe = M0CapabilityProbe.tryBuild(this, bridge.getApi());
        // Land message pipeline for /land ReplySink (same key+vars contract). Fail-closed
        // when AceLib is not ready; the sink falls back to plain text.
        Locale defaultLocale = configService.map(s -> s.current().messages().defaultLocale()).orElse(Locale.US);
        this.landMessagePipeline = ChunkLandMessagePipeline.tryBuild(this, bridge.getApi(), defaultLocale);
        this.landCommand = new LandCommand(LandCommand.defaultStubHandlers(), null);
        // Capture the underlying capabilities bundle (when built) so onDisable can call
        // cancelAll() in one place. tryBuild returns Optional<M0CapabilityProbe>; for the
        // release path we re-derive the bundle by peeking at the probe's stored reference.
        this.capabilities = capabilityProbe.map(M0CapabilityProbe::capabilities);
    }

    @Override
    public void onDisable() {
        // Idempotent: Bukkit may invoke onDisable more than once (e.g. after a
        // self-disable during shutdown). Releasing bridge state is always safe.
        this.messagePipeline = Optional.empty();
        this.capabilityProbe = Optional.empty();
        this.landMessagePipeline = Optional.empty();
        this.landCommand = null;
        // Cancel the local SafeScheduler BEFORE clearing the bridge state so the
        // cancelAll() call uses the same scheduler instance the smoke tests dispatched to.
        if (capabilities.isPresent()) {
            try {
                capabilities.get().release();
            } catch (RuntimeException ignored) {
                // fail-closed: a release-time failure must not stop onDisable from clearing
                // the bridge.
            }
        }
        this.capabilities = Optional.empty();
        // ConfigService is an in-memory holder with no native resources;
        // dropping the reference is enough. The reload listener list is
        // package-private and GC'd along with the service.
        this.configService = Optional.empty();
        bridge.release();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("chunkland")) {
            return dispatch(sender, args, () -> this.messagePipeline, () -> this.capabilityProbe);
        }
        if (command.getName().equalsIgnoreCase("land")) {
            LandCommand cmd = this.landCommand;
            if (cmd == null) {
                cmd = new LandCommand(LandCommand.defaultStubHandlers(), null);
            }
            ChunkLandMessagePipeline pipeline = this.landMessagePipeline.orElse(null);
            return cmd.dispatch(sender, args, pipeline);
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("land")) {
            return LandCommand.tabComplete(sender, args);
        }
        return super.onTabComplete(sender, command, alias, args);
    }

    // Visible for tests: inject a custom land pipeline / command
    void setLandMessagePipelineForTest(ChunkLandMessagePipeline pipeline) {
        this.landMessagePipeline = Optional.ofNullable(pipeline);
    }

    void setLandCommandForTest(LandCommand command) {
        this.landCommand = command;
    }

    Optional<ChunkLandMessagePipeline> getLandMessagePipeline() {
        return landMessagePipeline;
    }

    /**
     * Pure dispatcher extracted from {@link #onCommand} so tests can exercise the permission
     * and routing logic without standing up a Bukkit {@link Command} or server.
     *
     * @param sender        the command sender
     * @param args          full command args (including the leading subcommand token)
     * @param messageProbe  lazy lookup for the M0 message probe (must already be built by
     *                      {@code onEnable}); tests pass {@code () -> probe} to inject a stub
     * @param capabilityProbe lazy lookup for the M0 capability probe
     * @return {@code true} when the entry-point is recognised (so Bukkit stops scanning
     *         aliases); {@code false} for unknown commands
     */
    static boolean dispatch(CommandSender sender,
                            String[] args,
                            Supplier<Optional<M0MessageProbe>> messageProbe,
                            Supplier<Optional<M0CapabilityProbe>> capabilityProbe) {
        if (args == null || args.length < 1) {
            return false;
        }
        if (args[0].equalsIgnoreCase("m0message")) {
            if (!sender.hasPermission(PERMISSION_M0_MESSAGE)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_MESSAGE + "，拒絕執行 m0message。");
                return true;
            }
            return M0MessageProbe.handleCommand(sender, args, messageProbe.get());
        }
        if (args[0].equalsIgnoreCase("m0test")) {
            if (!sender.hasPermission(PERMISSION_M0_TEST)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_TEST + "，拒絕執行 m0test。");
                return true;
            }
            return M0CapabilityProbe.handleCommand(sender, args, capabilityProbe.get());
        }
        return false;
    }

    /**
     * Server-independent dispatch seam used by tests. Mirrors {@link #dispatch} but takes a
     * {@link PermissionProbedProbeGate} so tests can observe whether the probe was actually
     * invoked (vs. short-circuited by a permission deny).
     */
    @FunctionalInterface
    public interface PermissionProbedProbeGate {
        /**
         * @return {@code true} if the gate observed that the probe was reached (the caller
         *         can use this to assert "denied routes must not call the probe").
         */
        boolean invoke(CommandSender sender, String[] args, String subcommand);
    }

    /**
     * Server-independent dispatch seam that drives the supplied gate in place of the real
     * probe call. Used by {@code M0CapabilityRedContractTest} to assert deny/allow paths.
     */
    public static boolean dispatchForTest(CommandSender sender,
                                          Command command,
                                          String label,
                                          String[] args,
                                          Supplier<Optional<M0MessageProbe>> messageProbe,
                                          PermissionProbedProbeGate gate) {
        if (!command.getName().equalsIgnoreCase("chunkland")) {
            return false;
        }
        if (args == null || args.length < 1) {
            return false;
        }
        if (args[0].equalsIgnoreCase("m0message")) {
            if (!sender.hasPermission(PERMISSION_M0_MESSAGE)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_MESSAGE + "，拒絕執行 m0message。");
                return true;
            }
            gate.invoke(sender, args, "m0message");
            return true;
        }
        if (args[0].equalsIgnoreCase("m0test")) {
            if (!sender.hasPermission(PERMISSION_M0_TEST)) {
                sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION_M0_TEST + "，拒絕執行 m0test。");
                return true;
            }
            gate.invoke(sender, args, "m0test");
            return true;
        }
        return false;
    }
}

