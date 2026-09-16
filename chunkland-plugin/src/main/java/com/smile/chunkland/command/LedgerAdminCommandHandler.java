package com.smile.chunkland.command;

import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.LedgerState;
import com.smile.chunkland.persistence.OperationLedger;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Operator ledger administration behind {@code /land admin ledger}.
 *
 * <p>Three read-model verbs and one explicit verdict verb:
 * {@code list [state]} pages the durable rows newest-filtered and bounded,
 * {@code show <operationId>} renders one row, and
 * {@code resolve <operationId> <RESOLVED|REFUNDED|IGNORED>} records an
 * operator decision. A resolve only leaves {@code NEEDS_RECONCILIATION},
 * exactly once, through the typed compare-and-set, followed by one
 * {@code LEDGER_RESOLVE} audit row carrying the actor, the operation, the
 * previous state and the decision. The verdict states are terminal and
 * recovery-inert: startup recovery never writes them, and this handler never
 * calls Economy, replays the domain or parks compensation — {@code REFUNDED}
 * means the operator handled the money outside the ledger.
 *
 * <p>All storage runs on the persistence executor. When the sender is a
 * player the terminal reply hops through the injected {@link PlayerScheduler}
 * to the player thread first, so a persistence thread never touches the
 * {@link ReplySink}; console senders reply inline on the completing thread.
 * Every degraded state fails closed on {@code usage} (malformed input) or
 * {@code failed} (unknown operation, wrong state, lost race, storage or
 * audit outage). List and show payloads are bounded summaries — never the
 * stored payload JSON, economy references or full actor ids.
 */
public final class LedgerAdminCommandHandler implements LandCommand.Handler {

    /** Maximum rows rendered by one {@code list}; newer rows win. */
    static final int LIST_LIMIT = 20;

    /** Hard cap on one rendered line so an odd row cannot flood chat. */
    static final int LINE_VALUE_LIMIT = 220;

    /**
     * Explicit operator verdicts. Each maps one-to-one to the terminal
     * {@link LedgerState} of the same name; there is no silent mapping onto
     * an unrelated state.
     */
    public enum OperatorDecision {
        RESOLVED,
        REFUNDED,
        IGNORED
    }

    private final Supplier<OperationLedger> ledgers;
    private final Supplier<AuditRepository> audits;
    private final Supplier<Instant> clock;
    private final PlayerScheduler scheduler;

    /**
     * @param ledgers durable ledger source; {@code null} or failing reads fail closed
     * @param audits  audit write source for {@code LEDGER_RESOLVE}; resolve fails
     *                closed without it
     * @param clock   time source for transitions and audit rows; {@code null} or
     *                failing reads fail closed
     * @param scheduler player-thread hop for async replies; {@code null} replies
     *                  inline on the completing thread
     */
    public LedgerAdminCommandHandler(Supplier<OperationLedger> ledgers,
            Supplier<AuditRepository> audits,
            Supplier<Instant> clock,
            PlayerScheduler scheduler) {
        this.ledgers = ledgers;
        this.audits = audits;
        this.clock = clock;
        this.scheduler = scheduler == null ? PlayerScheduler.direct() : scheduler;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (args == null || args.length < 3
                || !"admin".equalsIgnoreCase(args[0])
                || !"ledger".equalsIgnoreCase(args[1])) {
            sink.reply("command.land.admin.ledger.usage", Map.of());
            return;
        }
        String verb = args[2] == null ? "" : args[2].strip().toLowerCase(Locale.ROOT);
        switch (verb) {
            case "list" -> handleList(sender, args, sink);
            case "show" -> handleShow(sender, args, sink);
            case "resolve" -> handleResolve(sender, args, sink);
            default -> sink.reply("command.land.admin.ledger.usage", Map.of());
        }
    }

    // -----------------------------------------------------------------
    // list
    // -----------------------------------------------------------------

