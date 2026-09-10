package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.binding.LandBindingService;
import com.smile.chunkland.group.SubjectGroupService;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.BindingRejectedException;
import com.smile.chunkland.persistence.GroupRejectedException;
import com.smile.chunkland.persistence.ProfileRejectedException;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import com.smile.chunkland.profile.PermissionProfileService;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land binding <bind|unbind>} handler.
 *
 * <p>Both verbs act on the land under the sender's current position; an
 * optional {@code --in-subland} tail flag narrows the scope to the subland
 * covering the sender's block position instead. The subject is either a
 * player (UUID text or online exact name) or a group (UUID text or
 * owner-scoped name resolved in the sender's own namespace). The profile is
 * a UUID or owner-scoped name the same way. Reserved subjects
 * ({@code EVERYONE}, {@code *}, names instead of ids) never resolve, and the
 * implicit direct profile is refused by the durable guard. The domain gate
 * ({@code MANAGE_PERMISSION}) is enforced by the {@link LandCommand}
 * dispatcher before this handler runs; this handler owns only input
 * resolution and the durable mutation call.
 *
 * <p>Threading: everything sender-facing happens synchronously on the
 * calling thread; only the reply runs on mutation completion, using values
 * captured up front, so the callback never touches server objects.
 */
public final class BindingCommandHandler implements LandCommand.Handler {

    /** Durable binding operations; kept as a seam so tests observe the call. */
    public interface Bindings {
        CompletionStage<LandBindingRepository.BindOutcome> bindLand(
                UUID owner, LandId landId, LandBindingRepository.Subject subject, UUID profileId);

        CompletionStage<LandBindingRepository.UnbindOutcome> unbindLand(
                UUID owner, LandId landId, LandBindingRepository.Subject subject);

        CompletionStage<LandBindingRepository.BindOutcome> bindSubland(
                UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject,
                UUID profileId);

        CompletionStage<LandBindingRepository.UnbindOutcome> unbindSubland(
                UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject);
    }

    /** Group reference resolution in the caller's namespace. */
    public interface Groups {
        CompletionStage<SubjectGroupRepository.GroupView> resolve(UUID owner, String ref);
    }

    /** Profile reference resolution in the caller's namespace. */
    public interface Profiles {
        CompletionStage<PermissionProfileRepository.ProfileView> resolve(UUID owner, String ref);
    }

    /** Resolves the affected land for the sender; empty means fail closed. */
    @FunctionalInterface
    public interface LandResolver {
        Optional<LandId> resolve(CommandSender sender);
    }

    /**
     * Resolves the affected subland within the land for the sender's block
     * position; empty means fail closed.
     */
    @FunctionalInterface
    public interface SublandResolver {
        Optional<SubLandId> resolve(CommandSender sender, LandId landId);
    }

    /** Production seam over the real binding service. */
    public static Bindings serviceBindings(LandBindingService service) {
        Objects.requireNonNull(service, "service");
        return new Bindings() {
            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject,
                    UUID profileId) {
                return service.bindLand(owner, landId, subject, profileId);
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject) {
                return service.unbindLand(owner, landId, subject);
            }

            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject,
                    UUID profileId) {
                return service.bindSubland(owner, sublandId, subject, profileId);
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject) {
                return service.unbindSubland(owner, sublandId, subject);
            }
        };
    }

    /** Production seam over the real group service. */
    public static Groups serviceGroups(SubjectGroupService service) {
        Objects.requireNonNull(service, "service");
        return service::resolve;
    }

    /** Production seam over the real profile service. */
    public static Profiles serviceProfiles(PermissionProfileService service) {
        Objects.requireNonNull(service, "service");
        return service::resolve;
    }

    private final Bindings bindings;
    private final Groups groups;
    private final Profiles profiles;
    private final LandResolver lands;
    private final SublandResolver sublands;
    private final Function<String, Optional<UUID>> playerIds;

    /**
     * @param bindings durable operations; null replies unavailable without side effects
     * @param groups group reference resolution; null fails every group subject closed
     * @param profiles profile reference resolution; null fails every verb closed
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param sublands affected-subland resolver for {@code --in-subland};
     *                 null or empty resolves fail closed
     * @param playerIds player lookup (UUID text or online exact name); null
     *                  resolves every name fail closed
     */
    public BindingCommandHandler(Bindings bindings, Groups groups, Profiles profiles,
            LandResolver lands, SublandResolver sublands,
            Function<String, Optional<UUID>> playerIds) {
        this.bindings = bindings;
        this.groups = groups;
        this.profiles = profiles;
        this.lands = lands;
        this.sublands = sublands;
        this.playerIds = playerIds;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.binding.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.failed"));
            return;
        }
        if (actor == null) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.failed"));
            return;
        }
        String verb = verbOf(args);
        if (verb == null) {
            sink.reply("command.land.binding.usage", Map.of());
            return;
        }
        if (bindings == null || (verb.equals("bind") && profiles == null)) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.unavailable"));
            return;
        }
        boolean inSubland = hasSublandFlag(args);
        String kindRaw = argAt(args, 2);
        String subjectRaw = argAt(args, 3);
        String profileRaw = verb.equals("bind") ? argAt(args, 4) : null;
        if (kindRaw == null || subjectRaw == null || (verb.equals("bind") && profileRaw == null)) {
            sink.reply("command.land.binding.usage", Map.of());
            return;
        }
        String kind = kindRaw.strip().toLowerCase(Locale.ROOT);
        if (!kind.equals("player") && !kind.equals("group")) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.invalid"));
            return;
        }
        if (kind.equals("group") && groups == null) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.unavailable"));
            return;
        }
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.unknown_land"));
            return;
        }
        LandId landId = land.get();
        Optional<SubLandId> subland = Optional.empty();
        if (inSubland) {
            subland = resolveSubland(sender, landId);
            if (subland.isEmpty() || subland.get() == null) {
                sink.reply("command.land.binding.failed",
                        Map.of("reason", "binding.unknown_land"));
                return;
            }
        }
        Optional<SubLandId> scope = subland;
        resolveSubject(actor, kind, subjectRaw.strip()).whenComplete((subject, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.binding.failed",
                            Map.of("reason", reasonOf(failure)));
                    return;
                }
                if (subject == null) {
                    sink.reply("command.land.binding.failed",
                            Map.of("reason", "binding.failed"));
                    return;
                }
                if (verb.equals("bind")) {
                    runBind(actor, landId, scope.orElse(null), subject,
                            profileRaw.strip(), sink);
                } else {
                    runUnbind(actor, landId, scope.orElse(null), subject, sink);
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void runBind(UUID actor, LandId landId, SubLandId sublandId,
            LandBindingRepository.Subject subject, String profileRef, ReplySink sink) {
        CompletionStage<PermissionProfileRepository.ProfileView> resolved;
        try {
            resolved = profiles.resolve(actor, profileRef);
        } catch (RuntimeException failure) {
            sink.reply("command.land.binding.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (resolved == null) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.failed"));
            return;
        }
        resolved.whenComplete((view, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.binding.failed",
                            Map.of("reason", reasonOf(failure)));
                    return;
                }
                if (view == null) {
                    sink.reply("command.land.binding.failed", Map.of("reason", "binding.failed"));
                    return;
                }
                CompletionStage<LandBindingRepository.BindOutcome> stage;
                try {
                    stage = sublandId == null
                            ? bindings.bindLand(actor, landId, subject, view.id())
                            : bindings.bindSubland(actor, sublandId, subject, view.id());
                } catch (RuntimeException mutationFailure) {
                    sink.reply("command.land.binding.failed",
                            Map.of("reason", reasonOf(mutationFailure)));
                    return;
                }
                if (stage == null) {
                    sink.reply("command.land.binding.failed", Map.of("reason", "binding.failed"));
                    return;
                }
                stage.whenComplete((outcome, mutationFailure) -> {
                    try {
                        if (mutationFailure != null) {
                            sink.reply("command.land.binding.failed",
                                    Map.of("reason", reasonOf(mutationFailure)));
                        } else if (outcome == null) {
                            sink.reply("command.land.binding.failed",
                                    Map.of("reason", "binding.failed"));
                        } else if (outcome.replaced()) {
                            sink.reply("command.land.binding.updated", Map.of());
                        } else {
                            sink.reply("command.land.binding.created", Map.of());
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

    private void runUnbind(UUID actor, LandId landId, SubLandId sublandId,
            LandBindingRepository.Subject subject, ReplySink sink) {
        CompletionStage<? extends LandBindingRepository.UnbindOutcome> stage;
        try {
            stage = sublandId == null
                    ? bindings.unbindLand(actor, landId, subject)
                    : bindings.unbindSubland(actor, sublandId, subject);
        } catch (RuntimeException failure) {
            sink.reply("command.land.binding.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.binding.failed", Map.of("reason", "binding.failed"));
            return;
        }
        stage.whenComplete((outcome, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.binding.failed",
                            Map.of("reason", reasonOf(failure)));
                } else {
                    sink.reply("command.land.binding.deleted", Map.of());
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private CompletionStage<LandBindingRepository.Subject> resolveSubject(
            UUID actor, String kind, String raw) {
        if (kind.equals("player")) {
            Optional<UUID> target = resolvePlayer(raw);
            if (target.isEmpty() || target.get() == null) {
                return CompletableFuture.failedFuture(
                        new BindingRejectedException("binding.invalid"));
            }
            return CompletableFuture.completedFuture(
                    new LandBindingRepository.Subject(
                            LandBindingRepository.SubjectKind.PLAYER, target.get()));
        }
        CompletionStage<SubjectGroupRepository.GroupView> resolved;
        try {
            resolved = groups.resolve(actor, raw);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (resolved == null) {
            return CompletableFuture.failedFuture(
                    new BindingRejectedException("binding.failed"));
        }
        return resolved.thenApply(view -> {
            if (view == null) {
                throw new BindingRejectedException("binding.failed");
            }
            return new LandBindingRepository.Subject(
                    LandBindingRepository.SubjectKind.GROUP, view.id());
        });
    }

    private Optional<UUID> resolvePlayer(String raw) {
        String stripped = raw.strip();
        if (stripped.isEmpty()
                || stripped.equalsIgnoreCase("EVERYONE")
                || stripped.equals("*")
                || stripped.toLowerCase(Locale.ROOT).startsWith("group(")) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(stripped));
        } catch (IllegalArgumentException notUuid) {
            // Not a UUID: only an online exact name may resolve it, and only
            // through the injected lookup (no network query here).
        }
        if (playerIds == null) {
            return Optional.empty();
        }
        try {
            Optional<UUID> found = playerIds.apply(stripped);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Optional<LandId> resolveLand(CommandSender sender) {
        if (lands == null) {
            return Optional.empty();
        }
        try {
            Optional<LandId> found = lands.resolve(sender);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Optional<SubLandId> resolveSubland(CommandSender sender, LandId landId) {
        if (sublands == null) {
            return Optional.empty();
        }
        try {
            Optional<SubLandId> found = sublands.resolve(sender, landId);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static String verbOf(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        String raw = args[1];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String verb = raw.strip().toLowerCase(Locale.ROOT);
        return verb.equals("bind") || verb.equals("unbind") ? verb : null;
    }

    private static String argAt(String[] args, int index) {
        if (args == null || args.length <= index) {
            return null;
        }
        String raw = args[index];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // The subland flag is a scope marker, never a positional value: a
        // verb missing its subject or profile still reports usage instead of
        // binding the flag text as an id.
        if (raw.strip().equalsIgnoreCase("--in-subland")) {
            return null;
        }
        return raw.strip();
    }

    private static boolean hasSublandFlag(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (arg != null && arg.strip().equalsIgnoreCase("--in-subland")) {
                return true;
            }
        }
        return false;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current == null ? failure : current;
    }

    private static String reasonOf(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof BindingRejectedException rejected) {
            return rejected.reason();
        }
        if (cause instanceof GroupRejectedException groupRejected) {
            return groupRejected.reason();
        }
        if (cause instanceof ProfileRejectedException profileRejected) {
            return profileRejected.reason();
        }
        return "binding.failed";
    }
}
