package com.smile.chunkland.command;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.ProfileDeleteRestrictedException;
import com.smile.chunkland.persistence.ProfileRejectedException;
import com.smile.chunkland.profile.PermissionProfileService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land profile <create|list|set|delete>} handler.
 *
 * <p>Every subcommand acts in the sender's own owner namespace: only players
 * reach the mutations, and the profile reference (UUID or owner-scoped name)
 * plus the durable owner check inside the transaction keep one owner from
 * reading or mutating another owner's profiles. Permission and state text
 * parse fail-closed before any lookup, so unknown names, reserved words and
 * land-rule actions never touch storage. The fixed {@code direct} internal
 * profile resolves but every mutation on it is refused with
 * {@code profile.reserved}, so generic management can never pollute direct
 * trust bindings. There is no land context here: profiles live outside lands
 * and outlive them.
 *
 * <p>Deletes are two-step by flag: a plain {@code delete} is refused while
 * any land or subland binding references the profile (naming the blockers);
 * {@code delete --force} removes those references in the same transaction
 * and names them in the reply, so nothing dangles silently.
 *
 * <p>Threading: everything sender-facing (sender type, UUID, arg parsing)
 * happens synchronously on the calling thread; only the reply runs on
 * mutation completion, using values captured up front, so the callback never
 * touches server objects.
 */
public final class ProfileCommandHandler implements LandCommand.Handler {

    /** Durable profile operations; kept as a seam so tests observe the call. */
    public interface Profiles {
        CompletionStage<PermissionProfileRepository.ProfileView> create(UUID owner, String name);

        CompletionStage<List<PermissionProfileRepository.ProfileView>> list(UUID owner);

        CompletionStage<PermissionProfileRepository.ProfileView> resolve(UUID owner, String ref);

        CompletionStage<PermissionProfileRepository.EntryOutcome> setEntry(
                UUID owner, UUID profileId, ProtectionActionType action, PermissionState state);

        CompletionStage<Void> delete(UUID owner, UUID profileId);

        CompletionStage<PermissionProfileRepository.ForceDeleteOutcome> forceDelete(
                UUID owner, UUID profileId);
    }

    private final Profiles profiles;

    /**
     * @param profiles durable operations; null replies unavailable without side effects
     */
    public ProfileCommandHandler(Profiles profiles) {
        this.profiles = profiles;
    }

