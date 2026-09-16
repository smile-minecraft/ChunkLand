package com.smile.chunkland.command;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Async boundary for player-name resolution.
 *
 * <p>A UUID string parses directly and an online exact (case-sensitive) name
 * resolves through the injected region-safe lookup — both complete
 * synchronously without touching the executor. Anything else is dispatched to
 * the explicit {@link Executor}: the blocking lookup (OfflinePlayer, Mojang
 * profile, or any other network/disk query) runs only there, so the
 * region-facing caller never blocks on it and never waits for the answer.
 *
 * <p>Every degraded answer fails closed to {@link Optional#empty()}: reserved
 * subjects ({@code EVERYONE}, {@code *}, group syntax), blank input, unknown
 * names, lookup failures, {@code null} answers and timeouts. Empty never
 * distinguishes which cause fired, so callers can deny without probing
 * whether a name exists.
 */
public final class OfflinePlayerResolver {

    /** Potentially blocking name lookup; runs only on the async executor. */
    @FunctionalInterface
    public interface BlockingLookup {
        Optional<UUID> lookup(String name);
    }

    private final Function<String, Optional<UUID>> onlineIds;
    private final BlockingLookup offlineLookup;
    private final Executor executor;

    /**
     * @param onlineIds region-safe online exact-name lookup (UUID text is
     *                  parsed before it is consulted); {@code null} resolves
     *                  every name fail closed
     * @param offlineLookup blocking name lookup; invoked only on
     *                      {@code executor}, {@code null} fails every
     *                      offline name closed
     * @param executor async seam for the blocking lookup; {@code null} fails
     *                 every offline name closed without running anything
     */
    public OfflinePlayerResolver(Function<String, Optional<UUID>> onlineIds,
            BlockingLookup offlineLookup, Executor executor) {
        this.onlineIds = onlineIds;
        this.offlineLookup = offlineLookup;
        this.executor = executor;
    }

    /**
     * Resolves one raw player reference without blocking the caller.
     *
     * @return a stage that is already complete for UUID text, online names
     *     and every fail-closed input, and pending on the executor otherwise
     */
    public CompletionStage<Optional<UUID>> resolveAsync(String raw) {
        String stripped = raw == null ? null : raw.strip();
        if (stripped == null || stripped.isEmpty() || isReserved(stripped)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return CompletableFuture.completedFuture(Optional.of(UUID.fromString(stripped)));
        } catch (IllegalArgumentException notUuid) {
            // Fall through to the name lookups below.
        }
        Optional<UUID> online = resolveOnline(stripped);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(online);
        }
        if (offlineLookup == null || executor == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<UUID>> pending = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    Optional<UUID> found = offlineLookup.lookup(stripped);
                    pending.complete(found == null ? Optional.empty() : found);
                } catch (RuntimeException failure) {
                    pending.complete(Optional.empty());
                }
            });
        } catch (RuntimeException rejected) {
            pending.complete(Optional.empty());
        }
        return pending;
    }

    /**
     * Resolves with a fail-closed timeout: a lookup that does not answer in
     * time yields empty instead of leaving the caller hanging, and the cause
     * (timeout, failure, unknown) is never distinguished.
     */
    public CompletionStage<Optional<UUID>> resolveAsync(String raw, Duration timeout) {
        CompletionStage<Optional<UUID>> base;
        try {
            base = resolveAsync(raw);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (base == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return failClosed(base);
        }
        CompletableFuture<Optional<UUID>> bounded;
        try {
            bounded = base.toCompletableFuture()
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return failClosed(bounded);
    }

    /**
     * Maps any exceptional completion (timeout, transport failure, lookup
     * error) to empty, keeping the success value untouched. A {@code null}
     * stage or a {@code null} success value also yields empty.
     */
    public static CompletionStage<Optional<UUID>> failClosed(
            CompletionStage<Optional<UUID>> stage) {
        if (stage == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<UUID>> done;
        try {
            done = stage.toCompletableFuture();
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return done.thenApply(found -> found == null ? Optional.<UUID>empty() : found)
                .exceptionally(failure -> Optional.<UUID>empty());
    }

    private Optional<UUID> resolveOnline(String stripped) {
        if (onlineIds == null) {
            return Optional.empty();
        }
        try {
            Optional<UUID> found = onlineIds.apply(stripped);
            if (found == null || found.isEmpty() || found.get() == null) {
                return Optional.empty();
            }
            return found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static boolean isReserved(String stripped) {
        if (stripped.equalsIgnoreCase("EVERYONE") || stripped.equals("*")) {
            return true;
        }
        String lowered = stripped.toLowerCase(Locale.ROOT);
        return lowered.startsWith("group(");
    }

    /** Exposes the async posture for diagnostics without leaking internals. */
    @Override
    public String toString() {
        return "OfflinePlayerResolver{async=" + (executor != null) + "}";
    }
}
