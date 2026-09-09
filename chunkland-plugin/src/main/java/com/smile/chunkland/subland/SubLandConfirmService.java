package com.smile.chunkland.subland;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Bukkit-free confirmation gate for SubLand mutations.
 *
 * <p>Token contract: the pair is the {@code sessionGeneration} plus the
 * {@code selectionRevision} the operator was shown, checked against the
 * operator's own live {@link SelectionSession} together with the live parent
 * structure revision. The session must own the {@code CREATE_SUBLAND} mode
 * and target the parent land; update/delete additionally target the SubLand.
 * Any mismatch yields {@link Optional#empty()} without a side effect, so
 * replays of old prompts, replaced or timed-out sessions, and structure
 * changes never reach the mutation runner.
 *
 * <p>Replay guard: the first accepted pair per live session instance is
 * recorded; a second submit with the same pair on the same session instance
 * is rejected. The mark is scoped by session reference (an equal-valued
 * replacement session is never blocked) and is released when the downstream
 * mutation reports anything but durable success, so a rejected mutation can
 * be confirmed again after fixing the cause. The guard slot is owned by this
 * service alone and never shares state with the claim confirmation path, so
 * SubLand confirmations cannot consume or overwrite claim tokens and vice
 * versa.
 *
 * <p>Threading: concurrent confirms for one player are serialized on a
 * private monitor so two racing submits admit exactly one acceptance.
 */
public final class SubLandConfirmService {

    /** Captured live-session data for one accepted confirmation. */
    public record Accepted(
            SelectionSession session,
            LandId parentId,
            Optional<SubLandId> targetSubLandId,
            long structureRevision) {
        public Accepted {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(targetSubLandId, "targetSubLandId");
        }
    }

    private final Object guard = new Object();
    private final Map<UUID, ConsumedMark> consumed = new HashMap<>();

    private record ConsumedMark(SelectionSession session, long generation, long revision) {
    }

    /**
     * Validate the token pair against the actor's live session.
     *
     * @param actor confirming player
     * @param generation {@code sessionGeneration} shown to the operator
     * @param revision {@code selectionRevision} shown to the operator
     * @param selections live selection registry
     * @param structures live parent structure revision source (unresolvable fails closed)
     * @return the captured session data, or empty on any rejection
     */
    public Optional<Accepted> accept(
            UUID actor,
            long generation,
            long revision,
            SelectionSessionManager selections,
            SelectionStructureRevisionLookup structures) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(selections, "selections");
        Objects.requireNonNull(structures, "structures");
        if (generation < 0 || revision < 0) {
            return Optional.empty();
        }
        synchronized (guard) {
            SelectionSession session;
            try {
                Optional<SelectionSession> current = selections.sessionFor(actor);
                if (current.isEmpty()) {
                    return Optional.empty();
                }
                session = current.get();
            } catch (RuntimeException failure) {
                return Optional.empty();
            }
            if (session.mode() != SelectionMode.CREATE_SUBLAND) {
                return Optional.empty();
            }
            if (session.sessionGeneration() != generation) {
                return Optional.empty();
            }
            if (session.selectionRevision() != revision) {
                return Optional.empty();
            }
            if (session.targetLandId().isEmpty()) {
                return Optional.empty();
            }
            if (session.pointA().isEmpty() && session.targetSubLandId().isEmpty()) {
                return Optional.empty();
            }
            ConsumedMark mark = consumed.get(actor);
            if (mark != null && mark.session() == session
                    && mark.generation() == generation && mark.revision() == revision) {
                return Optional.empty();
            }
            LandId parentId = session.targetLandId().get();
            OptionalLong live;
            try {
                live = structures.currentRevision(parentId);
            } catch (RuntimeException failure) {
                return Optional.empty();
            }
            if (live.isEmpty() || live.getAsLong() < 0
                    || live.getAsLong() != session.baseStructureRevision()) {
                return Optional.empty();
            }
            consumed.put(actor, new ConsumedMark(session, generation, revision));
            return Optional.of(new Accepted(
                    session, parentId, session.targetSubLandId(), session.baseStructureRevision()));
        }
    }

    /**
     * Release the single-use mark when the downstream mutation did not reach
     * durable success, so the operator can confirm again. Marks owned by a
     * newer acceptance are left untouched.
     */
    public void releaseIfNotSuccess(UUID actor, Accepted accepted) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        synchronized (guard) {
            ConsumedMark mark = consumed.get(actor);
            if (mark != null && mark.session() == accepted.session()
                    && mark.generation() == accepted.session().sessionGeneration()
                    && mark.revision() == accepted.session().selectionRevision()) {
                consumed.remove(actor);
            }
        }
    }
}
