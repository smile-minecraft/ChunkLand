package com.smile.chunkland.command;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ManagementPermissionGate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land manage} handler: the player-reachable entry to the
 * Java management GUI.
 *
 * <p>The sender must be a player standing on a land they may manage: the
 * target resolves through the injected {@link ManagementGateResolver} (the
 * production wiring uses the current-location resolver over the immutable
 * snapshot) and the handler re-checks the shared {@link ManagementPermissionGate}
 * for {@code MANAGE_PERMISSION} itself, because the dispatcher has no
 * domain-action mapping for {@code manage}. Only an ALLOW reaches the
 * caller-owned {@link GuiOpener} seam, which owns the region-thread hop and
 * the navigator open. Consoles, unresolvable targets, gate denials, missing
 * seams and opener failures all fail closed on generic replies that never
 * probe whether a land exists. The handler performs no mutation, economy,
 * SQL, chunk-load or network access: row-level mutations stay on the
 * existing {@code /land} handler/service path behind the same gate.
 */
public final class ManageGuiCommandHandler implements LandCommand.Handler {

    /**
     * Caller-owned GUI open step. Implementations own every Bukkit touch —
     * including the Folia region-thread hop — and delegate the snapshot,
     * gate and page work to the shared plugin entry.
     */
    @FunctionalInterface
    public interface GuiOpener {
        /**
         * @param player resolved player; never {@code null}
         * @param landId gate-approved target land; never {@code null}
         */
        void open(Player player, com.smile.chunkland.api.land.LandId landId);
    }

    private final ManagementGateResolver gateResolver;
    private final GuiOpener opener;

    /**
     * @param gateResolver domain-gate inputs; {@code null} fails every call
     *                     closed without side effects
     * @param opener GUI open step; {@code null} fails every call closed
     *               without side effects
     */
    public ManageGuiCommandHandler(ManagementGateResolver gateResolver, GuiOpener opener) {
        this.gateResolver = gateResolver;
        this.opener = opener;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.manage.console", Map.of());
            return;
        }
        if (gateResolver == null || opener == null) {
            sink.reply("command.land.manage.denied", Map.of());
            return;
        }
        Optional<ManagementGateResolver.Request> resolved;
        try {
            resolved = gateResolver.resolve(
                    sender, ProtectionActionType.MANAGE_PERMISSION, args);
        } catch (RuntimeException unresolved) {
            sink.reply("command.land.manage.denied", Map.of());
            return;
        }
        if (resolved == null || resolved.isEmpty() || resolved.get() == null) {
            sink.reply("command.land.manage.denied", Map.of());
            return;
        }
        ManagementGateResolver.Request request = resolved.get();
        boolean allowed;
        try {
            allowed = ManagementPermissionGate.check(
                    request.actor(),
                    request.landId(),
                    ProtectionActionType.MANAGE_PERMISSION,
                    request.snapshot(),
                    request.adminBypass(),
                    request.serverLandSteward(),
                    request.provider()).outcome() == PermissionState.ALLOW;
        } catch (RuntimeException denied) {
            sink.reply("command.land.manage.denied", Map.of());
            return;
        }
        if (!allowed) {
            sink.reply("command.land.manage.denied", Map.of());
            return;
        }
        try {
            opener.open(player, request.landId());
        } catch (RuntimeException failure) {
            sink.reply("command.land.manage.denied", Map.of());
        }
    }
}
