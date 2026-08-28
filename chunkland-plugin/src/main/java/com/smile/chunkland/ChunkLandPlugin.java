package com.smile.chunkland;

import com.smile.chunkland.adapter.AceLibBridge;
import com.smile.chunkland.adapter.AceLibLifecycle;
import com.smile.chunkland.message.M0MessageProbe;
import java.util.Optional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ChunkLand server plugin entry point.
 *
 * <p>Lifecycle is intentionally thin: all AceLib acquisition and fail-closed policy
 * live in {@link AceLibBridge} / {@link AceLibLifecycle}, which are testable without a
 * server. This class only wires the Bukkit callbacks to those seams, and (for M0-08)
 * builds the message pipeline once a ready AceLib API is acquired.</p>
 */
public final class ChunkLandPlugin extends JavaPlugin {
    public static final String NAME = "ChunkLand";

    private final AceLibBridge bridge = new AceLibBridge();
    private Optional<M0MessageProbe> messagePipeline = Optional.empty();

    public ChunkLandPlugin() {
    }

    /**
     * @return the AceLib bridge (non-null after construction); its state is populated
     *         during {@link #onEnable()} and cleared during {@link #onDisable()}.
     */
    public AceLibBridge getBridge() {
        return bridge;
    }

    @Override
    public void onEnable() {
        AceLibLifecycle.enable(
            bridge,
            AceLibBridge.fromServicesManager(getServer().getServicesManager()),
            getLogger(),
            () -> getServer().getPluginManager().disablePlugin(this)
        );
        // Only build the message probe after a ready AceLib API is held. If AceLib is
        // missing/not-ready, bridge.getApi() is null and tryBuild returns empty (fail-closed,
        // no NPE). The probe command reports "not ready" instead of pretending to send.
        this.messagePipeline = M0MessageProbe.tryBuild(this, bridge.getApi());
    }

    @Override
    public void onDisable() {
        // Idempotent: Bukkit may invoke onDisable more than once (e.g. after a
        // self-disable during shutdown). Releasing bridge state is always safe.
        this.messagePipeline = Optional.empty();
        bridge.release();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("chunkland")) {
            return false;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("m0message")) {
            return M0MessageProbe.handleCommand(sender, args, messagePipeline);
        }
        return false;
    }
}
