package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.rename.LandRenameResult;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land rename} handler: the single command entry point
 * into the rename flow.
 *
 * <p>The sender must be a player standing on an existing land, resolved
 * through the injected current-land view against the immutable snapshot;
 * the tail args name the new display name. The Bukkit command node only
 * controls who may try the subcommand — the owner/steward rule itself is
 * enforced by the rename flow, never here.
 *
 * <p>Fail-closed contract: console senders, missing or invalid names, an
 * unresolvable land and an unwired mutation reply without touching the
 * mutation. Every other outcome replies exactly once on mutation
 * completion, using values captured up front, so the callback never
 * touches server objects.
 */
public final class RenameCommandHandler implements LandCommand.Handler {

    /** Durable rename entry; kept as a seam so tests observe the call. */
    @FunctionalInterface
    public interface RenameMutation {
        CompletionStage<LandRenameResult> apply(
                UUID actor, LandId landId, String newName, boolean serverLandSteward);
    }

    /** Resolves the affected land for the sender; empty means fail closed. */
    @FunctionalInterface
    public interface LandResolver {
        Optional<LandId> resolve(CommandSender sender);
    }

    private final LandResolver lands;
    private final RenameMutation mutation;
    private final Function<CommandSender, Boolean> stewards;

    /**
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param mutation durable rename; null replies unavailable without side effects
     * @param stewards server-land grant source; null or failing resolves to
     *                 no grant (fail-closed)
     */
    public RenameCommandHandler(LandResolver lands, RenameMutation mutation,
            Function<CommandSender, Boolean> stewards) {
        this.lands = lands;
        this.mutation = mutation;
        this.stewards = stewards;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.rename.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.rename.failed", Map.of("reason", "rename.failed"));
            return;
        }
        String newName = displayNameOf(args);
        if (newName == null) {
            sink.reply("command.land.rename.usage", Map.of());
            return;
        }
        if (mutation == null) {
            sink.reply("command.land.rename.failed", Map.of("reason", "rename.unavailable"));
            return;
        }
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land.rename.failed", Map.of("reason", "rename.unknown_land"));
            return;
        }
        LandId landId = land.get();
        boolean steward = resolveSteward(sender);
        CompletionStage<LandRenameResult> stage;
        try {
            stage = mutation.apply(actor, landId, newName, steward);
        } catch (RuntimeException failure) {
            sink.reply("command.land.rename.failed", Map.of("reason", "rename.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.rename.failed", Map.of("reason", "rename.failed"));
            return;
        }
        stage.whenComplete((outcome, failure) -> replyOutcome(sink, outcome, failure));
    }

    private static void replyOutcome(ReplySink sink, LandRenameResult outcome, Throwable failure) {
        try {
            if (failure != null || outcome == null) {
                sink.reply("command.land.rename.failed", Map.of("reason", "rename.failed"));
                return;
            }
            switch (outcome.status()) {
                case SUCCESS -> sink.reply("command.land.rename.success",
                        Map.of("new_name", outcome.newDisplayName(),
                                "old_name", outcome.oldDisplayName()));
                case DEGRADED -> sink.reply("command.land.rename.degraded",
                        Map.of("new_name", outcome.newDisplayName(),
                                "old_name", outcome.oldDisplayName(),
                                "reason", outcome.diagnosticKey()));
                case REJECTED -> sink.reply("command.land.rename.rejected",
                        Map.of("reason", outcome.diagnosticKey()));
                case FAILED -> sink.reply("command.land.rename.failed",
                        Map.of("reason", outcome.diagnosticKey()));
            }
        } catch (RuntimeException ignored) {
            // Terminal reply path: never let a sink failure escape onto mutation threads.
        }
    }

    private boolean resolveSteward(CommandSender sender) {
        if (stewards == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stewards.apply(sender));
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private Optional<LandId> resolveLand(CommandSender sender) {
        if (lands == null) {
            return Optional.empty();
        }
        try {
            Optional<LandId> found = lands.resolve(sender);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /**
     * Join the tail args into a display name, or null when no usable name
     * was given. Validation reuses the land-name contract so blank input
     * and control characters fail closed to usage.
     */
    private static String displayNameOf(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            String part = args[i];
            if (part == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(part);
        }
        String candidate = joined.toString();
        try {
            LandName.normalize(candidate);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (candidate.isBlank()) {
            return null;
        }
        return candidate;
    }
}
