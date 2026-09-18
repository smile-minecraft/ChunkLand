package com.smile.chunkland.command;

import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Player-only {@code /land bypass on|off} toggle behind the independent
 * {@code chunkland.admin.bypass} node.
 *
 * <p>The node only allows <em>attempting</em> the switch: holding it never
 * authorises a management mutation on its own. Every {@code on} or
 * {@code off} attempt appends one attempt row carrying the actor, the
 * requested edge and an outcome marker, flips the actor-scoped in-memory
 * state, then appends one committed terminal with the same attempt id and
 * the actual edge. The {@code after} edge of an attempt row is the
 * requested state, never proof of success: only a committed terminal
 * counts as success, and an attempt alone is never success. When the
 * commit fails after a successful attempt write, or a committed terminal
 * cannot be made durable, one abort row with the same attempt id and the
 * actual {@code false} edge is appended instead, so no unmarked
 * {@code after=true} row can be read as a successful switch. An audit
 * failure, a full bypass memory, a console sender, a missing node or
 * malformed input changes no state.
 *
 * <p>Toggles for one actor complete strictly in enqueue order without ever
 * blocking a thread: each attempt chains onto that actor's tail future, and
 * the {@code before} edge plus the capacity check are captured when the
 * attempt actually executes — after every earlier attempt's audit and state
 * flip settled, including any abort compensation. A failed audit changes
 * no state and never stalls the same actor's queue. Different actors chain
 * on independent tails and never wait for each other. When the owning
 * enable generation shuts down, every late callback stops before any audit
 * insert, terminal write or state mutation, so old rows can never shift a
 * new recovery scan.
 */
public final class AdminBypassCommandHandler implements LandCommand.Handler {

    /** Audit action recorded before any state flip. */
    public static final String TOGGLE_AUDIT_ACTION = "ADMIN_BYPASS_TOGGLE";

    /** Outcome marker for the first row of one {@code on} or {@code off} attempt. */
    public static final String OUTCOME_ATTEMPT = "attempt";

    /** Outcome marker for the durable success terminal of one attempt. */
    public static final String OUTCOME_COMMITTED = "committed";

    /** Outcome marker for the compensating row of an aborted {@code on} attempt. */
    public static final String OUTCOME_ABORTED = "aborted";

    private final Supplier<AuditRepository> audits;
    private final Supplier<Instant> clock;
    private final AdminBypassState states;
    private final PlayerScheduler scheduler;
    /**
     * Unique id of the enable generation that owns this handler. Every
     * attempt and terminal row carries it, so a startup recovery can tell
     * previous-generation opens apart from current-generation writes.
     */
    private final UUID processGeneration;
    /**
     * Startup recovery gate. While incomplete or failed, every toggle
     * fail-closes without audit or state change; once completed normally
     * toggles run. Null means no gate (open).
     */
    private final CompletableFuture<Void> recoveryGate;
    /**
     * Shared close token of the owning enable generation. While closed,
     * every late callback stops before any audit insert, terminal write
     * or state mutation. Null means no gate (open).
     */
    private final AdminBypassLifecycle lifecycle;
    /**
     * Per-actor completion tails. Each toggle chains onto its actor's tail
     * so one actor's audit-then-state transitions serialize in enqueue
     * order; entries are removed as their tail settles, so the map only
     * holds actors with a toggle in flight.
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    /**
     * @param audits audit write source; {@code null} or failing writes fail
     *               the toggle closed without touching the state
     * @param clock time source for audit rows; {@code null} or failing reads
     *              fail the toggle closed
     * @param states per-enable bypass memory; {@code null} fails every call
     *               closed without side effects
     * @param scheduler player-thread hop for async replies; {@code null}
     *                  replies inline on the completing thread
     */
    public AdminBypassCommandHandler(Supplier<AuditRepository> audits,
            Supplier<Instant> clock,
            AdminBypassState states,
            PlayerScheduler scheduler) {
        this(audits, clock, states, scheduler, UUID.randomUUID(), new CompletableFuture<>());
        this.recoveryGate.complete(null);
    }

    /**
     * @param processGeneration unique id of the owning enable generation;
     *               every attempt and terminal row carries it
     * @param recoveryGate startup recovery stage; while incomplete or
     *               failed every toggle fail-closes, null means open
     */
    public AdminBypassCommandHandler(Supplier<AuditRepository> audits,
            Supplier<Instant> clock,
            AdminBypassState states,
            PlayerScheduler scheduler,
            UUID processGeneration,
            CompletableFuture<Void> recoveryGate) {
        this(audits, clock, states, scheduler, processGeneration, recoveryGate, null);
    }