    private void handleList(CommandSender sender, String[] args, ReplySink sink) {
        if (args.length > 4) {
            sink.reply("command.land.admin.ledger.usage", Map.of());
            return;
        }
        final LedgerState filter;
        if (args.length == 4) {
            String raw = args[3] == null ? "" : args[3].strip();
            Optional<LedgerState> parsed = LedgerState.tryParse(raw);
            if (parsed.isEmpty()) {
                sink.reply("command.land.admin.ledger.usage", Map.of());
                return;
            }
            filter = parsed.get();
        } else {
            filter = null;
        }
        OperationLedger ledger = readLedger();
        if (ledger == null) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.unavailable"));
            return;
        }
        CompletionStage<List<LedgerEntry>> stage;
        try {
            stage = filter == null ? ledger.findAll() : ledger.findByState(filter);
        } catch (RuntimeException failure) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.failed"));
            return;
        }
        Player player = playerOf(sender);
        Locale locale = senderLocale(sender);
        stage.whenComplete((rows, failure) -> {
            try {
                if (failure != null || rows == null) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.failed"), locale);
                } else if (rows.isEmpty()) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.empty",
                            Map.of(), locale);
                } else {
                    int shown = Math.min(rows.size(), LIST_LIMIT);
                    for (int i = 0; i < shown; i++) {
                        LedgerEntry row = rows.get(i);
                        if (row == null) {
                            continue;
                        }
                        replyAsync(player, sender, sink, "command.land.admin.ledger.line",
                                Map.of("value", summarize(row)), locale);
                    }
                    if (rows.size() > shown) {
                        replyAsync(player, sender, sink, "command.land.admin.ledger.line",
                                Map.of("value", "and " + (rows.size() - shown)
                                        + " more; refine the state filter"),
                                locale);
                    }
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    // -----------------------------------------------------------------
    // show
    // -----------------------------------------------------------------

    private void handleShow(CommandSender sender, String[] args, ReplySink sink) {
        if (args.length != 4) {
            sink.reply("command.land.admin.ledger.usage", Map.of());
            return;
        }
        final UUID operationId;
        try {
            operationId = parseOperationId(args[3]);
        } catch (IllegalArgumentException malformed) {
            sink.reply("command.land.admin.ledger.usage", Map.of());
            return;
        }
        OperationLedger ledger = readLedger();
        if (ledger == null) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.unavailable"));
            return;
        }
        CompletionStage<Optional<LedgerEntry>> stage;
        try {
            stage = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.failed"));
            return;
        }
        Player player = playerOf(sender);
        Locale locale = senderLocale(sender);
        stage.whenComplete((found, failure) -> {
            try {
                if (failure != null) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.failed"), locale);
                } else if (found == null || found.isEmpty() || found.get() == null) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.unknown"), locale);
                } else {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.line",
                            Map.of("value", describe(found.get())), locale);
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    // -----------------------------------------------------------------
    // resolve
    // -----------------------------------------------------------------

    private void handleResolve(CommandSender sender, String[] args, ReplySink sink) {
        if (args.length != 5) {
            sink.reply("command.land.admin.ledger.usage", Map.of());
            return;
        }
        final UUID operationId;
        try {
            operationId = parseOperationId(args[3]);
        } catch (IllegalArgumentException malformed) {
            sink.reply("command.land.admin.ledger.usage", Map.of());
            return;
        }
        final OperatorDecision decision;
        try {
            decision = parseDecision(args[4]);
        } catch (IllegalArgumentException malformed) {
            sink.reply("command.land.admin.ledger.usage", Map.of());
            return;
        }
        OperationLedger ledger = readLedger();
        AuditRepository auditRepo = readAudits();
        Instant now = readClock();
        if (ledger == null || auditRepo == null || now == null) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.unavailable"));
            return;
        }
        UUID actor = actorOf(sender);
        Player player = playerOf(sender);
        Locale locale = senderLocale(sender);
        CompletionStage<Optional<LedgerEntry>> read;
        try {
            read = ledger.findById(operationId);
        } catch (RuntimeException failure) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.failed"));
            return;
        }
        if (read == null) {
            sink.reply("command.land.admin.ledger.failed", Map.of("reason", "ledger.failed"));
            return;
        }
        read.whenComplete((found, failure) -> {
            try {
                if (failure != null || found == null || found.isEmpty() || found.get() == null) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.unknown"), locale);
                    return;
                }
                LedgerEntry current = found.get();
                LedgerState currentState;
                try {
                    currentState = current.typedState();
                } catch (IllegalArgumentException malformed) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.state"), locale);
                    return;
                }
                if (currentState != LedgerState.NEEDS_RECONCILIATION) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.state"), locale);
                    return;
                }
                LedgerState target = targetStateFor(decision);
                CompletionStage<Void> guarded;
                try {
                    guarded = ledger.compareAndSetState(operationId,
                            LedgerState.NEEDS_RECONCILIATION, target, now);
                } catch (RuntimeException rejected) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.state"), locale);
                    return;
                }
                if (guarded == null) {
                    replyAsync(player, sender, sink, "command.land.admin.ledger.failed",
                            Map.of("reason", "ledger.failed"), locale);
                    return;
                }
                guarded.whenComplete((done, casFailure) -> {
                    try {
                        if (casFailure != null) {
                            // Lost the race or the row moved: exactly one
                            // operator decision wins per row.
                            replyAsync(player, sender, sink,
                                    "command.land.admin.ledger.failed",
                                    Map.of("reason", "ledger.conflict"), locale);
                            return;
                        }
                        AuditEntry audit = resolveAudit(actor, current, currentState, decision, now);
                        CompletionStage<Long> written;
                        try {
                            written = auditRepo.insert(audit);
                        } catch (RuntimeException auditFailure) {
                            replyAsync(player, sender, sink,
                                    "command.land.admin.ledger.failed",
                                    Map.of("reason", "ledger.audit_failed"), locale);
                            return;
                        }
                        if (written == null) {
                            replyAsync(player, sender, sink,
                                    "command.land.admin.ledger.failed",
                                    Map.of("reason", "ledger.audit_failed"), locale);
                            return;
                        }
                        written.whenComplete((id, auditFailure) -> {
                            try {
                                if (auditFailure != null) {
                                    replyAsync(player, sender, sink,
                                            "command.land.admin.ledger.failed",
                                            Map.of("reason", "ledger.audit_failed"), locale);
                                } else {
                                    replyAsync(player, sender, sink,
                                            "command.land.admin.ledger.resolved",
                                            Map.of("value", operationId + " " + decision
                                                    + " -> " + target),
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
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    // -----------------------------------------------------------------
    // Rendering (bounded, no payload or economy internals)
    // -----------------------------------------------------------------

    /** One bounded list line: type, short id, state and retry count. */
    static String summarize(LedgerEntry entry) {
        String shortId = entry.operationId().toString().substring(0, 8);
        return bound("type=" + safe(entry.operationType()) + " op=" + shortId
                + " state=" + safe(entry.state()) + " attempts=" + entry.compensationAttempts());
    }

    /** Full single-row rendering for {@code show}; still bounded. */
    static String describe(LedgerEntry entry) {
        return bound("op=" + entry.operationId() + " type=" + safe(entry.operationType())
                + " state=" + safe(entry.state()) + " attempts=" + entry.compensationAttempts()
                + " updated=" + entry.updatedAt().toEpochMilli());
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String stripped = value.strip().replaceAll("\\s+", "_");
        return stripped.length() <= 48 ? stripped : stripped.substring(0, 48);
    }

    private static String bound(String value) {
        if (value.length() <= LINE_VALUE_LIMIT) {
            return value;
        }
        return value.substring(0, LINE_VALUE_LIMIT);
    }

    // -----------------------------------------------------------------
    // Parsing and mapping
    // -----------------------------------------------------------------

    private static UUID parseOperationId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("operation id must not be blank");
        }
        return UUID.fromString(raw.strip());
    }

    /**
     * Exact uppercase decision spelling; anything else (including lowercase
     * or near-miss names) is rejected so an operator typo can never resolve
     * a row by accident.
     */
    static OperatorDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("decision must not be blank");
        }
        try {
            return OperatorDecision.valueOf(raw.strip());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown operator decision: " + raw, unknown);
        }
    }

    /** One-to-one verdict mapping; resolve never invents a new target. */
    static LedgerState targetStateFor(OperatorDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return switch (decision) {
            case RESOLVED -> LedgerState.RESOLVED;
            case REFUNDED -> LedgerState.REFUNDED;
            case IGNORED -> LedgerState.IGNORED;
        };
    }

    /**
     * The durable {@code LEDGER_RESOLVE} trail: actor, operation, previous
     * state and operator decision travel in the metadata JSON, the state
     * edge in before/after. No payload, no economy reference.
     */
    static AuditEntry resolveAudit(UUID actor, LedgerEntry current, LedgerState oldState,
            OperatorDecision decision, Instant now) {
        String metadata = "{\"operationId\":\"" + current.operationId()
                + "\",\"oldState\":\"" + oldState.name()
                + "\",\"decision\":\"" + decision.name() + "\"}";
        return new AuditEntry(0L, now, actor, "LEDGER_RESOLVE",
                current.targetLandId(), current.worldId(), null, 1,
                oldState.name(), targetStateFor(decision).name(), metadata, List.of());
    }

    // -----------------------------------------------------------------
    // Seams
    // -----------------------------------------------------------------

    private OperationLedger readLedger() {
        try {
            return ledgers == null ? null : ledgers.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private AuditRepository readAudits() {
        try {
            return audits == null ? null : audits.get();
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
