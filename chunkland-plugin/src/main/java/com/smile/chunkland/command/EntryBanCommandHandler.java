package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land ban} / {@code /land unban} handler.
 *
 * <p>Both subcommands act on the land under the sender's current position:
 * the tail arg names the affected player, never a land. The target resolves
 * only through the injected lookup — a UUID string or an online exact name —
 * so unknown or offline names fail closed without any network query, and
 * reserved names ({@code EVERYONE}, {@code *}, group syntax) never resolve.
 * The domain gate ({@code MANAGE_MEMBER}) is enforced by the
 * {@link LandCommand} dispatcher before this handler runs; this handler owns
 * only input resolution, the owner/Server Land guards, and the durable
 * mutation call. Bans never reuse the untrust path and never open an
 * {@code EVERYONE} entry: they write their own durable ban row.
 *
 * <p>Banning the land owner or banning on a Server Land fails closed before
 * any write, so the Owner Guarantee and the steward contract stay intact.
 * Unbanning is idempotent removal: resending without a ban still succeeds.
 *
 * <p>Threading: everything sender-facing (sender type, UUID, land and target
 * resolution) happens synchronously on the calling thread; only the reply
 * runs on mutation completion, using values captured up front, so the
 * callback never touches server objects.
 */
public final class EntryBanCommandHandler implements LandCommand.Handler {

    /** Which ENTRY ban change this instance performs. */
    public enum Mode {
        BAN,
        UNBAN
    }

    /** Durable mutation entry; kept as a seam so tests observe the call. */
    @FunctionalInterface
    public interface BanMutation {
        CompletionStage<Void> apply(UUID actor, LandId landId, UUID target);
    }

    /** Resolves the affected land for the sender; empty means fail closed. */
    @FunctionalInterface
    public interface LandResolver {
        Optional<LandId> resolve(CommandSender sender);
    }

    /** Reads the land snapshot for owner/Server Land guards; empty means fail closed. */
    @FunctionalInterface
    public interface LandView {
        Optional<LandSnapshot> view(LandId landId);
    }

    private final Mode mode;
    private final LandResolver lands;
    private final Function<String, Optional<UUID>> playerIds;
    private final LandView views;
    private final BanMutation mutation;

    /**
     * @param mode which ENTRY ban change to perform
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param playerIds target lookup (UUID text or online exact name); null
     *                  resolves every name fail closed
     * @param views land snapshot source for the owner/Server Land guards;
     *              null or empty resolves fail closed
     * @param mutation durable mutation; null replies unavailable without side effects
     */
    public EntryBanCommandHandler(Mode mode, LandResolver lands,
            Function<String, Optional<UUID>> playerIds, LandView views, BanMutation mutation) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.lands = lands;
        this.playerIds = playerIds;
        this.views = views;
        this.mutation = mutation;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        String base = mode == Mode.BAN ? "ban" : "unban";
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
        if (mode == Mode.BAN && !bannable(landId, targetId)) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".not_allowed"));
            return;
        }
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

    /**
     * Whether the target may be banned on the land: the land must be known
     * and player-owned, and the target must not be its owner. Server Land
     * and the owner fail closed before any write.
     */
    private boolean bannable(LandId landId, UUID targetId) {
        if (views == null) {
            return false;
        }
        LandSnapshot land;
        try {
            Optional<LandSnapshot> found = views.view(landId);
            if (found == null || found.isEmpty() || found.get() == null) {
                return false;
            }
            land = found.get();
        } catch (RuntimeException failure) {
            return false;
        }
        try {
            if (land.ownerRef() instanceof OwnerRef.ServerOwnerRef) {
                return false;
            }
            if (land.ownerRef() instanceof OwnerRef.PlayerOwnerRef player
                    && player.uuid().equals(targetId)) {
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
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