    /**
     * @param lifecycle shared close token of the owning enable generation;
     *               while closed every toggle and late callback fail-closes
     *               before any audit or state mutation, null means open
     */
    public AdminBypassCommandHandler(Supplier<AuditRepository> audits,
            Supplier<Instant> clock,
            AdminBypassState states,
            PlayerScheduler scheduler,
            UUID processGeneration,
            CompletableFuture<Void> recoveryGate,
            AdminBypassLifecycle lifecycle) {
        this.audits = audits;
        this.clock = clock;
        this.states = states;
        this.scheduler = scheduler == null ? PlayerScheduler.direct() : scheduler;
        this.processGeneration = Objects.requireNonNull(processGeneration, "processGeneration");
        this.recoveryGate = recoveryGate;
        this.lifecycle = lifecycle;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.bypass.console", Map.of());
            return;
        }
        UUID actor = actorOf(player);
        if (actor == null) {
            sink.reply("command.land.bypass.console", Map.of());
            return;
        }
        if (!hasToggleAuthority(sender)) {
            sink.reply("command.land.denied", Map.of("permission", LandPermissions.BYPASS));
            return;
        }
        Boolean target = parseTarget(args);
        if (target == null) {
            sink.reply("command.land.bypass.usage", Map.of());
            return;
        }
        if (states == null) {
            sink.reply("command.land.bypass.failed", Map.of("reason", "bypass.unavailable"));
            return;
        }
        if (!isRecoveryOpen()) {
            sink.reply("command.land.bypass.failed", Map.of("reason", "bypass.unavailable"));
            return;
        }
        if (isClosed()) {
            sink.reply("command.land.bypass.failed", Map.of("reason", "bypass.unavailable"));
            return;
        }
        boolean enable = target;
        Locale locale = senderLocale(player);
        CompletableFuture<Void> next;
        try {
            next = tails.compute(actor, (key, prev) -> {
                CompletableFuture<Void> gate = prev == null
                        ? CompletableFuture.completedFuture(null)
                        : prev.handle((ignored, failure) -> null);
                return gate.thenCompose(ignored -> executeToggle(actor, player, sink, enable, locale));
            });
        } catch (RuntimeException enqueueFailure) {
            sink.reply("command.land.bypass.failed", Map.of("reason", "bypass.failed"));
            return;
        }
        if (next == null) {
            sink.reply("command.land.bypass.failed", Map.of("reason", "bypass.failed"));
            return;
        }
        next.whenComplete((ignored, failure) -> tails.remove(actor, next));
    }

    /**
     * Runs one toggle when its turn in the actor's queue arrives: admits an
     * {@code on} against the bound with one atomic token binding the actor
     * to the actual generation (a refused admission fails closed without
     * any audit), appends one attempt row, and only then commits the token.
     * The token is released on every non-commit path, and the commit itself
     * strictly requires the matching pending token plus the generation, so
     * an audit failure, a cancelled/null/throwing audit source, or a
     * disable racing the in-flight audit can never re-enable bypass. When
     * the commit fails after a successful attempt write, one abort row with
     * the same attempt id and the actual {@code false} edge is appended, so
     * the attempt's {@code after=true} edge can never be read as success.
     * The {@code before} edge comes from the same atomic admission, never
     * from a separate read. A toggle that only starts after the owning
     * generation shut down fail-closes immediately: no admission, no audit,
     * no state change. Every path replies exactly once and returns a
     * normally-completing stage, so a failure here changes no state and
     * never stalls the actor's queue. Never blocks.
     */
    private CompletableFuture<Void> executeToggle(UUID actor, Player player,
            ReplySink sink, boolean enable, Locale locale) {
        if (isClosed()) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        }
        if (!enable) {
            boolean before;
            try {
                before = initiate(() -> states.isOn(actor));
            } catch (AdminBypassLifecycle.Closed closed) {
                replyAsync(player, sink, "command.land.bypass.failed",
                        Map.of("reason", "bypass.unavailable"), locale);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException unresolved) {
                replyAsync(player, sink, "command.land.bypass.failed",
                        Map.of("reason", "bypass.failed"), locale);
                return CompletableFuture.completedFuture(null);
            }
            return executeOff(actor, player, sink, before, locale);
        }
        AdminBypassState.Admission admission;
        try {
            admission = initiate(() -> states.admit(actor));
        } catch (AdminBypassLifecycle.Closed closed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException unresolved) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        if (admission.outcome() == AdminBypassState.ReserveOutcome.REFUSED) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        }
        boolean before = admission.outcome() == AdminBypassState.ReserveOutcome.ALREADY_ON;
        AdminBypassState.Reservation token = admission.reservation();
        long claimEpoch = admission.epoch();
        final UUID attemptId;
        try {
            attemptId = UUID.randomUUID();
        } catch (RuntimeException malformed) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        AuditRepository auditRepo;
        Instant now;
        try {
            auditRepo = initiate(this::readAudits);
            now = initiate(this::readClock);
        } catch (AdminBypassLifecycle.Closed closed) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        }
        if (auditRepo == null || now == null) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        }
        final AuditEntry audit;
        try {
            audit = attemptAudit(actor, before, now, attemptId, claimEpoch, processGeneration);
        } catch (RuntimeException malformed) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        final CompletionStage<Long> written;
        try {
            written = initiate(() -> auditRepo.insert(audit));
        } catch (AdminBypassLifecycle.Closed closed) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException auditFailure) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        if (written == null) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> settled = new CompletableFuture<>();
        try {
            written.whenComplete((id, auditFailure) -> {
                try {
                    if (isClosed()) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.unavailable"), locale);
                        settled.complete(null);
                        return;
                    }
                    if (auditFailure != null) {
                        releaseQuietly(token);
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.audit_failed"), locale);
                        settled.complete(null);
                        return;
                    }
                    boolean committed;
                    try {
                        committed = initiate(() -> token != null
                                ? states.commit(token)
                                : states.confirmStillOn(actor, claimEpoch));
                    } catch (AdminBypassLifecycle.Closed closed) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.unavailable"), locale);
                        settled.complete(null);
                        return;
                    } catch (RuntimeException stateFailure) {
                        releaseQuietly(token);
                        compensateAbort(actor, player, sink, locale, auditRepo, now,
                                attemptId, claimEpoch, settled, "bypass.failed");
                        return;
                    }
                    if (!committed) {
                        compensateAbort(actor, player, sink, locale, auditRepo, now,
                                attemptId, claimEpoch, settled, "bypass.unavailable");
                        return;
                    }
                    writeOnCommittedTerminal(actor, player, sink, locale, auditRepo, now,
                            before, attemptId, claimEpoch, settled);
                } catch (RuntimeException terminal) {
                    // Terminal reply path: never let a sink failure escape or
                    // stall the actor's queue.
                    try {
                        settled.complete(null);
                    } catch (RuntimeException ignored) {
                    }
                }
            });
        } catch (RuntimeException auditFailure) {
            releaseQuietly(token);
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
        }
        return settled;
    }

    /**
     * Appends the abort compensation for an {@code on} attempt whose attempt
     * row succeeded but whose commit failed. The abort row carries the same
     * attempt id and the actual {@code false} edge, so the attempt's
     * {@code after=true} edge can never be read as success. A successful
     * compensation replies with the original reason; a failed compensation
     * replies {@code bypass.audit_failed} so the pending attempt stays
     * traceable via its attempt id. Never blocks; the actor's queue
     * advances only after the abort settles.
     */
    private void compensateAbort(UUID actor, Player player, ReplySink sink, Locale locale,
            AuditRepository auditRepo, Instant now, UUID attemptId, long generation,
            CompletableFuture<Void> settled, String reason) {
        if (isClosed()) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            settled.complete(null);
            return;
        }
        boolean actualBefore;
        try {
            actualBefore = initiate(() -> states.isOn(actor));
        } catch (AdminBypassLifecycle.Closed closed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            settled.complete(null);
            return;
        } catch (RuntimeException unresolved) {
            actualBefore = false;
        }
        final AuditEntry abort;
        try {
            abort = abortAudit(actor, actualBefore, now, attemptId, generation, processGeneration);
        } catch (RuntimeException malformed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
            return;
        }
        final CompletionStage<Long> abortWritten;
        try {
            abortWritten = initiate(() -> auditRepo.insert(abort));
        } catch (AdminBypassLifecycle.Closed closed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            settled.complete(null);
            return;
        } catch (RuntimeException auditFailure) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
            return;
        }
        if (abortWritten == null) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
            return;
        }
        try {
            abortWritten.whenComplete((abortId, abortFailure) -> {
                try {
                    if (abortFailure != null) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.audit_failed"), locale);
                        return;
                    }
                    replyAsync(player, sink, "command.land.bypass.failed",
                            Map.of("reason", reason), locale);
                } catch (RuntimeException terminal) {
                    // Terminal reply path: never let a sink failure escape or
                    // stall the actor's queue.
                } finally {
                    settled.complete(null);
                }
            });
        } catch (RuntimeException auditFailure) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
        }
    }

    /**
     * Appends the durable success terminal after an {@code on} commit. The
     * terminal shares the attempt id and carries the actual edge. If the
     * terminal write fails, the state is immediately fail-closed back off
     * and one abort row for the same attempt id is appended, so the attempt
     * alone can never be read as success; a doubly-failed compensation
     * stays traceable via its attempt id for the next recovery. Never
     * blocks; the actor's queue advances only after the terminal settles.
     */
    private void writeOnCommittedTerminal(UUID actor, Player player, ReplySink sink, Locale locale,
            AuditRepository auditRepo, Instant now, boolean before, UUID attemptId, long generation,
            CompletableFuture<Void> settled) {
        if (isClosed()) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            settled.complete(null);
            return;
        }
        final AuditEntry committed;
        try {
            committed = committedAudit(actor, before, true, now, attemptId, generation, true, processGeneration);
        } catch (RuntimeException malformed) {
            failClosedAfterTerminalFailure(actor, player, sink, locale, auditRepo, now,
                    attemptId, generation, settled);
            return;
        }
        final CompletionStage<Long> committedWritten;
        try {
            committedWritten = initiate(() -> auditRepo.insert(committed));
        } catch (AdminBypassLifecycle.Closed closed) {
            failClosedAfterTerminalFailure(actor, player, sink, locale, auditRepo, now,
                    attemptId, generation, settled);
            return;
        } catch (RuntimeException auditFailure) {
            failClosedAfterTerminalFailure(actor, player, sink, locale, auditRepo, now,
                    attemptId, generation, settled);
            return;
        }
        if (committedWritten == null) {
            failClosedAfterTerminalFailure(actor, player, sink, locale, auditRepo, now,
                    attemptId, generation, settled);
            return;
        }
        try {
            committedWritten.whenComplete((committedId, committedFailure) -> {
                try {
                    if (isClosed()) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.unavailable"), locale);
                        settled.complete(null);
                        return;
                    }
                    if (committedFailure != null) {
                        failClosedAfterTerminalFailure(actor, player, sink, locale, auditRepo, now,
                                attemptId, generation, settled);
                        return;
                    }
                    replyAsync(player, sink, "command.land.bypass.on", Map.of(), locale);
                    settled.complete(null);
                } catch (RuntimeException terminal) {
                    try {
                        settled.complete(null);
                    } catch (RuntimeException ignored) {
                    }
                }
            });
        } catch (RuntimeException auditFailure) {
            failClosedAfterTerminalFailure(actor, player, sink, locale, auditRepo, now,
                    attemptId, generation, settled);
        }
    }

    /**
     * Fail-closed path for a committed terminal that could not be made
     * durable: turns the state back off, then appends one abort row for the
     * same attempt id. Any failure inside stays as {@code audit_failed} so
     * the open attempt remains retryable by the next recovery.
     */
    private void failClosedAfterTerminalFailure(UUID actor, Player player, ReplySink sink, Locale locale,
            AuditRepository auditRepo, Instant now, UUID attemptId, long generation,
            CompletableFuture<Void> settled) {
        if (!isClosed()) {
            try {
                initiate(() -> {
                    states.setEnabled(actor, false);
                    return null;
                });
            } catch (AdminBypassLifecycle.Closed closed) {
                // Quiesced mid-path: the old memory is already gone.
            } catch (RuntimeException ignored) {
            }
        }
        compensateAbort(actor, player, sink, locale, auditRepo, now,
                attemptId, generation, settled, "bypass.audit_failed");
    }

    /**
     * Runs one {@code off} when its turn arrives: appends one attempt row,
     * flips the state off idempotently, then appends one committed terminal
     * with the same attempt id. Disabling needs no capacity admission and
     * can never be blocked by enable reservations or a full memory; the
     * removal itself is idempotent, so settling after a disable stays a
     * no-op. A missing committed terminal stays open for the next recovery
     * and is never read as success alone. Audit failure still changes
     * nothing except the already-durable attempt row.
     */
    private CompletableFuture<Void> executeOff(UUID actor, Player player,
            ReplySink sink, boolean before, Locale locale) {
        final UUID attemptId;
        try {
            attemptId = UUID.randomUUID();
        } catch (RuntimeException malformed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        AuditRepository auditRepo;
        Instant now;
        try {
            auditRepo = initiate(this::readAudits);
            now = initiate(this::readClock);
        } catch (AdminBypassLifecycle.Closed closed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        }
        if (auditRepo == null || now == null) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        }
        final AuditEntry audit;
        try {
            audit = offAttemptAudit(actor, before, now, attemptId, processGeneration);
        } catch (RuntimeException malformed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        final CompletionStage<Long> written;
        try {
            written = initiate(() -> auditRepo.insert(audit));
        } catch (AdminBypassLifecycle.Closed closed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException auditFailure) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        if (written == null) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> settled = new CompletableFuture<>();
        try {
            written.whenComplete((id, auditFailure) -> {
                try {
                    if (isClosed()) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.unavailable"), locale);
                        settled.complete(null);
                        return;
                    }
                    if (auditFailure != null) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.audit_failed"), locale);
                        settled.complete(null);
                        return;
                    }
                    try {
                        initiate(() -> {
                            states.setEnabled(actor, false);
                            return null;
                        });
                    } catch (AdminBypassLifecycle.Closed closed) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.unavailable"), locale);
                        settled.complete(null);
                        return;
                    } catch (RuntimeException stateFailure) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.failed"), locale);
                        settled.complete(null);
                        return;
                    }
                    writeOffCommittedTerminal(actor, player, sink, locale, auditRepo, now,
                            before, attemptId, settled);
                } catch (RuntimeException terminal) {
                    // Terminal reply path: never let a sink failure escape or
                    // stall the actor's queue.
                    try {
                        settled.complete(null);
                    } catch (RuntimeException ignored) {
                    }
                }
            });
        } catch (RuntimeException auditFailure) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
        }
        return settled;
    }

    /**
     * Appends the durable success terminal after an {@code off} flip. The
     * state is already fail-closed off, so a terminal write failure only
     * replies {@code audit_failed} and leaves the attempt open for the next
     * recovery. Never blocks; the actor's queue advances only after the
     * terminal settles.
     */
    private void writeOffCommittedTerminal(UUID actor, Player player, ReplySink sink, Locale locale,
            AuditRepository auditRepo, Instant now, boolean before, UUID attemptId,
            CompletableFuture<Void> settled) {
        if (isClosed()) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            settled.complete(null);
            return;
        }
        final AuditEntry committed;
        try {
            committed = offCommittedAudit(actor, before, now, attemptId, processGeneration);
        } catch (RuntimeException malformed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
            return;
        }
        final CompletionStage<Long> committedWritten;
        try {
            committedWritten = initiate(() -> auditRepo.insert(committed));
        } catch (AdminBypassLifecycle.Closed closed) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.unavailable"), locale);
            settled.complete(null);
            return;
        } catch (RuntimeException auditFailure) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
            return;
        }
        if (committedWritten == null) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
            return;
        }
        try {
            committedWritten.whenComplete((committedId, committedFailure) -> {
                try {
                    if (isClosed()) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.unavailable"), locale);
                        return;
                    }
                    if (committedFailure != null) {
                        replyAsync(player, sink, "command.land.bypass.failed",
                                Map.of("reason", "bypass.audit_failed"), locale);
                        return;
                    }
                    replyAsync(player, sink, "command.land.bypass.off", Map.of(), locale);
                } catch (RuntimeException terminal) {
                    // Terminal reply path: never let a sink failure escape or
                    // stall the actor's queue.
                } finally {
                    settled.complete(null);
                }
            });
        } catch (RuntimeException auditFailure) {
            replyAsync(player, sink, "command.land.bypass.failed",
                    Map.of("reason", "bypass.audit_failed"), locale);
            settled.complete(null);
        }
    }

    private void releaseQuietly(AdminBypassState.Reservation token) {
        if (token == null) {
            return;
        }
        try {
            initiate(() -> {
                states.release(token);
                return null;
            });
        } catch (AdminBypassLifecycle.Closed closed) {
            // Quiesced mid-path: the old memory is already gone.
        } catch (RuntimeException ignored) {
            // Reservation accounting is best-effort cleanup; the commit-time
            // size check remains the hard bound.
        }
    }

    /**
     * The durable toggle trail: actor, before/after state in the edge
     * columns, request detail in metadata. No land, no world, no chunks.
     */
    static AuditEntry toggleAudit(UUID actor, boolean before, boolean after, Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        String metadata = "{\"reason\":\"command\",\"requested\":\"" + (after ? "on" : "off") + "\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(after), metadata, List.of());
    }

    /**
     * The attempt row of one {@code on} toggle: the {@code after} edge is
     * the requested state, never proof of success. Actual success also
     * flips the state and leaves no abort row for the same attempt id;
     * an aborted attempt always leaves one abort row with the same id and
     * the actual {@code false} edge. Readers must never read
     * {@code after=true} alone as success.
     */
    static AuditEntry attemptAudit(UUID actor, boolean before, Instant now,
            UUID attemptId, long generation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        String metadata = "{\"reason\":\"command\",\"requested\":\"on\","
                + "\"outcome\":\"" + OUTCOME_ATTEMPT + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"generation\":" + generation + "}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(true), metadata, List.of());
    }

    /** Attempt row carrying its owning enable process, for current writes. */
    static AuditEntry attemptAudit(UUID actor, boolean before, Instant now,
            UUID attemptId, long generation, UUID process) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(process, "process");
        String metadata = "{\"reason\":\"command\",\"requested\":\"on\","
                + "\"outcome\":\"" + OUTCOME_ATTEMPT + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"generation\":" + generation + ","
                + "\"process\":\"" + process + "\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(true), metadata, List.of());
    }

    /**
     * The compensating row of an aborted {@code on} attempt: same attempt
     * id and generation as its attempt row, with the actual {@code false}
     * edge. Written only after the attempt row succeeded but the commit
     * failed, so the attempt's {@code after=true} edge can never be read
     * as a successful switch.
     */
    static AuditEntry abortAudit(UUID actor, boolean actualBefore, Instant now,
            UUID attemptId, long generation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        String metadata = "{\"reason\":\"command\",\"requested\":\"on\","
                + "\"outcome\":\"" + OUTCOME_ABORTED + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"generation\":" + generation + ","
                + "\"abortReason\":\"unavailable\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(actualBefore), Boolean.toString(false), metadata, List.of());
    }

    /** Abort row carrying its owning enable process, for current writes. */
    static AuditEntry abortAudit(UUID actor, boolean actualBefore, Instant now,
            UUID attemptId, long generation, UUID process) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(process, "process");
        String metadata = "{\"reason\":\"command\",\"requested\":\"on\","
                + "\"outcome\":\"" + OUTCOME_ABORTED + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"generation\":" + generation + ","
                + "\"process\":\"" + process + "\","
                + "\"abortReason\":\"unavailable\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(actualBefore), Boolean.toString(false), metadata, List.of());
    }

    /**
     * The durable success terminal of one attempt: same attempt id and
     * generation as its attempt row, with the actual before/after edge.
     * Written only after the state flip succeeded; an attempt row alone,
     * with or without a later {@code off}, is never success.
     */
    static AuditEntry committedAudit(UUID actor, boolean before, boolean after, Instant now,
            UUID attemptId, long generation, boolean requested) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        String metadata = "{\"reason\":\"command\",\"requested\":\"" + (requested ? "on" : "off") + "\","
                + "\"outcome\":\"" + OUTCOME_COMMITTED + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"generation\":" + generation + "}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(after), metadata, List.of());
    }

    /** Committed terminal carrying its owning enable process, for current writes. */
    static AuditEntry committedAudit(UUID actor, boolean before, boolean after, Instant now,
            UUID attemptId, long generation, boolean requested, UUID process) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(process, "process");
        String metadata = "{\"reason\":\"command\",\"requested\":\"" + (requested ? "on" : "off") + "\","
                + "\"outcome\":\"" + OUTCOME_COMMITTED + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"generation\":" + generation + ","
                + "\"process\":\"" + process + "\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(after), metadata, List.of());
    }

    /**
     * The attempt row of one {@code off} toggle: requested off, never proof
     * of success until its committed terminal with the same attempt id.
     */
    static AuditEntry offAttemptAudit(UUID actor, boolean before, Instant now, UUID attemptId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        String metadata = "{\"reason\":\"command\",\"requested\":\"off\","
                + "\"outcome\":\"" + OUTCOME_ATTEMPT + "\","
                + "\"attempt\":\"" + attemptId + "\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(false), metadata, List.of());
    }

    /** Off attempt row carrying its owning enable process, for current writes. */
    static AuditEntry offAttemptAudit(UUID actor, boolean before, Instant now, UUID attemptId, UUID process) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(process, "process");
        String metadata = "{\"reason\":\"command\",\"requested\":\"off\","
                + "\"outcome\":\"" + OUTCOME_ATTEMPT + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"process\":\"" + process + "\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(false), metadata, List.of());
    }

    /**
     * The durable success terminal of one {@code off} attempt: same attempt
     * id as its attempt row, with the actual edge ending off.
     */
    static AuditEntry offCommittedAudit(UUID actor, boolean before, Instant now, UUID attemptId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        String metadata = "{\"reason\":\"command\",\"requested\":\"off\","
                + "\"outcome\":\"" + OUTCOME_COMMITTED + "\","
                + "\"attempt\":\"" + attemptId + "\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(false), metadata, List.of());
    }

    /** Off committed terminal carrying its owning enable process, for current writes. */
    static AuditEntry offCommittedAudit(UUID actor, boolean before, Instant now, UUID attemptId, UUID process) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(process, "process");
        String metadata = "{\"reason\":\"command\",\"requested\":\"off\","
                + "\"outcome\":\"" + OUTCOME_COMMITTED + "\","
                + "\"attempt\":\"" + attemptId + "\","
                + "\"process\":\"" + process + "\"}";
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(before), Boolean.toString(false), metadata, List.of());
    }

    /**
     * The recovery abort for an open attempt without a terminal: same
     * attempt id as its attempt row, actual edge ending off. Used both for
     * {@code on} opens (generation from the attempt row when present) and
     * {@code off} opens.
     */
    static AuditEntry recoveryAbortAudit(UUID actor, Instant now, UUID attemptId, Long generation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        String metadata;
        if (generation == null) {
            metadata = "{\"reason\":\"command\",\"requested\":\"off\","
                    + "\"outcome\":\"" + OUTCOME_ABORTED + "\","
                    + "\"attempt\":\"" + attemptId + "\","
                    + "\"abortReason\":\"recovery\"}";
        } else {
            metadata = "{\"reason\":\"command\",\"requested\":\"on\","
                    + "\"outcome\":\"" + OUTCOME_ABORTED + "\","
                    + "\"attempt\":\"" + attemptId + "\","
                    + "\"generation\":" + generation + ","
                    + "\"abortReason\":\"recovery\"}";
        }
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(false), Boolean.toString(false), metadata, List.of());
    }

    /** Recovery abort carrying the recovering enable process, for current recoveries. */
    static AuditEntry recoveryAbortAudit(UUID actor, Instant now, UUID attemptId, Long generation,
            UUID process) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(process, "process");
        String metadata;
        if (generation == null) {
            metadata = "{\"reason\":\"command\",\"requested\":\"off\","
                    + "\"outcome\":\"" + OUTCOME_ABORTED + "\","
                    + "\"attempt\":\"" + attemptId + "\","
                    + "\"process\":\"" + process + "\","
                    + "\"abortReason\":\"recovery\"}";
        } else {
            metadata = "{\"reason\":\"command\",\"requested\":\"on\","
                    + "\"outcome\":\"" + OUTCOME_ABORTED + "\","
                    + "\"attempt\":\"" + attemptId + "\","
                    + "\"generation\":" + generation + ","
                    + "\"process\":\"" + process + "\","
                    + "\"abortReason\":\"recovery\"}";
        }
        return new AuditEntry(0L, now, actor, TOGGLE_AUDIT_ACTION,
                null, null, null, 1,
                Boolean.toString(false), Boolean.toString(false), metadata, List.of());
    }

    /** Whether the row is the durable success terminal of an attempt. */
    static boolean isCommittedRow(AuditEntry entry) {
        return entry != null
                && TOGGLE_AUDIT_ACTION.equals(entry.action())
                && entry.metadataJson() != null
                && entry.metadataJson().contains("\"outcome\":\"" + OUTCOME_COMMITTED + "\"");
    }

    /** Whether the row is an abort compensation for an {@code on} attempt. */
    static boolean isAbortRow(AuditEntry entry) {
        return entry != null
                && TOGGLE_AUDIT_ACTION.equals(entry.action())
                && entry.metadataJson() != null
                && entry.metadataJson().contains("\"outcome\":\"" + OUTCOME_ABORTED + "\"");
    }

    /** Whether the row is the first attempt row of a toggle. */
    static boolean isAttemptRow(AuditEntry entry) {
        return entry != null
                && TOGGLE_AUDIT_ACTION.equals(entry.action())
                && entry.metadataJson() != null
                && entry.metadataJson().contains("\"outcome\":\"" + OUTCOME_ATTEMPT + "\"");
    }

    /** Best-effort attempt id of a toggle row, empty when absent or malformed. */
    static Optional<UUID> attemptIdOf(AuditEntry entry) {
        if (entry == null || entry.metadataJson() == null) {
            return Optional.empty();
        }
        String marker = "\"attempt\":\"";
        String json = entry.metadataJson();
        int start = json.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(json.substring(start, end)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** Best-effort owning enable process of a toggle row, empty for legacy rows. */
    static Optional<UUID> processOf(AuditEntry entry) {
        if (entry == null || entry.metadataJson() == null) {
            return Optional.empty();
        }
        String marker = "\"process\":\"";
        String json = entry.metadataJson();
        int start = json.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(json.substring(start, end)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** Whether any abort row carries the attempt id. */
    static boolean hasAbortFor(UUID attemptId, List<AuditEntry> rows) {
        if (attemptId == null || rows == null) {
            return false;
        }
        for (AuditEntry row : rows) {
            if (isAbortRow(row) && attemptIdOf(row).map(attemptId::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any committed terminal carries the attempt id. */
    static boolean hasCommittedFor(UUID attemptId, List<AuditEntry> rows) {
        if (attemptId == null || rows == null) {
            return false;
        }
        for (AuditEntry row : rows) {
            if (isCommittedRow(row) && attemptIdOf(row).map(attemptId::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** Best-effort generation of a toggle row, empty when absent or malformed. */
    static Optional<Long> generationOf(AuditEntry entry) {
        if (entry == null || entry.metadataJson() == null) {
            return Optional.empty();
        }
        String marker = "\"generation\":";
        String json = entry.metadataJson();
        int start = json.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(json.substring(start, end)));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    /** Whether any terminal (committed or aborted) carries the attempt id. */
    static boolean hasTerminalFor(UUID attemptId, List<AuditEntry> rows) {
        return hasCommittedFor(attemptId, rows) || hasAbortFor(attemptId, rows);
    }

    /**
     * Whether the attempt id counts as a successful {@code on}: one attempt
     * row requesting on plus one committed terminal with the same id and an
     * actual {@code after=true} edge. An attempt alone, an abort, or a
     * legacy row without a committed terminal is never success.
     */
    static boolean isSuccessfulOn(UUID attemptId, List<AuditEntry> rows) {
        if (attemptId == null || rows == null) {
            return false;
        }
        for (AuditEntry row : rows) {
            if (isCommittedRow(row)
                    && attemptIdOf(row).map(attemptId::equals).orElse(false)
                    && "true".equals(row.afterJson())) {
                return true;
            }
        }
        return false;
    }

    /** Counts {@code after=true} rows without any outcome marker. */
    static long countUnmarkedSuccessRows(List<AuditEntry> rows) {
        if (rows == null) {
            return 0;
        }
        long unmarked = 0;
        for (AuditEntry row : rows) {
            if (row != null
                    && "true".equals(row.afterJson())
                    && (row.metadataJson() == null || !row.metadataJson().contains("\"outcome\""))) {
                unmarked++;
            }
        }
        return unmarked;
    }

    /**
     * Parses the exact {@code on|off} tail ({@code args[1]}),
     * case-insensitively. Anything else yields {@code null} (usage).
     */
    static Boolean parseTarget(String[] args) {
        if (args == null || args.length != 2) {
            return null;
        }
        String raw = args[1];
        if (raw == null) {
            return null;
        }
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        return switch (wanted) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private AuditRepository readAudits() {
        try {
            return audits == null ? null : audits.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Whether the startup recovery gate is open (completed normally). */
    private boolean isRecoveryOpen() {
        CompletableFuture<Void> gate = this.recoveryGate;
        if (gate == null) {
            return true;
        }
        return gate.isDone() && !gate.isCompletedExceptionally();
    }

    /** Whether the owning enable generation has shut down. */
    private boolean isClosed() {
        AdminBypassLifecycle token = this.lifecycle;
        return token != null && token.isClosed();
    }

    /**
     * Runs one state or audit initiation atomically against the generation
     * close: either it starts fully before the close lands, or the close
     * lands first and nothing runs. Only the initiation call itself runs
     * under the boundary — never any stage wait.
     *
     * @throws AdminBypassLifecycle.Closed when the generation shut down first
     */
    private <T> T initiate(java.util.function.Supplier<T> initiation) {
        AdminBypassLifecycle token = this.lifecycle;
        if (token == null) {
            return initiation.get();
        }
        return token.initiate(initiation);
    }

    /** Owning enable generation, never reused across enables. */
    UUID processGeneration() {
        return processGeneration;
    }

    private Instant readClock() {
        try {
            return clock == null ? null : clock.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean hasToggleAuthority(CommandSender sender) {
        try {
            return sender.hasPermission(LandPermissions.BYPASS);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private static UUID actorOf(Player player) {
        try {
            return player.getUniqueId();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static Locale senderLocale(Player player) {
        try {
            return player.locale();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private void replyAsync(Player player, ReplySink sink,
            String key, Map<String, Object> vars, Locale locale) {
        try {
            scheduler.runForPlayer(player, () -> {
                try {
                    sink.reply(key, vars, locale);
                } catch (RuntimeException ignored) {
                    // A broken reply outlet stays silent; the toggle already landed.
                }
            });
        } catch (RuntimeException dropped) {
            // The player-thread hop itself cannot be scheduled (retired
            // scheduler, departed player): fall back to one inline attempt so
            // every audited terminal still replies exactly once. The outlet
            // stays fail-closed (offline stays silent, unsafe stays silent),
            // and audit plus state already settled, so no invariant is moved.
            // The success path still hops first; this only runs when the hop
            // cannot be scheduled at all.
            try {
                sink.reply(key, vars, locale);
            } catch (RuntimeException ignored) {
                // A broken fallback outlet stays silent; the toggle already landed.
            }
        }
    }
}
