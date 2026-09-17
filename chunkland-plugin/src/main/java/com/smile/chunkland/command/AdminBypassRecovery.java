package com.smile.chunkland.command;

import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Startup recovery for bypass audit terminals.
 *
 * <p>Every {@code on} or {@code off} attempt writes one attempt row before
 * its state flip and one terminal afterwards: committed on success,
 * aborted when the commit cannot succeed. A crash between the attempt and
 * its terminal, or a doubly-failed compensation, leaves an open attempt
 * with no terminal. An open attempt is never success: only a committed
 * terminal with the same attempt id counts.
 *
 * <p>Each enable generation starts with a fresh in-memory state where every
 * actor is off and a unique process id carried by every new attempt and
 * terminal row. Recovery scans the full toggle history with stable
 * {@code timestamp DESC, id DESC} pages, collects every open attempt from
 * previous generations, then appends one abort row per open attempt with
 * the same id. Attempts from the recovering enable itself are never
 * aborted; legacy rows without a process id are treated as previous
 * generations. Scans collect first and write afterwards, so compensation
 * rows cannot shift page offsets. Aborts are bounded per page size,
 * non-blocking (pure stage chains), and retryable (failed aborts stay
 * open for the next recovery).
 */
public final class AdminBypassRecovery {

    /** Bounded page size for one recovery scan page (query maximum). */
    public static final int DEFAULT_LIMIT = 500;

    /** Stable page size used for full scans (query maximum). */
    public static final int PAGE_SIZE = 100;

    /** Result of one recovery scan. */
    public record RecoveryResult(
            int examined,
            int openFound,
            int abortedWritten,
            List<UUID> abortedAttemptIds,
            List<UUID> failedAttemptIds) {
        public RecoveryResult {
            abortedAttemptIds = List.copyOf(abortedAttemptIds);
            failedAttemptIds = List.copyOf(failedAttemptIds);
        }
    }

    private AdminBypassRecovery() {
    }

    /**
     * Scans the full toggle history and aborts every open attempt. Never
     * blocks; pages are bounded, collection precedes compensation so new
     * abort rows cannot shift page offsets. A scan failure fails the stage
     * so the caller can retry on the next enable; per-abort failures are
     * reported inside the result and stay open for the next recovery.
     */
    public static CompletionStage<RecoveryResult> recover(
            Supplier<AuditRepository> audits,
            Supplier<Instant> clock,
            int limit) {
        return recover(audits, clock, limit, null);
    }

    /**
     * Full-scan recovery that never aborts the recovering enable itself:
     * open attempts whose process id equals {@code currentProcess} are
     * skipped; legacy rows without a process id are treated as previous
     * generations. A null {@code currentProcess} disables the filter.
     */
    public static CompletionStage<RecoveryResult> recover(
            Supplier<AuditRepository> audits,
            Supplier<Instant> clock,
            int limit,
            UUID currentProcess) {
        return recover(audits, clock, limit, currentProcess, null);
    }

