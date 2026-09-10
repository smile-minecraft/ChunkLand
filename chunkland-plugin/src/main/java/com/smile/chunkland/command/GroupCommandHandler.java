package com.smile.chunkland.command;

import com.smile.chunkland.group.SubjectGroupService;
import com.smile.chunkland.persistence.GroupDeleteRestrictedException;
import com.smile.chunkland.persistence.GroupRejectedException;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land group <create|list|add|remove|delete>} handler.
 *
 * <p>Every subcommand acts in the sender's own owner namespace: only players
 * reach the mutations, and the group reference (UUID or owner-scoped name)
 * plus the durable owner check inside the transaction keep one owner from
 * reading or mutating another owner's groups. The member target resolves
 * only through the injected lookup — a UUID string or an online exact name —
 * so unknown or offline names fail closed without any network query, and
 * reserved names ({@code EVERYONE}, {@code *}, group syntax) never resolve.
 * There is no land context here: groups live outside lands and outlive them.
 *
 * <p>Deletes are two-step by flag: a plain {@code delete} is refused while
 * any land or subland binding references the group (naming the blockers);
 * {@code delete --force} removes those references in the same transaction
 * and names them in the reply, so nothing dangles silently.
 *
 * <p>Threading: everything sender-facing (sender type, UUID, arg parsing and
 * target resolution) happens synchronously on the calling thread; only the
 * reply runs on mutation completion, using values captured up front, so the
 * callback never touches server objects.
 */
public final class GroupCommandHandler implements LandCommand.Handler {

    /** Durable group operations; kept as a seam so tests observe the call. */
    public interface Groups {
        CompletionStage<SubjectGroupRepository.GroupView> create(UUID owner, String name);

        CompletionStage<List<SubjectGroupRepository.GroupView>> list(UUID owner);

        CompletionStage<SubjectGroupRepository.GroupView> resolve(UUID owner, String ref);

        CompletionStage<SubjectGroupRepository.MembershipOutcome> addMember(
                UUID owner, UUID groupId, UUID member);

        CompletionStage<SubjectGroupRepository.MembershipOutcome> removeMember(
                UUID owner, UUID groupId, UUID member);

        CompletionStage<Void> delete(UUID owner, UUID groupId);

        CompletionStage<SubjectGroupRepository.ForceDeleteOutcome> forceDelete(
                UUID owner, UUID groupId);
    }

    private final Groups groups;
    private final Function<String, Optional<UUID>> playerIds;

    /**
     * @param groups durable operations; null replies unavailable without side effects
     * @param playerIds member lookup (UUID text or online exact name); null
     *                  resolves every name fail closed
     */
    public GroupCommandHandler(Groups groups, Function<String, Optional<UUID>> playerIds) {
        this.groups = groups;
        this.playerIds = playerIds;
    }

