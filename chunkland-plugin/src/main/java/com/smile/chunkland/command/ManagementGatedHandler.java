package com.smile.chunkland.command;

import org.bukkit.command.CommandSender;

/**
 * A {@link LandCommand.Handler} that executes against the exact immutable
 * gate inputs the dispatcher authorised.
 *
 * <p>Management subcommands whose mutation target can change namespace
 * between the dispatcher gate and the handler (for example a Player Land
 * converted to Server Land) must not re-resolve their decision basis from a
 * second live snapshot. Implementations re-run the shared domain gate from
 * the given request snapshot with the same action and inputs, resolve the
 * target land, chunks and owner from that same snapshot, and deny fail-closed
 * — without touching the mutation pipeline — when the live target no longer
 * matches the authorised namespace. Later mutations stay guarded by the
 * saga validator and the atomic commit's owner and revision checks.
 */
public interface ManagementGatedHandler extends LandCommand.Handler {

    /**
     * Handles the subcommand against the authorised gate request.
     *
     * @param sender command sender (must match the request actor)
     * @param args full command args (subcommand + tail)
     * @param sink reply sink
     * @param gateRequest the immutable inputs the dispatcher authorised;
     *                    never null
     */
    void handleGated(CommandSender sender, String[] args, ReplySink sink,
            ManagementGateResolver.Request gateRequest);
}