    /**
     * Full-scan recovery bound to one enable lifecycle: every scan page and
     * every compensation insert first checks the token, so a closed
     * generation stops without writing rows. A null page or read failure
     * fails the stage so the gate stays closed and the next enable retries.
     * A null {@code lifecycle} disables the close checks.
     */
    public static CompletionStage<RecoveryResult> recover(
            Supplier<AuditRepository> audits,
            Supplier<Instant> clock,
            int limit,
            UUID currentProcess,
            AdminBypassLifecycle lifecycle) {
        if (audits == null || clock == null || limit <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("recovery needs audits, clock and a positive limit"));
        }
        int pageSize = Math.min(limit, com.smile.chunkland.persistence.AuditSearchQuery.MAX_LIMIT);
        if (pageSize <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("recovery needs a positive page size"));
        }
        final AuditRepository repo;
        try {
            repo = audits.get();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (repo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("audit source unavailable"));
        }
        final Instant now;
        try {
            now = clock.get();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (now == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("clock unavailable"));
        }
        CompletableFuture<RecoveryResult> result = new CompletableFuture<>();
        try {
            if (isClosed(lifecycle)) {
                result.completeExceptionally(new IllegalStateException("recovery closed"));
                return result;
            }
            scanPages(repo, pageSize, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                    now, currentProcess, lifecycle, result);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /** Whether the owning enable generation has shut down. */
    private static boolean isClosed(AdminBypassLifecycle lifecycle) {
        return lifecycle != null && lifecycle.isClosed();
    }

    /**
     * Runs one repository initiation atomically against the generation
     * close. A null lifecycle disables the boundary.
     *
     * @throws AdminBypassLifecycle.Closed when the generation shut down first
     */
    private static <T> T initiate(AdminBypassLifecycle lifecycle,
            java.util.function.Supplier<T> initiation) {
        if (lifecycle == null) {
            return initiation.get();
        }
        return lifecycle.initiate(initiation);
    }

    /** Open attempts in the scanned rows: attempt ids with no terminal. */
    static Map<UUID, AuditEntry> openAttempts(List<AuditEntry> rows) {
        return openAttempts(rows, null);
    }

    /** Open attempts excluding the recovering enable itself. */
    static Map<UUID, AuditEntry> openAttempts(List<AuditEntry> rows, UUID currentProcess) {
        Map<UUID, AuditEntry> attempts = new LinkedHashMap<>();
        Map<UUID, Boolean> terminal = new LinkedHashMap<>();
        if (rows == null) {
            return attempts;
        }
        for (AuditEntry row : rows) {
            if (row == null || !AdminBypassCommandHandler.TOGGLE_AUDIT_ACTION.equals(row.action())) {
                continue;
            }
            Optional<UUID> id = AdminBypassCommandHandler.attemptIdOf(row);
            if (id.isEmpty()) {
                continue;
            }
            if (AdminBypassCommandHandler.isAttemptRow(row)) {
                attempts.putIfAbsent(id.get(), row);
            } else if (AdminBypassCommandHandler.isCommittedRow(row)
                    || AdminBypassCommandHandler.isAbortRow(row)) {
                terminal.put(id.get(), Boolean.TRUE);
            }
        }
        attempts.keySet().removeAll(terminal.keySet());
        if (currentProcess != null) {
            attempts.entrySet().removeIf(entry ->
                    AdminBypassCommandHandler.processOf(entry.getValue())
                            .map(currentProcess::equals).orElse(false));
        }
        return attempts;
    }

    private static void scanPages(AuditRepository repo, int pageSize, int offset,
            Map<UUID, AuditEntry> attempts, Map<UUID, Boolean> terminals,
            Instant now, UUID currentProcess, AdminBypassLifecycle lifecycle,
            CompletableFuture<RecoveryResult> result) {
        if (isClosed(lifecycle)) {
            result.completeExceptionally(new IllegalStateException("recovery closed"));
            return;
        }
        final com.smile.chunkland.persistence.AuditSearchQuery query;
        try {
            query = new com.smile.chunkland.persistence.AuditSearchQuery(
                    null, null, AdminBypassCommandHandler.TOGGLE_AUDIT_ACTION, null, null,
                    pageSize, offset);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
            return;
        }
        final CompletionStage<List<AuditEntry>> page;
        try {
            page = initiate(lifecycle, () -> repo.search(query));
        } catch (AdminBypassLifecycle.Closed closed) {
            result.completeExceptionally(new IllegalStateException("recovery closed"));
            return;
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
            return;
        }
        if (page == null) {
            result.completeExceptionally(new IllegalStateException("audit scan unavailable"));
            return;
        }
        try {
            page.whenComplete((rows, scanFailure) -> {
                if (isClosed(lifecycle)) {
                    result.completeExceptionally(new IllegalStateException("recovery closed"));
                    return;
                }
                if (scanFailure != null) {
                    result.completeExceptionally(scanFailure instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException(scanFailure));
                    return;
                }
                if (rows == null) {
                    result.completeExceptionally(
                            new IllegalStateException("audit page unavailable"));
                    return;
                }
                List<AuditEntry> safe = rows;
                for (AuditEntry row : safe) {
                    if (row == null
                            || !AdminBypassCommandHandler.TOGGLE_AUDIT_ACTION.equals(row.action())) {
                        continue;
                    }
                    Optional<UUID> id = AdminBypassCommandHandler.attemptIdOf(row);
                    if (id.isEmpty()) {
                        continue;
                    }
                    if (AdminBypassCommandHandler.isAttemptRow(row)) {
                        attempts.putIfAbsent(id.get(), row);
                    } else if (AdminBypassCommandHandler.isCommittedRow(row)
                            || AdminBypassCommandHandler.isAbortRow(row)) {
                        terminals.put(id.get(), Boolean.TRUE);
                    }
                }
                if (safe.size() < pageSize) {
                    Map<UUID, AuditEntry> open = new LinkedHashMap<>(attempts);
                    open.keySet().removeAll(terminals.keySet());
                    if (currentProcess != null) {
                        open.entrySet().removeIf(entry ->
                                AdminBypassCommandHandler.processOf(entry.getValue())
                                        .map(currentProcess::equals).orElse(false));
                    }
                    int examined = attempts.size() + terminals.size();
                    abortAll(repo, now, new ArrayList<>(open.values()), examined,
                            currentProcess, lifecycle, result);
                    return;
                }
                scanPages(repo, pageSize, offset + pageSize, attempts, terminals,
                        now, currentProcess, lifecycle, result);
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    private static void abortAll(AuditRepository repo, Instant now, List<AuditEntry> open,
            int examined, UUID currentProcess, AdminBypassLifecycle lifecycle,
            CompletableFuture<RecoveryResult> result) {
        List<UUID> done = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        abortNext(repo, now, open, 0, examined, currentProcess, lifecycle, done, failed, result);
    }

    private static void abortNext(AuditRepository repo, Instant now, List<AuditEntry> open, int index,
            int examined, UUID currentProcess, AdminBypassLifecycle lifecycle,
            List<UUID> done, List<UUID> failed,
            CompletableFuture<RecoveryResult> result) {
        if (isClosed(lifecycle)) {
            result.completeExceptionally(new IllegalStateException("recovery closed"));
            return;
        }
        if (index >= open.size()) {
            try {
                result.complete(new RecoveryResult(examined, open.size(), done.size(),
                        List.copyOf(done), List.copyOf(failed)));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
            return;
        }
        AuditEntry attempt = open.get(index);
        Optional<UUID> id = AdminBypassCommandHandler.attemptIdOf(attempt);
        if (id.isEmpty() || attempt.actor() == null) {
            id.ifPresent(failed::add);
            abortNext(repo, now, open, index + 1, examined, currentProcess, lifecycle, done, failed, result);
            return;
        }
        final AuditEntry abort;
        try {
            Optional<Long> generation = AdminBypassCommandHandler.generationOf(attempt);
            abort = currentProcess == null
                    ? AdminBypassCommandHandler.recoveryAbortAudit(
                            attempt.actor(), now, id.get(), generation.orElse(null))
                    : AdminBypassCommandHandler.recoveryAbortAudit(
                            attempt.actor(), now, id.get(), generation.orElse(null), currentProcess);
        } catch (RuntimeException malformed) {
            failed.add(id.get());
            abortNext(repo, now, open, index + 1, examined, currentProcess, lifecycle, done, failed, result);
            return;
        }
        final CompletionStage<Long> written;
        try {
            written = initiate(lifecycle, () -> repo.insert(abort));
        } catch (AdminBypassLifecycle.Closed closed) {
            failed.add(id.get());
            result.completeExceptionally(new IllegalStateException("recovery closed"));
            return;
        } catch (RuntimeException failure) {
            failed.add(id.get());
            abortNext(repo, now, open, index + 1, examined, currentProcess, lifecycle, done, failed, result);
            return;
        }
        if (written == null) {
            failed.add(id.get());
            abortNext(repo, now, open, index + 1, examined, currentProcess, lifecycle, done, failed, result);
            return;
        }
        final UUID attemptId = id.get();
        try {
            written.whenComplete((ignored, abortFailure) -> {
                if (abortFailure != null) {
                    failed.add(attemptId);
                } else {
                    done.add(attemptId);
                }
                abortNext(repo, now, open, index + 1, examined, currentProcess, lifecycle, done, failed, result);
            });
        } catch (RuntimeException failure) {
            failed.add(attemptId);
            abortNext(repo, now, open, index + 1, examined, currentProcess, lifecycle, done, failed, result);
        }
    }

    /**
     * Whether the attempt id counts as a successful {@code on} in the given
     * rows: one committed terminal with the same id and an actual
     * {@code after=true} edge. An attempt alone is never success.
     */
    public static boolean isSuccessfulOn(UUID attemptId, List<AuditEntry> rows) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (rows == null) {
            return false;
        }
        return AdminBypassCommandHandler.isSuccessfulOn(attemptId, rows);
    }
}
