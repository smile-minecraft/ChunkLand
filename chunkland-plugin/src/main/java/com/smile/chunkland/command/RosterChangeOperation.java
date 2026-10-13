package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Shared roster-change operation behind trust, untrust, ban, unban and the
 * management roster adds.
 *
 * <p>Every submit carries a raw player reference (UUID text, an online exact
 * name, or a known offline name) against one fixed land. UUID text and online
 * names resolve synchronously; anything else resolves through the injected
 * {@link OfflinePlayerResolver} on its explicit executor, so the
 * region-facing caller never blocks and never waits. Unknown names,
 * timeouts, lookup failures, reserved subjects and blank input all resolve
 * empty and are denied without revealing whether the name exists.
 *
 * <p>The land is fixed by the caller before submit and is never re-resolved
 * from the viewer's position afterwards. After resolution completes, the
 * continuation hops to the viewer's own thread through the injected
 * {@link PlayerScheduler} and re-checks, in order, that the enable
 * generation is still alive, the viewer is still online, the fixed land
 * still exists, and the viewer still passes the slot gate — then, for bans,
 * that the target is still bannable. Any mismatch denies without writing.
 * One submit writes at most once: a per-submit guard drops duplicate
 * completions, and a retired scheduler drops the continuation fail-closed.
 */
public final class RosterChangeOperation {

    /** Which roster change one submit performs. */
    public enum Slot {
        TRUST("trust"),
        UNTRUST("untrust"),
        BAN("ban"),
        UNBAN("unban");

        private final String key;

        Slot(String key) {
            this.key = key;
        }

        /** Command slot this change reports under. */
        public String key() {
            return key;
        }

        /** Slot for a command word, or empty when it names no roster change. */
        public static Optional<Slot> ofKey(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String stripped = raw.strip().toLowerCase(Locale.ROOT);
            for (Slot slot : values()) {
                if (slot.key.equals(stripped)) {
                    return Optional.of(slot);
                }
            }
            return Optional.empty();
        }
    }

    /** Terminal outcome of one submit. */
    public enum Outcome {
        SUCCESS,
        UNKNOWN_TARGET,
        GATE_DENIED,
        NOT_ALLOWED,
        UNAVAILABLE,
        FAILED
    }

    /**
     * Terminal report of one submit.
     *
     * @param slot which change was attempted
     * @param outcome what happened
     * @param target resolved player, or {@code null} unless successful
     * @param label display label for the reference (the stripped raw input)
     * @param reason reason code for failures
     *              ({@code <slot>.unknown_target}, {@code <slot>.failed},
     *              {@code <slot>.unavailable}, {@code ban.not_allowed});
     *              empty on success
     */
    public record Result(Slot slot, Outcome outcome, UUID target, String label, String reason) {
    }

    /** Terminal report sink; runs on the viewer's thread when the hop succeeds. */
    @FunctionalInterface
    public interface Reporter {
        void report(Result result);
    }

    /** One durable roster change; the same entry the services expose. */
    @FunctionalInterface
    public interface Mutation {
        CompletionStage<Void> apply(UUID actor, LandId landId, UUID target);
    }

    /** Slot gate over the live snapshot; {@code false} denies without writing. */
    @FunctionalInterface
    public interface Gate {
        boolean allows(UUID viewer, LandId landId, String slot);
    }

    /** Whether the viewer is still online; {@code false} denies without writing. */
    @FunctionalInterface
    public interface Presence {
        boolean online(UUID viewer);
    }

    /** Whether the fixed land still exists; {@code false} denies without writing. */
    @FunctionalInterface
    public interface Lands {
        boolean exists(LandId landId);
    }

    /** Whether the target may be banned on the fixed land right now. */
    @FunctionalInterface
    public interface BanGuard {
        boolean bannable(LandId landId, UUID target);
    }

    /** Fail-closed async timeout for offline name resolution. */
    static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(10);

    private final Function<String, Optional<UUID>> onlineIds;
    private final OfflinePlayerResolver resolver;
    private final PlayerScheduler scheduler;
    private final Gate gate;
    private final Presence presence;
    private final Lands lands;
    private final BooleanSupplier alive;
    private final BanGuard banGuard;
    private final Function<Slot, Mutation> mutations;
    private final Duration resolveTimeout;

    /**
     * @param onlineIds sync lookup for UUID text and online exact names;
     *                  {@code null} resolves every legacy name fail closed
     * @param resolver async offline-name resolution; {@code null} keeps the
     *                 legacy online-only behaviour
     * @param scheduler player-thread hop for every async continuation;
     *                  {@code null} runs continuations inline
     * @param gate live slot-gate re-check; {@code null} denies everything
     * @param presence viewer-online re-check; {@code null} denies everything
     * @param lands fixed-land existence re-check; {@code null} denies everything
     * @param alive enable-generation liveness; {@code null} denies everything
     * @param banGuard live bannable re-check for bans; {@code null} skips it
     * @param mutations durable writes per slot; a {@code null} or missing
     *                  entry reports unavailable without side effects
     */
    public RosterChangeOperation(Function<String, Optional<UUID>> onlineIds,
            OfflinePlayerResolver resolver, PlayerScheduler scheduler,
            Gate gate, Presence presence, Lands lands, BooleanSupplier alive,
            BanGuard banGuard, Function<Slot, Mutation> mutations) {
        this(onlineIds, resolver, scheduler, gate, presence, lands, alive,
                banGuard, mutations, RESOLVE_TIMEOUT);
    }

