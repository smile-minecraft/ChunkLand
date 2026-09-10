package com.smile.chunkland.profile;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.ProfileRejectedException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Command-facing entry for player-owned Permission Profiles.
 *
 * <p>Every call is scoped to the caller's own owner UUID: the durable owner
 * check runs inside the repository transaction, so a stale caller view can
 * never read or mutate another owner's profiles. The sender is always their
 * own actor, which keeps console and foreign senders fail-closed at the
 * command layer before anything reaches here. Failed commits publish
 * nothing; there is no volatile cache to invalidate and no runtime resolver
 * to notify.
 *
 * <p>Raw permission and state text parse fail-closed here: unknown names,
 * reserved words and land-rule actions never reach the repository as values.
 *
 * <p>Every mutation also bumps the owner's ACL epoch inside its transaction
 * and then rebuilds the generic binding snapshot through the attached
 * refresh hook, so profile entry changes reach live decisions without a
 * restart. A failed refresh fails the returned stage even though the commit
 * itself is durable; the refresh publishes the unloaded marker in that case
 * so readers fail closed instead of trusting a stale view.
 */
public final class PermissionProfileService {

    private final PermissionProfileRepository repository;
    private final Clock clock;
    private final Supplier<CompletionStage<Void>> snapshotRefresh;

    public PermissionProfileService(PermissionProfileRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public PermissionProfileService(PermissionProfileRepository repository, Clock clock) {
        this(repository, clock, () -> CompletableFuture.completedFuture(null));
    }

    /**
     * @param snapshotRefresh rebuilds the generic binding snapshot after
     *                        every committed mutation using the system clock;
     *                        null reads as a no-op
     */
    public PermissionProfileService(PermissionProfileRepository repository,
            Supplier<CompletionStage<Void>> snapshotRefresh) {
        this(repository, Clock.systemUTC(), snapshotRefresh);
    }

    /**
     * @param snapshotRefresh rebuilds the generic binding snapshot after
     *                        every committed mutation; null reads as a no-op
     */
    public PermissionProfileService(PermissionProfileRepository repository, Clock clock,
            Supplier<CompletionStage<Void>> snapshotRefresh) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshotRefresh = snapshotRefresh != null
                ? snapshotRefresh
                : () -> CompletableFuture.completedFuture(null);
    }

    /** Create one profile in the caller's namespace. */
    public CompletionStage<PermissionProfileRepository.ProfileView> create(
            UUID owner, String displayName) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(displayName, "displayName");
        CompletionStage<PermissionProfileRepository.ProfileView> committed;
        try {
            committed = repository.create(owner, displayName, owner, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("profile repository returned null"));
        }
        return committed.thenCompose(view -> refresh().thenApply(ignored -> view));
    }

    /** List every profile in the caller's namespace. */
    public CompletionStage<List<PermissionProfileRepository.ProfileView>> list(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        try {
            return repository.list(owner);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Resolve a profile reference (canonical UUID text or owner-scoped name)
     * within the caller's namespace. Only 8-4-4-4-12 UUID text that renders
     * back identically (case-insensitive) counts as an id; anything else
     * fails with {@code profile.unknown} so cross-owner guessing stays
     * fail-closed.
     */
    public CompletionStage<PermissionProfileRepository.ProfileView> resolve(
            UUID owner, String ref) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ref, "ref");
        String stripped = ref.strip();
        UUID parsed = parseCanonicalUuid(stripped);
        CompletionStage<Optional<PermissionProfileRepository.ProfileView>> found;
        try {
            found = parsed == null
                    ? repository.findByName(owner, stripped)
                    : repository.findById(owner, parsed);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (found == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("profile lookup returned null"));
        }
        return found.thenApply(optional -> {
            if (optional == null || optional.isEmpty() || optional.get() == null) {
                throw new ProfileRejectedException("profile.unknown");
            }
            return optional.get();
        });
    }

    /**
     * Persist one sparse entry on the referenced profile. Permission text must
     * name a subject-permission action and state text must name a
     * {@link PermissionState}; {@code INHERIT} deletes the row. Anything else
     * fails with {@code profile.invalid_permission} or
     * {@code profile.invalid_state} before any lookup.
     */
    public CompletionStage<PermissionProfileRepository.EntryOutcome> setEntry(
            UUID owner, String ref, String permissionRaw, String stateRaw) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(permissionRaw, "permissionRaw");
        Objects.requireNonNull(stateRaw, "stateRaw");
        ProtectionActionType action;
        PermissionState state;
        try {
            action = parseAction(permissionRaw);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        try {
            state = parseState(stateRaw);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return resolve(owner, ref).thenCompose(view -> {
            CompletionStage<PermissionProfileRepository.EntryOutcome> committed;
            try {
                committed = repository.setEntry(owner, view.id(), action, state,
                        owner, clock.instant());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (committed == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("profile repository returned null"));
            }
            return committed.thenCompose(outcome -> refresh().thenApply(ignored -> outcome));
        });
    }

    /** Normal delete: refused while bindings reference the profile. */
    public CompletionStage<Void> delete(UUID owner, String ref) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ref, "ref");
        return resolve(owner, ref).thenCompose(view -> {
            CompletionStage<Void> committed;
            try {
                committed = repository.delete(owner, view.id(), owner, clock.instant());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (committed == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("profile repository returned null"));
            }
            return committed.thenCompose(ignored -> refresh());
        });
    }

    /** Force delete: removes the referencing bindings in the same transaction. */
    public CompletionStage<PermissionProfileRepository.ForceDeleteOutcome> forceDelete(
            UUID owner, String ref) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ref, "ref");
        return resolve(owner, ref).thenCompose(view -> {
            CompletionStage<PermissionProfileRepository.ForceDeleteOutcome> committed;
            try {
                committed = repository.forceDelete(owner, view.id(), owner, clock.instant());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (committed == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("profile repository returned null"));
            }
            return committed.thenCompose(outcome -> refresh().thenApply(ignored -> outcome));
        });
    }

    private CompletionStage<Void> refresh() {
        try {
            CompletionStage<Void> refreshed = snapshotRefresh.get();
            return refreshed != null
                    ? refreshed
                    : CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Parse only canonical 8-4-4-4-12 UUID text (case-insensitive, matching
     * the durable owner-key contract). {@link UUID#fromString} also accepts
     * short forms such as {@code 1-1-1-1-1} that render back as a different
     * string; those stay on the owner-scoped name path so they can never
     * alias another profile id.
     */
    private static UUID parseCanonicalUuid(String stripped) {
        if (stripped.isEmpty()) {
            return null;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(stripped);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
        if (!parsed.toString().equalsIgnoreCase(stripped)) {
            return null;
        }
        return parsed;
    }

    private static ProtectionActionType parseAction(String raw) {        String stripped = raw.strip();
        if (stripped.isEmpty()
                || stripped.equalsIgnoreCase("EVERYONE")
                || stripped.equals("*")) {
            throw new ProfileRejectedException("profile.invalid_permission");
        }
        ProtectionActionType action;
        try {
            action = ProtectionActionType.valueOf(stripped.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ProfileRejectedException("profile.invalid_permission", unknown);
        }
        if (action.decisionSource() != DecisionSource.SUBJECT_PERMISSION) {
            throw new ProfileRejectedException("profile.invalid_permission");
        }
        return action;
    }

    private static PermissionState parseState(String raw) {
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw new ProfileRejectedException("profile.invalid_state");
        }
        try {
            return PermissionState.valueOf(stripped.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ProfileRejectedException("profile.invalid_state", unknown);
        }
    }
}
