package com.smile.chunkland.command;

import java.util.Map;
import java.util.Objects;
import org.bukkit.command.CommandSender;

/**
 * Deterministic router for the {@code /land admin} slot.
 *
 * <p>The orphan branch ({@code admin orphan ...}) goes to the orphan handler
 * and every other admin verb stays on the ledger handler, so each branch keeps
 * its own permission node, usage copy and failure semantics. A missing branch
 * fails closed on its own unavailable reply without touching the other one.
 * Permission enforcement itself stays in {@link LandCommand} and in each
 * branch handler; this router only selects the branch.
 */
public final class AdminCommandRouter implements LandCommand.Handler {

    private final LandCommand.Handler ledger;
    private final LandCommand.Handler orphan;

    public AdminCommandRouter(LandCommand.Handler ledger, LandCommand.Handler orphan) {
        this.ledger = ledger;
        this.orphan = orphan;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (args != null && args.length >= 2 && "orphan".equalsIgnoreCase(args[1])) {
            if (orphan != null) {
                orphan.handle(sender, args, sink);
            } else {
                sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.unavailable"));
            }
            return;
        }
        if (ledger != null) {
            ledger.handle(sender, args, sink);
        } else {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.unavailable"));
        }
    }
}
