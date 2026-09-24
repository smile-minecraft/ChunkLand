package com.smile.chunkland.command;

import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.subland.DepthExtendConfirmationRequired;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandEntryLookup;
import com.smile.chunkland.subland.SubLandMutationRunner;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land subland <create|update|delete> <generation> <revision> [name]}
 * handler: the chat entry point into the SubLand mutation runner.
 *
 * <p>Authorisation is owned by the {@code /land} dispatcher, which runs the
 * shared management gate for {@code MANAGE_SUBLAND} before this handler is
 * ever invoked; this handler never calls the gate and never accepts an
 * allow predicate. It only validates the confirmation triple
 * ({@code sessionGeneration + selectionRevision + parent structureRevision})
 * against the sender's own live session through
 * {@link SubLandConfirmService} and builds the precise {@link Cuboid} from
 * the session's own points, then enters the runner exactly once per accepted
 * token. Any mismatch replies stale without touching the runner, and the
 * runner releases the single-use mark on every non-durable outcome.
 *
 * <p>Threading: everything Bukkit-facing happens synchronously on the calling
 * thread; only the terminal reply runs on mutation completion, using values
 * captured up front.
 */
public final class SubLandCommandHandler implements LandCommand.Handler {

    private final SelectionSessionManager selections;
    private final SubLandConfirmService confirm;
    private final SubLandMutationRunner runner;
    private final SelectionStructureRevisionLookup structures;
    private final SubLandEntryLookup entries;

    public SubLandCommandHandler(
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandMutationRunner runner,
            SelectionStructureRevisionLookup structures) {
        this(selections, confirm, runner, structures, SubLandEntryLookup.unavailable());
    }

