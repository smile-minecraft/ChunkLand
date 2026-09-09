package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;

/**
 * Resolves the Bukkit-dependent inputs for the shared management gate.
 *
 * <p>The resolver supplies inputs only — it never decides ALLOW/DENY.
 * {@link LandCommand} always feeds the resolved inputs through the Bukkit-free
 * domain gate, so no caller can smuggle an arbitrary allow predicate past the
 * single enforcement point. An empty result means the target cannot be
 * resolved yet and the dispatch fails closed without invoking the handler.
 */
public interface ManagementGateResolver {

    /** Resolved inputs for one management decision. */
    record Request(
            UUID actor,
            LandId landId,
            LandRegistry snapshot,
            boolean adminBypass,
            boolean serverLandSteward,
            PermissionContextProvider provider) {
        public Request {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(landId, "landId");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(provider, "provider");
        }
    }

    /**
     * Resolves gate inputs for a management subcommand.
     *
     * @param sender command sender attempting the operation
     * @param action management action for the subcommand
     * @param args full command args (subcommand + tail)
     * @return resolved inputs, or empty when the target cannot be resolved
     *         (the caller fails closed)
     */
    Optional<Request> resolve(CommandSender sender, ProtectionActionType action, String[] args);
}
