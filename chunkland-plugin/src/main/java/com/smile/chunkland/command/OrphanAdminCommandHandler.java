package com.smile.chunkland.command;

import com.smile.chunkland.persistence.OrphanConflictException;
import com.smile.chunkland.persistence.OrphanPurgeRepository;
import com.smile.chunkland.persistence.OrphanUnknownException;
import com.smile.chunkland.runtime.storage.OrphanWorldGuard;
import com.smile.chunkland.runtime.storage.WorldCatalogSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Orphan-world administration behind {@code /land admin orphan}.
 *
 * <p>{@code list} renders the durable worlds absent from the versioned
 * catalog snapshot with bounded counters. {@code purge <world>} never
 * deletes: it captures the stored count and issues a single-use confirmation
 * binding the actor, the world, the count, the catalog generation, a random
 * nonce and an expiry. Only {@code purge <world> confirm <nonce>} with the
 * same actor, world, count, generation and a live nonce proceeds, and the
 * commit re-checks the generation and the world absence under the guard read
 * lock inside the same persistence transaction. Replays, expiries, wrong
 * actors/worlds/nonces, reappearing worlds, moved catalogs and lost races
 * all fail closed with zero durable side effect.
 *
 * <p>Access needs the independent {@code chunkland.admin.orphan} node; the
 * ledger, serverland and bypass nodes never grant this handler. All storage
 * runs on the persistence executor. Catalog snapshots are only ever taken
 * synchronously on the calling (command) thread through the guard — never
 * on a persistence thread — and the guard itself never touches Bukkit. An
 * unverified catalog (no successful world-list read yet) fails every verb
 * closed. When the sender
 * is a player the terminal reply hops through the injected
 * {@link PlayerScheduler} to the player thread first; console senders reply
 * inline on the completing thread. A successful purge refreshes the runtime
 * through the injected hook before reporting success; when the refresh fails
 * the reply says so and the deleted rows stay deleted — they are never
 * restored as a fake success. Nothing here calls Economy, touches the
 * operation ledger, or refunds.
 */
public final class OrphanAdminCommandHandler implements LandCommand.Handler {

    /** Maximum worlds rendered by one {@code list}; heaviest first. */
    static final int LIST_LIMIT = 20;

    /** Hard cap on one rendered line so an odd row cannot flood chat. */
    static final int LINE_VALUE_LIMIT = 220;

    /** How long an issued purge confirmation stays valid. */
    static final long CONFIRM_TTL_SECONDS = 120;

    private record PendingKey(String actorKey, UUID world) {
    }

    private record Pending(String nonce, int count, Instant expiresAt, long generation) {
    }

