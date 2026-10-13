package com.smile.chunkland.command;

import com.smile.chunkland.config.ReloadOutcome;
import java.util.Map;
import java.util.Objects;

/**
 * Production {@code /land reload} handler: re-reads {@code config.yml}
 * through the gated reload path and reports one distinguishable outcome.
 *
 * <p>Players and the console share this slot; it never touches a selection,
 * a saga, the persistence store or the world state. It never calls the
 * server reload or any library-global reload: only the ChunkLand snapshot
 * (plus its derived generation) moves. Extra arguments answer with the usage line.
 */
public final class ReloadCommandHandler implements LandCommand.Handler {

    /** Runs one gated reload; the handler only maps the outcome to a reply. */
    @FunctionalInterface
    public interface ReloadRunner {
        ReloadOutcome reload();
    }

    private final ReloadRunner runner;

    /**
     * @param runner gated reload entry point; null keeps the slot fail-closed
     *        ({@code reload.unavailable}) instead of running half-wired
     */
    public ReloadCommandHandler(ReloadRunner runner) {
        this.runner = runner;
    }

    @Override
    public void handle(org.bukkit.command.CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (args != null && args.length > 1) {
            sink.reply("command.land.reload.usage", Map.of());
            return;
        }
        if (runner == null) {
            sink.reply("command.land.reload.failed", Map.of("reason", "reload.unavailable"));
            return;
        }
        ReloadOutcome outcome;
        try {
            outcome = runner.reload();
        } catch (RuntimeException failure) {
            sink.reply("command.land.reload.failed",
                    Map.of("reason", String.valueOf(failure.getMessage())));
            return;
        }
        if (outcome == null) {
            sink.reply("command.land.reload.failed", Map.of("reason", "reload.unavailable"));
            return;
        }
        switch (outcome.kind()) {
            case SUCCESS -> {
                if (outcome.callbackFailure() != null) {
                    sink.reply("command.land.reload.committed_callback_failed",
                            Map.of("reason", String.valueOf(outcome.callbackFailure().getMessage())));
                } else {
                    sink.reply("command.land.reload.success", Map.of());
                }
            }
            case NO_CHANGE -> sink.reply("command.land.reload.no_change", Map.of());
            case REJECTED -> sink.reply("command.land.reload.restart_required",
                    Map.of("reason", String.valueOf(outcome.reason())));
            case FAILED -> sink.reply("command.land.reload.failed",
                    Map.of("reason", String.valueOf(
                            outcome.cause() == null ? outcome.reason() : outcome.cause().getMessage())));
            case BUSY -> sink.reply("command.land.reload.busy", Map.of());
        }
    }
}