    /**
     * Full constructor with an explicit resolution timeout, so tests can
     * force the timeout path without waiting out the production budget.
     *
     * @param resolveTimeout fail-closed budget for one offline lookup;
     *                       {@code null} keeps the production budget
     */
    public RosterChangeOperation(Function<String, Optional<UUID>> onlineIds,
            OfflinePlayerResolver resolver, PlayerScheduler scheduler,
            Gate gate, Presence presence, Lands lands, BooleanSupplier alive,
            BanGuard banGuard, Function<Slot, Mutation> mutations,
            Duration resolveTimeout) {
        this.onlineIds = onlineIds;
        this.resolver = resolver;
        this.scheduler = scheduler == null ? PlayerScheduler.direct() : scheduler;
        this.gate = gate;
        this.presence = presence;
        this.lands = lands;
        this.alive = alive;
        this.banGuard = banGuard;
        this.mutations = mutations;
        this.resolveTimeout = resolveTimeout == null ? RESOLVE_TIMEOUT : resolveTimeout;
    }

    /**
     * Submits one roster change and returns without waiting for it.
     *
     * @param viewerHandle viewer for the scheduler hop; never {@code null}
     * @param viewer acting player; never {@code null}
     * @param landId fixed target land; never re-resolved, never {@code null}
     * @param slot which change to perform; never {@code null}
     * @param raw raw player reference; never {@code null}
     * @param reporter terminal report; never {@code null}
     * @param onTerminal runs exactly once when the submit terminates,
     *                   however it terminates; {@code null} runs nothing
     */
    public void submit(Player viewerHandle, UUID viewer, LandId landId, Slot slot,
            String raw, Reporter reporter, Runnable onTerminal) {
        if (reporter == null) {
            terminal(onTerminal);
            return;
        }
        if (viewerHandle == null || viewer == null || landId == null || slot == null
                || raw == null || raw.isBlank()) {
            deny(slot, raw, Outcome.UNKNOWN_TARGET, reporter, onTerminal);
            return;
        }
        String label = raw.strip();
        Mutation mutation = mutationFor(slot);
        if (mutation == null) {
            report(new Result(slot, Outcome.UNAVAILABLE, null, label,
                    slot.key() + ".unavailable"), reporter, onTerminal);
            return;
        }
        CompletionStage<Optional<UUID>> resolved = resolve(raw);
        Attempt attempt = new Attempt(viewerHandle, viewer, landId, slot, label,
                mutation, reporter, onTerminal);
        try {
            resolved.whenComplete(attempt::onResolved);
        } catch (RuntimeException failure) {
            attempt.onResolved(null, failure);
        }
    }

