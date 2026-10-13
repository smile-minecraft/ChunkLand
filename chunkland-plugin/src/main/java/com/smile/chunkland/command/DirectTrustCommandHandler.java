package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land trust} / {@code /land untrust} handler.
 *
 * <p>Both subcommands act on the land under the sender's current position:
 * the tail arg names the affected player, never a land. The affected land
 * is fixed before resolution starts and never re-resolved afterwards. The
 * target accepts UUID text, an online exact name, or a known offline name:
 * the first two resolve synchronously, the last through the shared async
 * operation on the resolver's explicit executor, so the region-facing call
 * never blocks. Unknown names, timeouts, failures and reserved names
 * ({@code EVERYONE}, {@code *}, group syntax) fail closed with one generic
 * denial that never reveals whether the name exists. After resolution the
 * viewer, the fixed land and the slot gate are re-checked on the viewer
 * thread before the single allowed write. The domain gate
 * ({@code MANAGE_MEMBER}) is enforced by the {@link LandCommand} dispatcher
 * before this handler runs; this handler owns only input collection and the
 * shared operation call.
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
    private final OfflinePlayerResolver offlinePlayers;
    private final PlayerScheduler scheduler;
    private final RosterChangeOperation.Gate gate;
    private final RosterChangeOperation.Presence presence;
    private final RosterChangeOperation.Lands knownLands;
    private final BooleanSupplier alive;

    /**
     * @param mode which binding change to perform
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param playerIds target lookup (UUID text or online exact name); null
     *                  resolves every name fail closed
     * @param offlinePlayers async offline-name resolution; when present, a
     *                       name the sync lookup misses is resolved on its
     *                       explicit executor without blocking the caller.
     *                       {@code null} keeps the legacy online-only behaviour
     * @param scheduler player-thread hop for the async continuation;
     *                  {@code null} runs continuations inline
     * @param gate live slot-gate re-check on the fixed land; {@code null}
     *             denies every async completion
     * @param presence viewer-online re-check; {@code null} denies every
     *                 async completion
     * @param knownLands fixed-land existence re-check; {@code null} denies
     *                   every async completion
     * @param alive enable-generation liveness; {@code null} denies every
     *              async completion
     * @param mutation durable mutation; null replies unavailable without side effects
     */
    public DirectTrustCommandHandler(Mode mode, LandResolver lands,
            Function<String, Optional<UUID>> playerIds, OfflinePlayerResolver offlinePlayers,
            PlayerScheduler scheduler, RosterChangeOperation.Gate gate,
            RosterChangeOperation.Presence presence, RosterChangeOperation.Lands knownLands,
            BooleanSupplier alive, TrustMutation mutation) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.lands = lands;
        this.playerIds = playerIds;
        this.mutation = mutation;
        this.offlinePlayers = offlinePlayers;
        this.scheduler = scheduler;
        this.gate = gate;
        this.presence = presence;
        this.knownLands = knownLands;
        this.alive = alive;
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
        // The affected land is fixed before resolution starts and is never
        // re-resolved from the sender's position afterwards, so waiting for
        // an offline lookup cannot retarget the change at a moved player.
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land." + base + ".failed", Map.of("reason", base + ".unknown_land"));
            return;
        }
        RosterChangeOperation.Slot slot =
                mode == Mode.TRUST ? RosterChangeOperation.Slot.TRUST : RosterChangeOperation.Slot.UNTRUST;
        RosterChangeOperation operation = operation();
        operation.submit(player, actor, land.get(), slot, rawTarget,
                result -> reply(result, base, sink), null);
    }

    private void reply(RosterChangeOperation.Result result, String base, ReplySink sink) {
        try {
            switch (result.outcome()) {
                case SUCCESS -> sink.reply("command.land." + base + ".success",
                        Map.of("player", result.label()));
                case UNKNOWN_TARGET -> sink.reply("command.land." + base + ".failed",
                        Map.of("reason", base + ".unknown_target"));
                case UNAVAILABLE -> sink.reply("command.land." + base + ".failed",
                        Map.of("reason", base + ".unavailable"));
                default -> sink.reply("command.land." + base + ".failed",
                        Map.of("reason", base + ".failed"));
            }
        } catch (RuntimeException replyFailure) {
            // Terminal reply path: never let a sink failure escape.
        }
    }

    private RosterChangeOperation operation() {
        return new RosterChangeOperation(playerIds, offlinePlayers, scheduler, gate,
                presence, knownLands, alive, null,
                slot -> (actor, landId, target) -> {
                    if (mutation == null) {
                        return null;
                    }
                    try {
                        return mutation.apply(actor, landId, target);
                    } catch (RuntimeException failure) {
                        return null;
                    }
                });
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