    /** Production seam over the real service. */
    public static Groups serviceGroups(SubjectGroupService service) {
        Objects.requireNonNull(service, "service");
        return new Groups() {
            @Override
            public CompletionStage<SubjectGroupRepository.GroupView> create(UUID owner, String name) {
                return service.create(owner, name);
            }

            @Override
            public CompletionStage<List<SubjectGroupRepository.GroupView>> list(UUID owner) {
                return service.list(owner);
            }

            @Override
            public CompletionStage<SubjectGroupRepository.GroupView> resolve(UUID owner, String ref) {
                return service.resolve(owner, ref);
            }

            @Override
            public CompletionStage<SubjectGroupRepository.MembershipOutcome> addMember(
                    UUID owner, UUID groupId, UUID member) {
                return service.addMember(owner, groupId, member);
            }

            @Override
            public CompletionStage<SubjectGroupRepository.MembershipOutcome> removeMember(
                    UUID owner, UUID groupId, UUID member) {
                return service.removeMember(owner, groupId, member);
            }

            @Override
            public CompletionStage<Void> delete(UUID owner, UUID groupId) {
                return service.delete(owner, groupId);
            }

            @Override
            public CompletionStage<SubjectGroupRepository.ForceDeleteOutcome> forceDelete(
                    UUID owner, UUID groupId) {
                return service.forceDelete(owner, groupId);
            }
        };
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.group.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        if (actor == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        String action = actionOf(args);
        if (action == null) {
            sink.reply("command.land.group.usage", Map.of());
            return;
        }
        if (groups == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.unavailable"));
            return;
        }
        try {
            switch (action) {
                case "create" -> handleCreate(actor, args, sink);
                case "list" -> handleList(actor, sink);
                case "add" -> handleMember(actor, args, sink, true);
                case "remove" -> handleMember(actor, args, sink, false);
                case "delete" -> handleDelete(actor, args, sink);
                default -> sink.reply("command.land.group.usage", Map.of());
            }
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
        }
    }

    private void handleCreate(UUID actor, String[] args, ReplySink sink) {
        String name = joinTail(args, 2);
        if (name == null) {
            sink.reply("command.land.group.usage", Map.of());
            return;
        }
        CompletionStage<SubjectGroupRepository.GroupView> stage;
        try {
            stage = groups.create(actor, name);
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        stage.whenComplete((view, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.group.failed",
                            Map.of("reason", reasonOf(failure)));
                } else if (view == null) {
                    sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
                } else {
                    sink.reply("command.land.group.created",
                            Map.of("group", view.displayName()));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void handleList(UUID actor, ReplySink sink) {
        CompletionStage<List<SubjectGroupRepository.GroupView>> stage;
        try {
            stage = groups.list(actor);
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        stage.whenComplete((views, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.group.failed",
                            Map.of("reason", reasonOf(failure)));
                } else if (views == null) {
                    sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
                } else if (views.isEmpty()) {
                    sink.reply("command.land.group.listed_empty", Map.of());
                } else {
                    StringBuilder joined = new StringBuilder();
                    for (SubjectGroupRepository.GroupView view : views) {
                        if (view == null) {
                            continue;
                        }
                        if (joined.length() > 0) {
                            joined.append(", ");
                        }
                        joined.append(view.displayName());
                    }
                    sink.reply("command.land.group.listed",
                            Map.of("groups", joined.toString()));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void handleMember(UUID actor, String[] args, ReplySink sink, boolean add) {
        String base = add ? "added" : "removed";
        String groupRef = argAt(args, 2);
        String rawTarget = argAt(args, 3);
        if (groupRef == null || rawTarget == null) {
            sink.reply("command.land.group.usage", Map.of());
            return;
        }
        Optional<UUID> target = resolveTarget(rawTarget);
        if (target.isEmpty() || target.get() == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.unknown_target"));
            return;
        }
        CompletionStage<SubjectGroupRepository.GroupView> resolved;
        try {
            resolved = groups.resolve(actor, groupRef);
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (resolved == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        UUID targetId = target.get();
        String targetLabel = rawTarget.strip();
        resolved.whenComplete((view, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.group.failed",
                            Map.of("reason", reasonOf(failure)));
                    return;
                }
                if (view == null) {
                    sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
                    return;
                }
                CompletionStage<SubjectGroupRepository.MembershipOutcome> stage;
                try {
                    stage = add
                            ? groups.addMember(actor, view.id(), targetId)
                            : groups.removeMember(actor, view.id(), targetId);
                } catch (RuntimeException mutationFailure) {
                    sink.reply("command.land.group.failed",
                            Map.of("reason", reasonOf(mutationFailure)));
                    return;
                }
                if (stage == null) {
                    sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
                    return;
                }
                String groupLabel = view.displayName();
                stage.whenComplete((outcome, mutationFailure) -> {
                    try {
                        if (mutationFailure != null) {
                            sink.reply("command.land.group.failed",
                                    Map.of("reason", reasonOf(mutationFailure)));
                        } else {
                            sink.reply("command.land.group." + base,
                                    Map.of("group", groupLabel, "player", targetLabel));
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
        String groupRef = argAt(args, 2);
        if (groupRef == null) {
            sink.reply("command.land.group.usage", Map.of());
            return;
        }
        boolean force = hasForceFlag(args);
        CompletionStage<SubjectGroupRepository.GroupView> resolved;
        try {
            resolved = groups.resolve(actor, groupRef);
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (resolved == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        resolved.whenComplete((view, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.group.failed",
                            Map.of("reason", reasonOf(failure)));
                    return;
                }
                if (view == null) {
                    sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
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

    private void runDelete(UUID actor, SubjectGroupRepository.GroupView view, ReplySink sink) {
        CompletionStage<Void> stage;
        try {
            stage = groups.delete(actor, view.id());
        } catch (GroupDeleteRestrictedException restricted) {
            sink.reply("command.land.group.failed",
                    Map.of("reason", "group.restricted",
                            "affected", describeAffected(restricted.affected())));
            return;
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        String groupLabel = view.displayName();
        stage.whenComplete((ignored, failure) -> {
            try {
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof GroupDeleteRestrictedException restricted) {
                        sink.reply("command.land.group.failed",
                                Map.of("reason", "group.restricted",
                                        "affected", describeAffected(restricted.affected())));
                    } else {
                        sink.reply("command.land.group.failed",
                                Map.of("reason", reasonOf(failure)));
                    }
                } else {
                    sink.reply("command.land.group.deleted", Map.of("group", groupLabel));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private void runForceDelete(UUID actor, SubjectGroupRepository.GroupView view, ReplySink sink) {
        CompletionStage<SubjectGroupRepository.ForceDeleteOutcome> stage;
        try {
            stage = groups.forceDelete(actor, view.id());
        } catch (RuntimeException failure) {
            sink.reply("command.land.group.failed", Map.of("reason", reasonOf(failure)));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
            return;
        }
        String groupLabel = view.displayName();
        stage.whenComplete((outcome, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.group.failed",
                            Map.of("reason", reasonOf(failure)));
                } else if (outcome == null) {
                    sink.reply("command.land.group.failed", Map.of("reason", "group.failed"));
                } else {
                    sink.reply("command.land.group.force_deleted",
                            Map.of("group", groupLabel,
                                    "affected", describeAffected(outcome.affected())));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    static String describeAffected(
            List<SubjectGroupRepository.AffectedBinding> affected) {
        if (affected == null || affected.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (SubjectGroupRepository.AffectedBinding binding : affected) {
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
            if (binding.profileId() != null) {
                out.append(" profile ").append(binding.profileId());
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private Optional<UUID> resolveTarget(String raw) {
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
        if (cause instanceof GroupDeleteRestrictedException) {
            return "group.restricted";
        }
        if (cause instanceof GroupRejectedException rejected) {
            return rejected.reason();
        }
        return "group.failed";
    }
}
