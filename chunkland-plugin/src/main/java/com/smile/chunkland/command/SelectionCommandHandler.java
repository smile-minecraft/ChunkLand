package com.smile.chunkland.command;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.selection.PendingChange;
import com.smile.chunkland.selection.SelectionEditOutcome;
import com.smile.chunkland.selection.SelectionEditReason;
import com.smile.chunkland.selection.SelectionEditService;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land selection} handler: the chat entry point for
 * chunk-edit geometry that never touches the wand buttons.
 *
 * <p>Verbs: {@code chunk} arms single-chunk edits, {@code rectangle} returns
 * to corner clicks, {@code toggle} flips between the two, {@code add} and
 * {@code remove} apply one chunk (the standing chunk, or explicit block
 * coordinates converted with floor division so negative positions land in
 * the right chunk), and {@code info} reports the actual chunk count with
 * the current geometry. Every verb runs the shared {@link
 * SelectionEditService}, so chat edits pass the same range, collision and
 * connectivity checks as wand clicks, and every rejection keeps the session
 * untouched.
 *
 * <p>Switching geometry never rewrites the session: workflow mode, target,
 * chunks and revision all stay as they are, which is why arming chunk edits
 * on a fresh claim cannot turn it into an expansion of an existing land.
 * Sub-land sessions keep their own point-based flow and stay rejected here.
 *
 * <p>Fail-closed contract: console senders, missing sessions, unknown verbs
 * and unparsable coordinates reply without touching selection state; a
 * missing edit service or an unreadable standing position fails the single
 * verb closed the same way.
 */
public final class SelectionCommandHandler implements LandCommand.Handler {

    /** Standing-chunk source; empty or throwing means the position is unknown. */
    @FunctionalInterface
    public interface StandingChunkLookup {
        Optional<ChunkKey> standingChunkOf(Player player);
    }

    /** Verbs offered after {@code /land selection}, in declaration order. */
    public static final java.util.List<String> VERBS =
            java.util.List.of("chunk", "rectangle", "toggle", "add", "remove", "info");

    private final SelectionSessionManager selections;
    private final Supplier<SelectionEditService> edits;
    private final StandingChunkLookup standing;
    private final BedrockSelectionFormHandler bedrockForms;

    public SelectionCommandHandler(SelectionSessionManager selections,
            Supplier<SelectionEditService> edits,
            StandingChunkLookup standing) {
        this(selections, edits, standing, null);
    }

