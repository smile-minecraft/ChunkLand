package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land trust} / {@code /land untrust} handler.
 *
 * <p>Both subcommands act on the land under the sender's current position:
 * the tail arg names the affected player, never a land. The target resolves
 * only through the injected lookup — a UUID string or an online exact name —
 * so unknown or offline names fail closed without any network query, and
 * reserved names ({@code EVERYONE}, {@code *}, group syntax) never resolve.
 * The domain gate ({@code MANAGE_MEMBER}) is enforced by the
 * {@link LandCommand} dispatcher before this handler runs; this handler owns
 * only input resolution and the durable mutation call.
 *
 * <p>Threading: everything sender-facing (sender type, UUID, land and target
 * resolution) happens synchronously on the calling thread; only the reply
 * runs on mutation completion, using values captured up front, so the
 * callback never touches server objects.
 */
public final class DirectTrustCommandHandler implements LandCommand.Handler {

    /** Which binding change this instance performs. */
    public enum Mode {
        TRUST,
        UNTRUST
    }

    /** Durable mutation entry; kept as a seam so tests observe the call. */
    @FunctionalInterface
    public interface TrustMutation {
        CompletionStage<Void> apply(UUID actor, LandId landId, UUID target);
    }

    /** Resolves the affected land for the sender; empty means fail closed. */
    @FunctionalInterface
    public interface LandResolver {
        Optional<LandId> resolve(CommandSender sender);
    }

    private final Mode mode;
    private final LandResolver lands;
    private final Function<String, Optional<UUID>> playerIds;
    private final TrustMutation mutation;

    /**
     * @param mode which binding change to perform
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param playerIds target lookup (UUID text or online exact name); null
     *                  resolves every name fail closed
     * @param mutation durable mutation; null replies unavailable without side effects
     */
    public DirectTrustCommandHandler(Mode mode, LandResolver lands,
            Function<String, Optional<UUID>> playerIds, TrustMutation mutation) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.lands = lands;
        this.playerIds = playerIds;
        this.mutation = mutation;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        String base = mode == Mode.TRUST ? "trust" : "untrust";
        if (!(sender instanceof Player player)) {
            sink.reply("command.land." + base + ".console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".failed"));
            return;
        }
        String rawTarget = tailOf(args);
        if (rawTarget == null) {
            sink.reply("command.land." + base + ".usage", Map.of());
            return;
        }
        if (mutation == null) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".unavailable"));
            return;
        }
        Optional<UUID> target = resolveTarget(rawTarget);
        if (target.isEmpty() || target.get() == null) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".unknown_target"));
            return;
        }
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".unknown_land"));
            return;
        }
        UUID targetId = target.get();
        LandId landId = land.get();
        String targetLabel = rawTarget.strip();
        CompletionStage<Void> stage;
        try {
            stage = mutation.apply(actor, landId, targetId);
        } catch (RuntimeException failure) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".failed"));
            return;
        }
        stage.whenComplete((ignored, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land." + base + ".failed",
                            Map.of("reason", base + ".failed"));
                } else {
                    sink.reply("command.land." + base + ".success",
                            Map.of("player", targetLabel));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private Optional<UUID> resolveTarget(String raw) {
        String stripped = raw.strip();
        if (stripped.isEmpty()
                || stripped.equalsIgnoreCase("EVERYONE")
                || stripped.equals("*")
                || stripped.toLowerCase(java.util.Locale.ROOT).startsWith("group(")) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(stripped));
        } catch (IllegalArgumentException notUuid) {
            // Not a UUID: only an online exact name may resolve it, and only
            // through the injected lookup (no network query here).
        }
        if (playerIds == null) {
            return Optional.empty();
        }
        try {
            Optional<UUID> found = playerIds.apply(stripped);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
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

    private static String tailOf(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        String raw = args[1];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw;
    }
}