    private Mutation mutationFor(Slot slot) {
        if (mutations == null || slot == null) {
            return null;
        }
        try {
            return mutations.apply(slot);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private CompletionStage<Optional<UUID>> resolve(String raw) {
        if (resolver != null) {
            try {
                CompletionStage<Optional<UUID>> resolved =
                        resolver.resolveAsync(raw, resolveTimeout);
                if (resolved != null) {
                    return resolved;
                }
            } catch (RuntimeException failure) {
                // Fall through to the fail-closed answer below.
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(resolveLegacy(raw));
    }

    private Optional<UUID> resolveLegacy(String raw) {
        String stripped = raw.strip();
        if (stripped.isEmpty() || isReserved(stripped)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(stripped));
        } catch (IllegalArgumentException notUuid) {
            // Not a UUID: only an online exact name may resolve it here.
        }
        if (onlineIds == null) {
            return Optional.empty();
        }
        try {
            Optional<UUID> found = onlineIds.apply(stripped);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static boolean isReserved(String stripped) {
        if (stripped.equalsIgnoreCase("EVERYONE") || stripped.equals("*")) {
            return true;
        }
        return stripped.toLowerCase(Locale.ROOT).startsWith("group(");
    }

    private void deny(Slot slot, String raw, Outcome outcome, Reporter reporter,
            Runnable onTerminal) {
        String label = raw == null ? "" : raw.strip();
        String reason = slot == null ? "" : slot.key() + switch (outcome) {
            case UNKNOWN_TARGET -> ".unknown_target";
            case UNAVAILABLE -> ".unavailable";
            case NOT_ALLOWED -> ".not_allowed";
            default -> ".failed";
        };
        report(new Result(slot, outcome, null, label, reason), reporter, onTerminal);
    }

    private void report(Result result, Reporter reporter, Runnable onTerminal) {
        try {
            reporter.report(result);
        } catch (RuntimeException ignored) {
            // Terminal report path: never let a sink failure escape.
        } finally {
            terminal(onTerminal);
        }
    }

    /** Per-submit state; the guards allow exactly one write and one terminal signal. */
    private final class Attempt {
        private final Player viewerHandle;
        private final UUID viewer;
        private final LandId landId;
        private final Slot slot;
        private final String label;
        private final Mutation mutation;
        private final Reporter reporter;
        private final Runnable onTerminal;
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        private Attempt(Player viewerHandle, UUID viewer, LandId landId, Slot slot,
                String label, Mutation mutation, Reporter reporter, Runnable onTerminal) {
            this.viewerHandle = viewerHandle;
            this.viewer = viewer;
            this.landId = landId;
            this.slot = slot;
            this.label = label;
            this.mutation = mutation;
            this.reporter = reporter;
            this.onTerminal = onTerminal;
        }

        private void onResolved(Optional<UUID> found, Throwable failure) {
            if (failure != null || found == null || found.isEmpty() || found.get() == null) {
                hop(() -> deny(Outcome.UNKNOWN_TARGET, ".unknown_target"));
                return;
            }
            UUID target = found.get();
            hop(() -> write(target));
        }

        private void hop(Runnable task) {
            try {
                if (scheduler instanceof DiscardingPlayerScheduler discarding) {
                    discarding.runForPlayer(viewerHandle, task, this::terminal);
                } else {
                    scheduler.runForPlayer(viewerHandle, task);
                }
            } catch (RuntimeException retired) {
                // The hop itself could not be scheduled: drop fail-closed
                // without writing or reporting.
                terminal();
            }
        }

        /** Exactly-once terminal signal for flight-flag release. */
        private void terminal() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            RosterChangeOperation.this.terminal(onTerminal);
        }

        private void report(Result result) {
            try {
                reporter.report(result);
            } catch (RuntimeException ignored) {
                // Terminal report path: never let a sink failure escape.
            } finally {
                terminal();
            }
        }

        private void deny(Outcome outcome, String reasonSuffix) {
            report(new Result(slot, outcome, null, label, slot.key() + reasonSuffix));
        }

        private void write(UUID target) {
            if (!live() || !present() || !landKnown() || !gateOpen()) {
                return;
            }
            if (slot == Slot.BAN && !bannable(target)) {
                return;
            }
            if (!settled.compareAndSet(false, true)) {
                terminal();
                return;
            }
            CompletionStage<Void> stage;
            try {
                stage = mutation.apply(viewer, landId, target);
            } catch (RuntimeException failure) {
                stage = null;
            }
            if (stage == null) {
                report(new Result(slot, Outcome.FAILED, null, label, slot.key() + ".failed"));
                return;
            }
            stage.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    hop(() -> report(new Result(slot, Outcome.FAILED, null, label,
                            slot.key() + ".failed")));
                    return;
                }
                hop(() -> report(new Result(slot, Outcome.SUCCESS, target, label, "")));
            });
        }

        private boolean live() {
            boolean open;
            try {
                open = alive != null && alive.getAsBoolean();
            } catch (RuntimeException failure) {
                open = false;
            }
            if (!open) {
                deny(Outcome.FAILED, ".failed");
                return false;
            }
            return true;
        }

        private boolean present() {
            boolean online;
            try {
                online = presence != null && presence.online(viewer);
            } catch (RuntimeException failure) {
                online = false;
            }
            if (!online) {
                deny(Outcome.FAILED, ".failed");
                return false;
            }
            return true;
        }

        private boolean landKnown() {
            boolean known;
            try {
                known = lands != null && lands.exists(landId);
            } catch (RuntimeException failure) {
                known = false;
            }
            if (!known) {
                deny(Outcome.FAILED, ".failed");
                return false;
            }
            return true;
        }

        private boolean gateOpen() {
            boolean allowed;
            try {
                allowed = gate != null && gate.allows(viewer, landId, slot.key());
            } catch (RuntimeException failure) {
                allowed = false;
            }
            if (!allowed) {
                deny(Outcome.GATE_DENIED, ".failed");
                return false;
            }
            return true;
        }

        private boolean bannable(UUID target) {
            boolean allowed;
            try {
                allowed = banGuard == null || banGuard.bannable(landId, target);
            } catch (RuntimeException failure) {
                allowed = false;
            }
            if (!allowed) {
                report(new Result(slot, Outcome.NOT_ALLOWED, null, label, "ban.not_allowed"));
                return false;
            }
            return true;
        }
    }

    private void terminal(Runnable onTerminal) {
        if (onTerminal == null) {
            return;
        }
        try {
            onTerminal.run();
        } catch (RuntimeException ignored) {
            // Terminal release must never escape onto executor threads.
        }
    }
}