    private final Supplier<OrphanPurgeRepository> repositories;
    private final OrphanWorldGuard guard;
    private final Supplier<Instant> clock;
    private final PlayerScheduler scheduler;
    private final Supplier<? extends CompletionStage<?>> refresher;
    private final ConcurrentHashMap<PendingKey, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param repositories orphan storage source; {@code null} or failing reads fail closed
     * @param guard versioned world catalog; {@code null} or unverified fails every verb closed
     * @param clock time source for confirmation expiry and audit rows; {@code null} fails closed
     * @param scheduler player-thread hop for async replies; {@code null} replies inline
     * @param refresher runtime refresh after a successful purge; a {@code null} or failing
     *                  refresh reports failure while the delete stays deleted
     */
    public OrphanAdminCommandHandler(Supplier<OrphanPurgeRepository> repositories,
            OrphanWorldGuard guard,
            Supplier<Instant> clock,
            PlayerScheduler scheduler,
            Supplier<? extends CompletionStage<?>> refresher) {
        this.repositories = repositories;
        this.guard = guard;
        this.clock = clock;
        this.scheduler = scheduler == null ? PlayerScheduler.direct() : scheduler;
        this.refresher = refresher;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!hasOrphanPermission(sender)) {
            sink.reply("command.land.denied", Map.of("permission", LandPermissions.ORPHAN));
            return;
        }
        if (args == null || args.length < 3
                || !"admin".equalsIgnoreCase(args[0])
                || !"orphan".equalsIgnoreCase(args[1])) {
            sink.reply("command.land.admin.orphan.usage", Map.of());
            return;
        }
        String verb = args[2] == null ? "" : args[2].strip().toLowerCase(Locale.ROOT);
        switch (verb) {
            case "list" -> handleList(sender, args, sink);
            case "purge" -> handlePurge(sender, args, sink);
            default -> sink.reply("command.land.admin.orphan.usage", Map.of());
        }
    }

    // -----------------------------------------------------------------
    // list
    // -----------------------------------------------------------------

    private void handleList(CommandSender sender, String[] args, ReplySink sink) {
        if (args.length != 3) {
            sink.reply("command.land.admin.orphan.usage", Map.of());
            return;
        }
        WorldCatalogSnapshot catalog = captureCatalog();
        if (!isVerified(catalog)) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.unavailable"));
            return;
        }
        OrphanPurgeRepository repos = readRepos();
        if (repos == null) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.unavailable"));
            return;
        }
        CompletionStage<List<OrphanPurgeRepository.OrphanWorldSummary>> stage;
        try {
            stage = repos.listOrphanSummaries(catalog.loadedWorlds(), LIST_LIMIT);
        } catch (RuntimeException failure) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.failed"));
            return;
        }
        Player player = playerOf(sender);
        Locale locale = senderLocale(sender);
        stage.whenComplete((rows, failure) -> {
            try {
                if (failure != null || rows == null) {
                    replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                            Map.of("reason", "orphan.failed"), locale);
                } else if (rows.isEmpty()) {
                    replyAsync(player, sender, sink, "command.land.admin.orphan.empty",
                            Map.of(), locale);
                } else {
                    int shown = Math.min(rows.size(), LIST_LIMIT);
                    for (int i = 0; i < shown; i++) {
                        OrphanPurgeRepository.OrphanWorldSummary row = rows.get(i);
                        if (row == null) {
                            continue;
                        }
                        replyAsync(player, sender, sink, "command.land.admin.orphan.line",
                                Map.of("value", summarize(row)), locale);
                    }
                    if (rows.size() > shown) {
                        replyAsync(player, sender, sink, "command.land.admin.orphan.line",
                                Map.of("value", "and " + (rows.size() - shown)
                                        + " more; purge one world at a time"),
                                locale);
                    }
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    // -----------------------------------------------------------------
    // purge
    // -----------------------------------------------------------------

    private void handlePurge(CommandSender sender, String[] args, ReplySink sink) {
        if (args.length != 4 && args.length != 6) {
            sink.reply("command.land.admin.orphan.usage", Map.of());
            return;
        }
        final UUID world;
        try {
            world = parseWorldId(args[3]);
        } catch (IllegalArgumentException malformed) {
            sink.reply("command.land.admin.orphan.usage", Map.of());
            return;
        }
        if (args.length == 4) {
            issueConfirmation(sender, sink, world);
            return;
        }
        if (!"confirm".equalsIgnoreCase(args[4]) || args[5] == null || args[5].isBlank()) {
            sink.reply("command.land.admin.orphan.usage", Map.of());
            return;
        }
        executePurge(sender, sink, world, args[5].strip());
    }

    /**
     * Step one: capture the stored count and issue a single-use confirmation.
     * Worlds present in the verified catalog are refused before any SQL runs.
     */
    private void issueConfirmation(CommandSender sender, ReplySink sink, UUID world) {
        WorldCatalogSnapshot catalog = captureCatalog();
        if (!isVerified(catalog)) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.unavailable"));
            return;
        }
        if (catalog.isLoaded(world)) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.present"));
            return;
        }
        OrphanPurgeRepository repos = readRepos();
        if (repos == null) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.unavailable"));
            return;
        }
        CompletionStage<Integer> count;
        try {
            count = repos.countLandsInWorld(world);
        } catch (RuntimeException failure) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.failed"));
            return;
        }
        if (count == null) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.failed"));
            return;
        }
        String actorKey = actorKeyOf(sender);
        if (actorKey == null) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.unavailable"));
            return;
        }
        Player player = playerOf(sender);
        Locale locale = senderLocale(sender);
        count.whenComplete((landCount, failure) -> {
            try {
                if (failure != null || landCount == null) {
                    replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                            Map.of("reason", "orphan.failed"), locale);
                } else if (landCount <= 0) {
                    replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                            Map.of("reason", "orphan.unknown"), locale);
                } else {
                    Instant issued = readClock();
                    if (issued == null) {
                        replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                                Map.of("reason", "orphan.unavailable"), locale);
                        return;
                    }
                    String nonce = UUID.randomUUID().toString();
                    pending.put(new PendingKey(actorKey, world),
                            new Pending(nonce, landCount, issued.plusSeconds(CONFIRM_TTL_SECONDS),
                                    catalog.generation()));
                    replyAsync(player, sender, sink, "command.land.admin.orphan.confirm",
                            Map.of("world", world.toString(), "count", landCount,
                                    "nonce", nonce, "expiry", CONFIRM_TTL_SECONDS),
                            locale);
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    /**
     * Step two: consume the confirmation in one atomic remove, then run the
     * purge against a freshly captured catalog snapshot bound by generation.
     * The pending entry is gone before any async work starts, so a replay can
     * never run twice. The expiry instant itself is already too late.
     */
    private void executePurge(CommandSender sender, ReplySink sink, UUID world, String nonce) {
        String actorKey = actorKeyOf(sender);
        if (actorKey == null) {
            sink.reply("command.land.admin.orphan.failed", Map.of("reason", "orphan.unavailable"));
            return;
        }
        Pending claimed = pending.remove(new PendingKey(actorKey, world));
        Player player = playerOf(sender);
        Locale locale = senderLocale(sender);
        if (claimed == null) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.mismatch"), locale);
            return;
        }
        Instant now = readClock();
        if (now == null) {
            pending.putIfAbsent(new PendingKey(actorKey, world), claimed);
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.unavailable"), locale);
            return;
        }
        if (!now.isBefore(claimed.expiresAt())) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.expired"), locale);
            return;
        }
        if (!claimed.nonce().equals(nonce)) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.mismatch"), locale);
            return;
        }
        WorldCatalogSnapshot fresh = captureCatalog();
        if (!isVerified(fresh)) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.unavailable"), locale);
            return;
        }
        if (fresh.isLoaded(world)) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.present"), locale);
            return;
        }
        if (fresh.generation() != claimed.generation()) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.conflict"), locale);
            return;
        }
        OrphanPurgeRepository repos = readRepos();
        if (repos == null || guard == null) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.unavailable"), locale);
            return;
        }
        UUID actor = actorOf(sender);
        CompletionStage<OrphanPurgeRepository.OrphanPurgeResult> purge;
        try {
            purge = repos.purgeOrphanWorld(world, actor, fresh, now, claimed.count(), guard);
        } catch (RuntimeException failure) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.failed"), locale);
            return;
        }
        if (purge == null) {
            replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                    Map.of("reason", "orphan.failed"), locale);
            return;
        }
        purge.whenComplete((result, failure) -> {
            try {
                if (failure != null || result == null) {
                    replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                            Map.of("reason", mapFailure(failure)), locale);
                    return;
                }
                CompletionStage<?> refresh;
                try {
                    refresh = refresher == null ? null : refresher.get();
                } catch (RuntimeException refreshFailure) {
                    refresh = null;
                }
                if (refresh == null) {
                    replyAsync(player, sender, sink, "command.land.admin.orphan.failed",
                            Map.of("reason", "orphan.refresh_failed"), locale);
                    return;
                }
                refresh.whenComplete((ignored, refreshFailure) -> {
                    try {
                        if (refreshFailure != null) {
                            replyAsync(player, sender, sink,
                                    "command.land.admin.orphan.failed",
                                    Map.of("reason", "orphan.refresh_failed"), locale);
                        } else {
                            replyAsync(player, sender, sink,
                                    "command.land.admin.orphan.purged",
                                    Map.of("world", result.worldId().toString(),
                                            "count", result.landCount()),
                                    locale);
                        }
                    } catch (RuntimeException replyFailure) {
                        // Terminal reply path: never let a sink failure escape.
                    }
                });
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    // -----------------------------------------------------------------
    // Rendering (bounded, no payload or economy internals)
    // -----------------------------------------------------------------

    /** One bounded list line: world id plus its durable land count. */
    static String summarize(OrphanPurgeRepository.OrphanWorldSummary row) {
        return bound("world=" + row.worldId() + " lands=" + row.landCount());
    }

    private static String bound(String value) {
        if (value.length() <= LINE_VALUE_LIMIT) {
            return value;
        }
        return value.substring(0, LINE_VALUE_LIMIT);
    }

    private static UUID parseWorldId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("world id must not be blank");
        }
        return UUID.fromString(raw.strip());
    }

    private static String mapFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof OrphanUnknownException) {
            return "orphan.unknown";
        }
        if (current instanceof OrphanConflictException) {
            return "orphan.conflict";
        }
        return "orphan.failed";
    }

    // -----------------------------------------------------------------
    // Seams
    // -----------------------------------------------------------------

    /**
     * The only permission this handler honours. Any backend failure denies;
     * no other node is consulted, so holding ledger, serverland or bypass
     * grants nothing here.
     */
    private static boolean hasOrphanPermission(CommandSender sender) {
        try {
            return sender.hasPermission(LandPermissions.ORPHAN);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    /**
     * Takes the versioned catalog snapshot on the calling thread. Must never
     * be invoked from a persistence-thread callback: the guard snapshot is a
     * memory-only read, but the calling convention keeps every Bukkit-adjacent
     * read on the command thread by construction.
     */
    private WorldCatalogSnapshot captureCatalog() {
        try {
            return guard == null ? null : guard.snapshot();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean isVerified(WorldCatalogSnapshot snapshot) {
        return snapshot != null && snapshot.isVerified();
    }

    private OrphanPurgeRepository readRepos() {
        try {
            return repositories == null ? null : repositories.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private Instant readClock() {
        try {
            return clock == null ? null : clock.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Player playerOf(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    private static UUID actorOf(CommandSender sender) {
        if (sender instanceof Player player) {
            try {
                return player.getUniqueId();
            } catch (RuntimeException unresolved) {
                return null;
            }
        }
        return null;
    }

    /**
     * Confirmation owner key: the player UUID, or the fixed console marker.
     * A {@code null} return means the identity itself is unreadable, which
     * fails the confirmation closed. Console senders share one marker by
     * design: the nonce itself stays the unguessable bound secret.
     */
    private static String actorKeyOf(CommandSender sender) {
        if (sender instanceof Player player) {
            try {
                UUID uuid = player.getUniqueId();
                return uuid == null ? null : uuid.toString();
            } catch (RuntimeException unresolved) {
                return null;
            }
        }
        return "console";
    }

    private static Locale senderLocale(CommandSender sender) {
        if (sender instanceof Player player) {
            try {
                return player.locale();
            } catch (RuntimeException unresolved) {
                return null;
            }
        }
        return null;
    }

    /**
     * Terminal reply: players always hop to their thread first so a
     * persistence-executor thread never touches the sink; console senders
     * reply inline. A retired scheduler drops the reply fail-closed.
     */
    private void replyAsync(Player player, CommandSender sender, ReplySink sink,
            String key, Map<String, Object> vars, Locale locale) {
        if (player == null) {
            try {
                sink.reply(key, vars, locale);
            } catch (RuntimeException ignored) {
                // Terminal reply path: never let a sink failure escape.
            }
            return;
        }
        PlayerScheduler hop = scheduler;
        try {
            hop.runForPlayer(player, () -> {
                try {
                    sink.reply(key, vars, locale);
                } catch (RuntimeException ignored) {
                    // Terminal reply path: never let a sink failure escape.
                }
            });
        } catch (RuntimeException retired) {
            // Scheduling itself failed: drop fail-closed without replying.
        }
    }

}