    /** Production seam over the real service. */
    public static Profiles serviceProfiles(PermissionProfileService service) {
        Objects.requireNonNull(service, "service");
        return new Profiles() {
            @Override
            public CompletionStage<PermissionProfileRepository.ProfileView> create(
                    UUID owner, String name) {
                return service.create(owner, name);
            }

            @Override
            public CompletionStage<List<PermissionProfileRepository.ProfileView>> list(
                    UUID owner) {
                return service.list(owner);
            }

            @Override
            public CompletionStage<PermissionProfileRepository.ProfileView> resolve(
                    UUID owner, String ref) {
                return service.resolve(owner, ref);
            }

            @Override
            public CompletionStage<PermissionProfileRepository.EntryOutcome> setEntry(
                    UUID owner, UUID profileId, ProtectionActionType action, PermissionState state) {
                return service.setEntry(owner, profileId.toString(),
                        action.name(), state.name());
            }

            @Override
            public CompletionStage<Void> delete(UUID owner, UUID profileId) {
                return service.delete(owner, profileId.toString());
            }

            @Override
            public CompletionStage<PermissionProfileRepository.ForceDeleteOutcome> forceDelete(
                    UUID owner, UUID profileId) {
                return service.forceDelete(owner, profileId.toString());
            }
        };
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.profile.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        if (actor == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        String action = actionOf(args);
        if (action == null) {
            sink.reply("command.land.profile.usage", Map.of());
            return;
        }
        if (profiles == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.unavailable"));
            return;
        }
        try {
            switch (action) {
                case "create" -> handleCreate(actor, args, sink);
                case "list" -> handleList(actor, sink);
                case "set" -> handleSet(actor, args, sink);
                case "delete" -> handleDelete(actor, args, sink);
                default -> sink.reply("command.land.profile.usage", Map.of());
            }
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
        }
    }

    private void handleCreate(UUID actor, String[] args, ReplySink sink) {
        String name = joinTail(args, 2);
        if (name == null) {
            sink.reply("command.land.profile.usage", Map.of());
            return;
        }
        CompletionStage<PermissionProfileRepository.ProfileView> stage;
        try {
            stage = profiles.create(actor, name);
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        stage.whenComplete((view, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.profile.failed",
                            Map.of("reason", reasonOf(failure)));
                } else if (view == null) {
                    sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
                } else {
                    sink.reply("command.land.profile.created",
                            Map.of("profile", view.displayName()));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void handleList(UUID actor, ReplySink sink) {
        CompletionStage<List<PermissionProfileRepository.ProfileView>> stage;
        try {
            stage = profiles.list(actor);
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        stage.whenComplete((views, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.profile.failed",
                            Map.of("reason", reasonOf(failure)));
                } else if (views == null) {
                    sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
                } else if (views.isEmpty()) {
                    sink.reply("command.land.profile.listed_empty", Map.of());
                } else {
                    StringBuilder joined = new StringBuilder();
                    for (PermissionProfileRepository.ProfileView view : views) {
                        if (view == null) {
                            continue;
                        }
                        if (joined.length() > 0) {
                            joined.append(", ");
                        }
                        joined.append(view.displayName());
                    }
                    sink.reply("command.land.profile.listed",
                            Map.of("profiles", joined.toString()));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void handleSet(UUID actor, String[] args, ReplySink sink) {
        String profileRef = argAt(args, 2);
        String rawAction = argAt(args, 3);
        String rawState = argAt(args, 4);
        if (profileRef == null || rawAction == null || rawState == null) {
            sink.reply("command.land.profile.usage", Map.of());
            return;
        }
        ProtectionActionType action = parseAction(rawAction);
        PermissionState state = parseState(rawState);
        if (action == null) {
            sink.reply("command.land.profile.failed",
                    Map.of("reason", "profile.invalid_permission"));
            return;
        }
        if (state == null) {
            sink.reply("command.land.profile.failed",
                    Map.of("reason", "profile.invalid_state"));
            return;
        }
        CompletionStage<PermissionProfileRepository.ProfileView> resolved;
        try {
            resolved = profiles.resolve(actor, profileRef);
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (resolved == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        ProtectionActionType parsedAction = action;
        PermissionState parsedState = state;
        resolved.whenComplete((view, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.profile.failed",
                            Map.of("reason", reasonOf(failure)));
                    return;
                }
                if (view == null) {
                    sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
                    return;
                }
                CompletionStage<PermissionProfileRepository.EntryOutcome> stage;
                try {
                    stage = profiles.setEntry(actor, view.id(), parsedAction, parsedState);
                } catch (RuntimeException mutationFailure) {
                    sink.reply("command.land.profile.failed",
                            Map.of("reason", reasonOf(mutationFailure)));
                    return;
                }
                if (stage == null) {
                    sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
                    return;
                }
                String profileLabel = view.displayName();
                stage.whenComplete((outcome, mutationFailure) -> {
                    try {
                        if (mutationFailure != null) {
                            sink.reply("command.land.profile.failed",
                                    Map.of("reason", reasonOf(mutationFailure)));
                        } else {
                            sink.reply("command.land.profile.set",
                                    Map.of("profile", profileLabel,
                                            "action", parsedAction.name(),
                                            "state", parsedState.name()));
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

    private void handleDelete(UUID actor, String[] args, ReplySink sink) {
        String profileRef = argAt(args, 2);
        if (profileRef == null) {
            sink.reply("command.land.profile.usage", Map.of());
            return;
        }
        boolean force = hasForceFlag(args);
        CompletionStage<PermissionProfileRepository.ProfileView> resolved;
        try {
            resolved = profiles.resolve(actor, profileRef);
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (resolved == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        resolved.whenComplete((view, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.profile.failed",
                            Map.of("reason", reasonOf(failure)));
                    return;
                }
                if (view == null) {
                    sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
                    return;
                }
                if (force) {
                    runForceDelete(actor, view, sink);
                } else {
                    runDelete(actor, view, sink);
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void runDelete(UUID actor, PermissionProfileRepository.ProfileView view, ReplySink sink) {
        CompletionStage<Void> stage;
        try {
            stage = profiles.delete(actor, view.id());
        } catch (ProfileDeleteRestrictedException restricted) {
            sink.reply("command.land.profile.failed",
                    Map.of("reason", "profile.restricted",
                            "affected", describeAffected(restricted.affected())));
            return;
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        String profileLabel = view.displayName();
        stage.whenComplete((ignored, failure) -> {
            try {
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof ProfileDeleteRestrictedException restricted) {
                        sink.reply("command.land.profile.failed",
                                Map.of("reason", "profile.restricted",
                                        "affected", describeAffected(restricted.affected())));
                    } else {
                        sink.reply("command.land.profile.failed",
                                Map.of("reason", reasonOf(failure)));
                    }
                } else {
                    sink.reply("command.land.profile.deleted", Map.of("profile", profileLabel));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void runForceDelete(UUID actor, PermissionProfileRepository.ProfileView view,
            ReplySink sink) {
        CompletionStage<PermissionProfileRepository.ForceDeleteOutcome> stage;
        try {
            stage = profiles.forceDelete(actor, view.id());
        } catch (RuntimeException failure) {
            sink.reply("command.land.profile.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
            return;
        }
        String profileLabel = view.displayName();
        stage.whenComplete((outcome, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.profile.failed",
                            Map.of("reason", reasonOf(failure)));
                } else if (outcome == null) {
                    sink.reply("command.land.profile.failed", Map.of("reason", "profile.failed"));
                } else {
                    sink.reply("command.land.profile.force_deleted",
                            Map.of("profile", profileLabel,
                                    "affected", describeAffected(outcome.affected())));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    static String describeAffected(
            List<PermissionProfileRepository.AffectedBinding> affected) {
        if (affected == null || affected.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (PermissionProfileRepository.AffectedBinding binding : affected) {
            if (binding == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append("; ");
            }
            if ("SUBLAND".equals(binding.scope())) {
                out.append("subland ").append(binding.sublandId());
                if (binding.landId() != null) {
                    out.append(" of land ").append(binding.landId());
                }
            } else {
                out.append("land ").append(binding.landId());
            }
            if (binding.subjectType() != null && binding.subjectId() != null) {
                out.append(" (").append(binding.subjectType()).append(' ')
                        .append(binding.subjectId()).append(')');
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /**
     * Parse one permission name fail-closed: unknown names, reserved words
     * and land-rule actions all read as {@code null} so the caller replies
     * without any side effect.
     */
    static ProtectionActionType parseAction(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()
                || stripped.equalsIgnoreCase("EVERYONE")
                || stripped.equals("*")) {
            return null;
        }
        ProtectionActionType action;
        try {
            action = ProtectionActionType.valueOf(stripped.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        if (action.decisionSource()
                != com.smile.chunkland.api.permission.DecisionSource.SUBJECT_PERMISSION) {
            return null;
        }
        return action;
    }

    /** Parse one state name fail-closed: anything outside the enum reads as {@code null}. */
    static PermissionState parseState(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        try {
            return PermissionState.valueOf(stripped.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String actionOf(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        String raw = args[1];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    private static String argAt(String[] args, int index) {
        if (args == null || args.length <= index) {
            return null;
        }
        String raw = args[index];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }

    private static String joinTail(String[] args, int from) {
        if (args == null || args.length <= from) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            String part = args[i];
            if (part == null || part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(part.strip());
        }
        String name = joined.toString();
        return name.isEmpty() ? null : name;
    }

    private static boolean hasForceFlag(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (arg != null && arg.strip().equalsIgnoreCase("--force")) {
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
        if (cause instanceof ProfileDeleteRestrictedException) {
            return "profile.restricted";
        }
        if (cause instanceof ProfileRejectedException rejected) {
            return rejected.reason();
        }
        return "profile.failed";
    }
}