    public SubLandCommandHandler(
            SelectionSessionManager selections,
            SubLandConfirmService confirm,
            SubLandMutationRunner runner,
            SelectionStructureRevisionLookup structures,
            SubLandEntryLookup entries) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.confirm = Objects.requireNonNull(confirm, "confirm");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.structures = Objects.requireNonNull(structures, "structures");
        this.entries = Objects.requireNonNull(entries, "entries");
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.subland.console", Map.of());
            return;
        }
        UUID actor = player.getUniqueId();
        String op = operationOf(args);
        if ("select".equals(op)) {
            select(player, actor, args, sink);
            return;
        }
        if ("extend".equals(op)) {
            extend(actor, args, sink);
            return;
        }
        if (op == null) {
            sink.reply("command.land.subland.usage", Map.of());
            return;
        }
        Long generation = parseNonNegative(args, 2);
        Long revision = parseNonNegative(args, 3);
        if (generation == null || revision == null) {
            preview(actor, op, args, sink);
            return;
        }
        if (needsName(op) && displayNameOf(args) == null) {
            sink.reply("command.land.subland.usage", Map.of());
            return;
        }
        Optional<SubLandConfirmService.Accepted> accepted;
        try {
            accepted = confirm.accept(actor, generation, revision, selections, structures);
        } catch (RuntimeException failure) {
            accepted = Optional.empty();
        }
        if (accepted.isEmpty()) {
            sink.reply(staleOrMissingKey(actor), Map.of());
            return;
        }
        SubLandConfirmService.Accepted settled = accepted.get();
        switch (op) {
            case "create" -> create(actor, settled, displayNameOf(args), sink);
            case "update" -> update(actor, settled, displayNameOf(args), sink);
            case "delete" -> delete(actor, settled, sink);
            default -> sink.reply("command.land.subland.usage", Map.of());
        }
    }

    private void select(Player player, UUID actor, String[] args, ReplySink sink) {
        String requestedName = nameFrom(args, 2);
        final Location location;
        try {
            location = player.getLocation();
        } catch (RuntimeException failure) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        if (location == null) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        final UUID worldId;
        final int blockX;
        final int blockZ;
        try {
            World world = location.getWorld();
            if (world == null || world.getUID() == null) {
                sink.reply("command.land.subland.no_selection", Map.of());
                return;
            }
            worldId = world.getUID();
            blockX = location.getBlockX();
            blockZ = location.getBlockZ();
        } catch (RuntimeException failure) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        final Optional<LandSnapshot> parent;
        try {
            parent = entries.landAt(worldId, blockX, blockZ);
        } catch (RuntimeException failure) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        if (parent == null || parent.isEmpty()
                || !(parent.get().ownerRef() instanceof com.smile.chunkland.api.land.OwnerRef.PlayerOwnerRef owner)
                || !owner.uuid().equals(actor)) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        LandSnapshot land = parent.get();
        Optional<com.smile.chunkland.api.land.SubLandId> target = Optional.empty();
        if (requestedName != null) {
            int matches = 0;
            com.smile.chunkland.api.land.SubLandId matched = null;
            for (SubLandSnapshot sub : land.subLands()) {
                if (sub.name().equals(requestedName)) {
                    matches++;
                    matched = sub.id();
                }
            }
            if (matches != 1) {
                sink.reply("command.land.subland.no_selection", Map.of());
                return;
            }
            target = Optional.of(matched);
        }
        SelectionSession initial;
        try {
            initial = SelectionSession.initial(
                    actor, worldId, com.smile.chunkland.selection.SelectionMode.CREATE_SUBLAND,
                    Optional.of(land.id()), target, Optional.empty(), Optional.empty(),
                    land.structureRevision(), java.time.Instant.now());
        } catch (RuntimeException failure) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        try {
            SelectionSession started = selections.start(initial);
            sink.reply("command.land.subland.selected", Map.of(
                    "generation", started.sessionGeneration(),
                    "revision", started.selectionRevision(),
                    "land_name", land.displayName()));
        } catch (RuntimeException failure) {
            sink.reply("command.land.subland.no_selection", Map.of());
        }
    }

    private void preview(UUID actor, String op, String[] args, ReplySink sink) {
        if (needsName(op) && nameFrom(args, 2) == null) {
            sink.reply("command.land.subland.usage", Map.of());
            return;
        }
        Optional<SelectionSession> live;
        try {
            live = selections.sessionFor(actor);
        } catch (RuntimeException failure) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        if (live == null || live.isEmpty()) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        SelectionSession session = live.get();
        String name = "delete".equals(op) ? op : nameFrom(args, 2);
        String commandName = "delete".equals(op) ? null : name;
        String command = "/land subland " + op + " " + session.sessionGeneration()
                + " " + session.selectionRevision()
                + (commandName == null || commandName.isBlank() ? "" : " " + commandName);
        sink.reply("command.land.subland.preview", Map.of(
                "action", op,
                "generation", session.sessionGeneration(),
                "revision", session.selectionRevision(),
                "land_name", name == null ? "" : name,
                "value", command));
    }

    private void extend(UUID actor, String[] args, ReplySink sink) {
        Long generation = parseNonNegative(args, 2);
        Long revision = parseNonNegative(args, 3);
        if (generation == null || revision == null) {
            sink.reply("command.land.subland.usage", Map.of());
            return;
        }
        Optional<SelectionSession> live;
        try {
            live = selections.sessionFor(actor);
        } catch (RuntimeException failure) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        if (live == null || live.isEmpty()
                || live.get().pointA().isEmpty() || live.get().pointB().isEmpty()) {
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        Optional<SubLandConfirmService.Accepted> accepted;
        try {
            accepted = confirm.accept(actor, generation, revision, selections, structures);
        } catch (RuntimeException failure) {
            accepted = Optional.empty();
        }
        if (accepted.isEmpty()) {
            sink.reply(staleOrMissingKey(actor), Map.of());
            return;
        }
        SubLandConfirmService.Accepted settled = accepted.get();
        runner.extend(actor, settled).whenComplete((result, failure) -> {
            try {
                if (failure == null) {
                    sink.reply("command.land.subland.extended", Map.of("count", result.chunkCount()));
                } else {
                    sink.reply("command.land.subland.failed", failureVars(failure, settled));
                }
            } catch (RuntimeException ignored) {
                // Terminal reply path: never let a sink failure escape onto persistence threads.
            }
        });
    }

    private void create(
            UUID actor,
            SubLandConfirmService.Accepted accepted,
            String name,
            ReplySink sink) {
        Cuboid cuboid = cuboidOf(accepted.session());
        if (cuboid == null) {
            confirm.releaseIfNotSuccess(actor, accepted);
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        var candidate = new com.smile.chunkland.api.land.SubLandSnapshot(
                new com.smile.chunkland.api.land.SubLandId(UUID.randomUUID()),
                accepted.parentId(), name, cuboid, accepted.session().worldId());
        runner.create(actor, accepted, candidate).whenComplete((created, failure) -> {
            try {
                if (failure != null) {
                    sink.reply(failureKey(failure),
                            failureVars(failure, accepted));
                } else {
                    sink.reply("command.land.subland.created",
                            Map.of("name", String.valueOf(created.name())));
                }
            } catch (RuntimeException ignored) {
                // Terminal reply path: never let a sink failure escape onto mutation threads.
            }
        });
    }

    private void update(
            UUID actor,
            SubLandConfirmService.Accepted accepted,
            String name,
            ReplySink sink) {
        if (accepted.targetSubLandId().isEmpty()) {
            confirm.releaseIfNotSuccess(actor, accepted);
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        Cuboid cuboid = cuboidOf(accepted.session());
        if (cuboid == null) {
            confirm.releaseIfNotSuccess(actor, accepted);
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        var candidate = new com.smile.chunkland.api.land.SubLandSnapshot(
                accepted.targetSubLandId().get(),
                accepted.parentId(), name, cuboid, accepted.session().worldId());
        runner.update(actor, accepted, candidate).whenComplete((updated, failure) -> {
            try {
                if (failure != null) {
                    sink.reply(failureKey(failure),
                            failureVars(failure, accepted));
                } else {
                    sink.reply("command.land.subland.updated",
                            Map.of("name", String.valueOf(updated.name())));
                }
            } catch (RuntimeException ignored) {
                // Terminal reply path: never let a sink failure escape onto mutation threads.
            }
        });
    }

    private void delete(
            UUID actor,
            SubLandConfirmService.Accepted accepted,
            ReplySink sink) {
        if (accepted.targetSubLandId().isEmpty()) {
            confirm.releaseIfNotSuccess(actor, accepted);
            sink.reply("command.land.subland.no_selection", Map.of());
            return;
        }
        runner.delete(actor, accepted, accepted.targetSubLandId().get())
                .whenComplete((ignored, failure) -> {
                    try {
                        if (failure != null) {
                            sink.reply(failureKey(failure),
                                    failureVars(failure, accepted));
                        } else {
                            sink.reply("command.land.subland.deleted", Map.of());
                        }
                    } catch (RuntimeException ignoredReply) {
                        // Terminal reply path: never let a sink failure escape onto mutation threads.
                    }
                });
    }

    private String staleOrMissingKey(UUID actor) {
        try {
            if (selections.sessionFor(actor).isEmpty()) {
                return "command.land.subland.no_selection";
            }
        } catch (RuntimeException ignored) {
            return "command.land.subland.no_selection";
        }
        return "command.land.subland.stale";
    }

    private static String failureKey(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException completed
                && completed.getCause() != null ? completed.getCause() : failure;
        if (cause instanceof SubLandMutationRunner.RuntimeRebuildPendingException) {
            return "command.land.subland.degraded";
        }
        if (cause instanceof DepthExtendConfirmationRequired) {
            return "command.land.subland.confirm_depth";
        }
        if (cause instanceof SubLandMutationRunner.ConfirmRejectedException) {
            return "command.land.subland.stale";
        }
        if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException) {
            return "command.land.subland.failed";
        }
        return "command.land.subland.failed";
    }

    private static Map<String, Object> failureVars(
            Throwable failure, SubLandConfirmService.Accepted accepted) {
        String reason = reasonOf(failure);
        Throwable cause = failure instanceof java.util.concurrent.CompletionException completed
                && completed.getCause() != null ? completed.getCause() : failure;
        if (cause instanceof DepthExtendConfirmationRequired && accepted != null) {
            return Map.of(
                    "reason", reason,
                    "value", "/land subland extend "
                            + accepted.session().sessionGeneration() + " "
                            + accepted.session().selectionRevision());
        }
        return Map.of("reason", reason);
    }

    private static String reasonOf(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException completed
                && completed.getCause() != null ? completed.getCause() : failure;
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank() ? "subland.failed" : message;
    }

    /** Precise cuboid from the session's own points, or null when unusable. */
    static Cuboid cuboidOf(SelectionSession session) {
        Optional<SelectionPoint> a = session.pointA();
        Optional<SelectionPoint> b = session.pointB();
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        SelectionPoint first = a.get();
        SelectionPoint second = b.get();
        try {
            return new Cuboid(
                    Math.min(first.blockX(), second.blockX()),
                    Math.min(first.blockY(), second.blockY()),
                    Math.min(first.blockZ(), second.blockZ()),
                    Math.max(first.blockX(), second.blockX()),
                    Math.max(first.blockY(), second.blockY()),
                    Math.max(first.blockZ(), second.blockZ()));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean needsName(String op) {
        return op.equals("create") || op.equals("update");
    }

    private static String operationOf(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        String raw = args[1];
        if (raw == null) {
            return null;
        }
        String op = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (op) {
            case "create", "update", "delete", "select", "extend" -> op;
            default -> null;
        };
    }

    private static Long parseNonNegative(String[] args, int index) {
        if (args == null || args.length <= index) {
            return null;
        }
        String raw = args[index];
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (parsed < 0) {
            return null;
        }
        return parsed;
    }

    private static String displayNameOf(String[] args) {
        return nameFrom(args, 4);
    }

    private static String nameFrom(String[] args, int start) {
        if (args == null || args.length <= start) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            String part = args[i];
            if (part == null || part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(part);
        }
        String candidate = joined.toString();
        return candidate.isBlank() ? null : candidate;
    }
}
