package com.smile.chunkland.group;

import com.smile.chunkland.persistence.GroupRejectedException;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Command-facing entry for Global Groups in one player namespace.
 *
 * <p>Every call is scoped to the caller's own owner UUID: the durable owner
 * check runs inside the repository transaction, so a stale caller view can
 * never read or mutate another owner's groups. The sender is always their
 * own actor, which keeps console and foreign senders fail-closed at the
 * command layer before anything reaches here. Failed commits publish
 * nothing; there is no volatile cache to invalidate.
 */
public final class SubjectGroupService {

    private final SubjectGroupRepository repository;
    private final Clock clock;

    public SubjectGroupService(SubjectGroupRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public SubjectGroupService(SubjectGroupRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Create one group in the caller's namespace. */
    public CompletionStage<SubjectGroupRepository.GroupView> create(UUID owner, String displayName) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(displayName, "displayName");
        try {
            return repository.create(owner, displayName, owner, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** List every group in the caller's namespace. */
    public CompletionStage<List<SubjectGroupRepository.GroupView>> list(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        try {
            return repository.list(owner);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Resolve a group reference (UUID text or owner-scoped name) within the
     * caller's namespace. Anything else fails with {@code group.unknown} so
     * cross-owner guessing stays fail-closed.
     */
    public CompletionStage<SubjectGroupRepository.GroupView> resolve(UUID owner, String ref) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ref, "ref");
        String stripped = ref.strip();
        UUID parsed;
        try {
            parsed = UUID.fromString(stripped);
        } catch (IllegalArgumentException notUuid) {
            parsed = null;
        }
        CompletionStage<Optional<SubjectGroupRepository.GroupView>> found;
        try {
            found = parsed == null
                    ? repository.findByName(owner, stripped)
                    : repository.findById(owner, parsed);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (found == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("group lookup returned null"));
        }
        return found.thenApply(optional -> {
            if (optional == null || optional.isEmpty() || optional.get() == null) {
                throw new GroupRejectedException("group.unknown");
            }
            return optional.get();
        });
    }

    /** Add one member; resends succeed. */
    public CompletionStage<SubjectGroupRepository.MembershipOutcome> addMember(
            UUID owner, UUID groupId, UUID member) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(member, "member");
        try {
            return repository.addMember(owner, groupId, member, owner, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Remove one member; resends succeed. */
    public CompletionStage<SubjectGroupRepository.MembershipOutcome> removeMember(
            UUID owner, UUID groupId, UUID member) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(member, "member");
        try {
            return repository.removeMember(owner, groupId, member, owner, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Normal delete: refused while bindings reference the group. */
    public CompletionStage<Void> delete(UUID owner, UUID groupId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        try {
            return repository.delete(owner, groupId, owner, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Force delete: removes the referencing bindings in the same transaction. */
    public CompletionStage<SubjectGroupRepository.ForceDeleteOutcome> forceDelete(
            UUID owner, UUID groupId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(groupId, "groupId");
        try {
            return repository.forceDelete(owner, groupId, owner, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
