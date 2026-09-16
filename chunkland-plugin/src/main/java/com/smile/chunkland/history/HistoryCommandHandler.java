package com.smile.chunkland.history;

import com.smile.chunkland.api.history.HistoryEntry;
import com.smile.chunkland.api.history.HistoryQuery;
import com.smile.chunkland.api.history.HistoryResult;
import com.smile.chunkland.api.history.WorldHistoryProvider;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.command.ReplySink;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Read-only {@code /land history} handler over the optional world-history
 * provider.
 *
 * <p>The handler takes no arguments and reports the bounded block-history
 * around the sender's own position. World identity plus block coordinates
 * are captured on the calling thread from already-read Bukkit state; the
 * provider runs the optional backend lookup on its injected executor and the
 * terminal callback only renders redacted entries (coordinates, action and
 * material — never attribution or backend detail), capped at the queried
 * limit as a second bound.
 *
 * <p>Every degraded state fails closed on one generic unavailable key:
 * console senders, unreadable locations, missing or unavailable providers
 * and every query failure. The handler performs no mutation, economy, chunk
 * loading or network access of its own, and replies never escape the
 * terminal callback.
 *
 * <p>The provider lookup runs on its injected executor, so the terminal
 * callback may arrive on a background thread where touching the
 * {@link ReplySink} is unsafe for players. Player completions therefore hop
 * through the injected {@link PlayerScheduler} to the sender's player thread
 * first (production: the Folia entity scheduler); a retired scheduler drops
 * the reply fail-closed. Console senders have no player thread and reply
 * inline. Only values captured before the async boundary (the query limit
 * plus the provider's plain result) cross into the hop — the callback never
 * reads the player or any other Bukkit state.
 */
public final class HistoryCommandHandler implements LandCommand.Handler {

    private final Supplier<WorldHistoryProvider> providers;
    private final PlayerScheduler scheduler;

    /**
     * @param providers history provider source; {@code null} or failing reads
     *                  fail closed
     */
    public HistoryCommandHandler(Supplier<WorldHistoryProvider> providers) {
        this(providers, PlayerScheduler.direct());
    }

    /**
     * @param providers history provider source; {@code null} or failing reads
     *                  fail closed
     * @param scheduler player-thread hop for the async terminal reply;
     *                  {@code null} replies inline on the completing thread
     */
    public HistoryCommandHandler(Supplier<WorldHistoryProvider> providers,
            PlayerScheduler scheduler) {
        this.providers = providers;
        this.scheduler = scheduler == null ? PlayerScheduler.direct() : scheduler;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.history.unavailable", Map.of());
            return;
        }
        if (args != null && args.length > 1) {
            sink.reply("command.land.history.usage", Map.of());
            return;
        }
        HistoryQuery query = captureQuery(player);
        if (query == null) {
            sink.reply("command.land.history.unavailable", Map.of());
            return;
        }
        WorldHistoryProvider provider = readProvider();
        if (provider == null || !isAvailable(provider)) {
            sink.reply("command.land.history.unavailable", Map.of());
            return;
        }
        CompletionStage<HistoryResult> stage = queryAsync(provider, query);
        if (stage == null) {
            sink.reply("command.land.history.unavailable", Map.of());
            return;
        }
        final int limit = query.maxResults();
        final PlayerScheduler hop = scheduler;
        stage.whenComplete((result, failure) -> {
            // The completion may arrive on the provider executor: never touch
            // the sink here — hop to the player thread first. A retired
            // scheduler drops the reply fail-closed without leaking.
            try {
                hop.runForPlayer(player, () -> replyTerminal(sink, result, failure, limit));
            } catch (RuntimeException retired) {
                // Scheduling itself failed: drop fail-closed without replying.
            }
        });
    }

    private static void replyTerminal(ReplySink sink, HistoryResult result,
            Throwable failure, int limit) {
        try {
            if (failure != null || result == null || !result.available()) {
                sink.reply("command.land.history.unavailable", Map.of());
            } else if (result.entries().isEmpty()) {
                sink.reply("command.land.history.empty", Map.of());
            } else {
                replyLines(sink, result.entries(), limit);
            }
        } catch (RuntimeException replyFailure) {
            // Terminal reply path: never let a sink failure escape.
        }
    }

    private static void replyLines(ReplySink sink, List<HistoryEntry> entries, int limit) {
        int sent = 0;
        for (HistoryEntry entry : entries) {
            if (entry == null || sent >= limit) {
                continue;
            }
            sent++;
            sink.reply("command.land.history.line", Map.of("value", entry.describe()));
        }
        if (sent == 0) {
            sink.reply("command.land.history.empty", Map.of());
        }
    }

    private static HistoryQuery captureQuery(Player player) {
        try {
            Location location = player.getLocation();
            if (location == null) {
                return null;
            }
            Location captured = location.clone();
            World world = captured.getWorld();
            if (world == null) {
                return null;
            }
            String worldName = world.getName();
            UUID worldId = world.getUID();
            if (worldName == null || worldName.isBlank() || worldId == null) {
                return null;
            }
            return HistoryQuery.bounded(worldId, worldName,
                    captured.getBlockX(), captured.getBlockY(), captured.getBlockZ(),
                    HistoryQuery.DEFAULT_RADIUS_BLOCKS,
                    HistoryQuery.DEFAULT_SECONDS_BACK,
                    HistoryQuery.MAX_RESULTS);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private WorldHistoryProvider readProvider() {
        try {
            return providers == null ? null : providers.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean isAvailable(WorldHistoryProvider provider) {
        try {
            return provider.available();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static CompletionStage<HistoryResult> queryAsync(WorldHistoryProvider provider,
                                                            HistoryQuery query) {
        try {
            return provider.query(query);
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