    /**
     * @param bedrockForms Bedrock Simple-menu branch; null keeps the chat
     *        path for every sender. When present, a Bedrock player takes the
     *        form path after the shared pre-checks; Java senders are
     *        unaffected.
     */
    public SelectionCommandHandler(SelectionSessionManager selections,
            Supplier<SelectionEditService> edits,
            StandingChunkLookup standing,
            BedrockSelectionFormHandler bedrockForms) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.edits = edits;
        this.standing = standing;
        this.bedrockForms = bedrockForms;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.selection.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = actorOf(player);
        } catch (RuntimeException failure) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        if (actor == null) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        SelectionSession session;
        try {
            Optional<SelectionSession> current = selections.sessionFor(actor);
            if (current == null || current.isEmpty()) {
                sink.reply("command.land.selection.no_selection", Map.of());
                return;
            }
            session = current.get();
        } catch (RuntimeException failure) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        if (bedrockForms != null) {
            boolean handled;
            try {
                handled = bedrockForms.handle(player, session, sink);
            } catch (RuntimeException failure) {
                sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
                return;
            }
            if (handled) {
                return;
            }
        }
        String verb = verbOf(args);
        if (verb == null) {
            sink.reply("command.land.selection.usage", Map.of());
            return;
        }
        switch (verb) {
            case "chunk" -> setMode(actor, session, true, sink);
            case "rectangle" -> setMode(actor, session, false, sink);
            case "toggle" -> setMode(actor, session, !selections.isChunkEdit(actor), sink);
            case "add" -> editOne(actor, session, args, true, sink, player);
            case "remove" -> editOne(actor, session, args, false, sink, player);
            case "info" -> info(session, sink);
            default -> sink.reply("command.land.selection.usage", Map.of());
        }
    }

    private void setMode(UUID playerId, SelectionSession session, boolean chunk, ReplySink sink) {
        boolean armed;
        try {
            armed = selections.setChunkEdit(playerId, chunk);
        } catch (RuntimeException failure) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        if (!armed) {
            sink.reply("command.land.selection.no_selection", Map.of());
            return;
        }
        sink.reply(chunk ? "command.land.selection.mode_chunk" : "command.land.selection.mode_rectangle",
                Map.of("chunk_count", session.selectedChunks().size()));
    }

    private void editOne(UUID playerId, SelectionSession session, String[] args,
            boolean add, ReplySink sink, Player player) {
        if (session.mode() == SelectionMode.CREATE_SUBLAND) {
            sink.reply("command.land.selection.wrong_mode", Map.of());
            return;
        }
        SelectionEditService service;
        try {
            service = edits == null ? null : edits.get();
        } catch (RuntimeException failure) {
            service = null;
        }
        if (service == null) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.unavailable"));
            return;
        }
        ChunkKey chunk = chunkOf(session, args, player);
        if (chunk == null) {
            if (wantsExplicit(args) && !parsableExplicit(args)) {
                sink.reply("command.land.selection.usage", Map.of());
            } else {
                sink.reply("command.land.selection.unavailable", Map.of());
            }
            return;
        }
        SelectionEditOutcome outcome;
        try {
            outcome = service.editChunk(session, chunk, add ? PendingChange.ADD : PendingChange.REMOVE);
        } catch (RuntimeException failure) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        if (outcome == null || outcome.expectedSession() != session) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        if (!outcome.accepted() || outcome.update().isEmpty()) {
            if (outcome.reason() == SelectionEditReason.NO_CHANGE) {
                sink.reply("command.land.selection.no_change", Map.of());
            } else {
                sink.reply("command.land.selection.rejected",
                        Map.of("reason", outcome.reason().name().toLowerCase(Locale.ROOT)));
            }
            return;
        }
        SelectionSession updated;
        try {
            Optional<SelectionSession> applied =
                    selections.updateSelection(playerId, outcome.expectedSession(), outcome.update().get());
            if (applied == null || applied.isEmpty()) {
                sink.reply("command.land.selection.stale", Map.of());
                return;
            }
            updated = applied.get();
        } catch (RuntimeException failure) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        sink.reply(add ? "command.land.selection.added" : "command.land.selection.removed",
                Map.of("chunk_count", updated.selectedChunks().size()));
    }

    private void info(SelectionSession session, ReplySink sink) {
        boolean chunk;
        try {
            chunk = selections.isChunkEdit(session.playerId());
        } catch (RuntimeException failure) {
            sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
            return;
        }
        sink.reply("command.land.selection.info",
                Map.of("chunk_count", session.selectedChunks().size(), "state", chunk ? "chunk" : "rectangle"));
    }

    /**
     * Resolve the verb chunk: explicit block coordinates win when present
     * and parsable, otherwise the standing chunk is used. Returns null when
     * no chunk can be resolved.
     */
    private ChunkKey chunkOf(SelectionSession session, String[] args, Player player) {
        if (wantsExplicit(args)) {
            if (!parsableExplicit(args)) {
                return null;
            }
            try {
                int blockX = Integer.parseInt(args[2].trim());
                int blockZ = Integer.parseInt(args[3].trim());
                return new ChunkKey(session.worldId(),
                        Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        if (standing == null) {
            return null;
        }
        try {
            Optional<ChunkKey> resolved = standing.standingChunkOf(player);
            return resolved == null ? null : resolved.orElse(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean wantsExplicit(String[] args) {
        return args != null && args.length >= 4;
    }

    private static boolean parsableExplicit(String[] args) {
        if (!wantsExplicit(args)) {
            return false;
        }
        try {
            Integer.parseInt(args[2].trim());
            Integer.parseInt(args[3].trim());
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String verbOf(String[] args) {
        if (args == null || args.length < 2 || args[1] == null) {
            return null;
        }
        String verb = args[1].toLowerCase(Locale.ROOT).trim();
        if (verb.isEmpty() || !VERBS.contains(verb)) {
            return null;
        }
        return verb;
    }

    /** Read the actor id without letting a Bukkit failure escape the command path. */
    private static UUID actorOf(Player player) {
        return player.getUniqueId();
    }
}
