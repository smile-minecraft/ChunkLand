package com.smile.chunkland.command;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Bedrock branch of {@code /land selection}: a Simple menu with one button
 * per chunk-edit operation, so Bedrock players never have to fall back to
 * the chat commands the buttons replace.
 *
 * <p>Only the supported public AceLib surface is used ({@code BedrockService}
 * for the player check, {@code FormSpec.simple} plus the {@code send(UUID,
 * FormSpec, Consumer<FormResponse>)} overload). Nothing is cast to an
 * implementation class and no service is shut down here.
 *
 * <p>Each button re-enters through the injected chat handler with the same
 * verbs a Java player would type ({@code toggle}, {@code add}, {@code
 * remove}), so form edits and chat edits run one code path and cannot drift
 * apart. The form itself only reads the session it opened for; the chat
 * handler re-reads the live session, so a selection that changed while the
 * menu was open is judged on its current state, never on a stale snapshot.
 *
 * <p>Fail-closed contract: an unknown player kind, a missing session, a
 * form that cannot be built or sent, a null or rejected send result, a null
 * response, any {@code CLOSED}/{@code INVALID} status and any unknown
 * button all reply without touching selection state. A non-Bedrock player
 * returns {@code false} so the caller keeps the chat path untouched.
 *
 * <p>Folia-safe dispatch: the form callback never touches the player or the
 * live selection on the calling thread. It first re-dispatches through the
 * public {@code SafeScheduler.runForPlayer(Player, Runnable)} and only the
 * scheduled task runs the response handling. A missing scheduler, a dispatch
 * throw, or an unacceptable schedule result fails closed with a single
 * failure reply; on success the scheduled task owns every reply.
 */
public final class BedrockSelectionFormHandler {

    /** Player-kind check; a throw means unknown and fails the menu closed. */
    @FunctionalInterface
    public interface BedrockLookup {
        boolean isBedrock(UUID playerId);
    }

    /**
     * On-demand form send; implementations resolve the live form service per
     * call and throw when none is available.
     */
    @FunctionalInterface
    public interface FormSender {
        FormSendResult send(UUID playerId, FormSpec spec, Consumer<FormResponse> callback);
    }

    private static final int BUTTON_TOGGLE = 0;
    private static final int BUTTON_ADD = 1;
    private static final int BUTTON_REMOVE = 2;

    private final BedrockLookup bedrock;
    private final FormSender forms;
    private final LandCommand.Handler selection;
    private final SelectionSessionManager selections;
    private final Function<Locale, SelectionFormTexts> texts;
    private final SafeScheduler folia;

    /**
     * @param bedrock player-kind check; never null
     * @param forms on-demand form send; never null
     * @param selection shared chat entry (the {@code /land selection} chat
     *        handler); button presses re-enter through its verbs; never null
     * @param selections live selection registry, read for the menu body only;
     *        never null
     * @param texts per-locale form copy; never null
     * @param folia player-scoped scheduler for the form callback; null means
     *        dispatch is unavailable and every callback fails closed without
     *        touching selection state
     */
    public BedrockSelectionFormHandler(BedrockLookup bedrock, FormSender forms,
            LandCommand.Handler selection, SelectionSessionManager selections,
            Function<Locale, SelectionFormTexts> texts, SafeScheduler folia) {
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.forms = Objects.requireNonNull(forms, "forms");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.selections = Objects.requireNonNull(selections, "selections");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.folia = folia;
    }

    /**
     * Run the Bedrock branch for one selection command.
     *
     * @param session the live session the dispatcher already read; null
     *        means no selection and fails closed with guidance
     * @return {@code true} when the Bedrock path took over; {@code false}
     *         for non-Bedrock players so the caller keeps the chat path
     */
    public boolean handle(Player player, SelectionSession session, ReplySink sink) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(sink, "sink");
        UUID actor = player.getUniqueId();
        boolean bedrockPlayer;
        try {
            bedrockPlayer = bedrock.isBedrock(actor);
        } catch (RuntimeException failure) {
            replyFailed(sink);
            return true;
        }
        if (!bedrockPlayer) {
            return false;
        }
        if (session == null) {
            sink.reply("command.land.selection.no_selection", Map.of());
            return true;
        }
        boolean chunkEdit;
        try {
            chunkEdit = selections.isChunkEdit(actor);
        } catch (RuntimeException failure) {
            replyFailed(sink);
            return true;
        }
        SelectionFormTexts copy;
        try {
            copy = texts.apply(playerLocaleOf(player));
        } catch (RuntimeException failure) {
            replyFailed(sink);
            return true;
        }
        if (copy == null) {
            replyFailed(sink);
            return true;
        }
        FormSpec spec;
        try {
            spec = FormSpec.simple(copy.title())
                    .content(copy.contentFor(chunkEdit, session.selectedChunks().size()))
                    .button(copy.toggleButton())
                    .button(copy.addButton())
                    .button(copy.removeButton())
                    .button(copy.closeButton())
                    .build();
        } catch (RuntimeException failure) {
            replyFailed(sink);
            return true;
        }
        FormSendResult result;
        try {
            result = forms.send(actor, spec, response -> onResponse(player, sink, response));
        } catch (RuntimeException failure) {
            replyFailed(sink);
            return true;
        }
        if (result == null || !result.isSent()) {
            replyFailed(sink);
            return true;
        }
        return true;
    }

    private void onResponse(Player player, ReplySink sink, FormResponse response) {
        SafeScheduler dispatch = folia;
        if (dispatch == null) {
            replyFailed(sink);
            return;
        }
        final FormResponse captured = response;
        final ScheduledTask scheduled;
        try {
            scheduled = dispatch.runForPlayer(player, () -> handleResponse(player, sink, captured));
        } catch (RuntimeException dispatchFailure) {
            replyFailed(sink);
            return;
        }
        if (!accepted(scheduled)) {
            replyFailed(sink);
        }
    }

    private static boolean accepted(ScheduledTask scheduled) {
        if (scheduled == null) {
            return false;
        }
        try {
            return !scheduled.isCancelled();
        } catch (RuntimeException stateFailure) {
            return false;
        }
    }

    private void handleResponse(Player player, ReplySink sink, FormResponse response) {
        try {
            if (response == null || response.status() == null) {
                sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
                return;
            }
            switch (response.status()) {
                case CLOSED, INVALID -> {
                    sink.reply("command.land.selection.cancelled", Map.of());
                    return;
                }
                case VALID -> {
                    // fall through to the button check below
                }
            }
            Integer button;
            try {
                button = response.clickedButton().orElse(null);
            } catch (RuntimeException failure) {
                sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed"));
                return;
            }
            if (button == null) {
                sink.reply("command.land.selection.cancelled", Map.of());
                return;
            }
            switch (button.intValue()) {
                case BUTTON_TOGGLE -> selection.handle(player, new String[]{"selection", "toggle"}, sink);
                case BUTTON_ADD -> selection.handle(player, new String[]{"selection", "add"}, sink);
                case BUTTON_REMOVE -> selection.handle(player, new String[]{"selection", "remove"}, sink);
                default -> sink.reply("command.land.selection.cancelled", Map.of());
            }
        } catch (RuntimeException failure) {
            replyFailed(sink);
        }
    }

    private static Locale playerLocaleOf(Player player) {
        try {
            Locale locale = player.locale();
            return locale == null ? Locale.US : locale;
        } catch (RuntimeException failure) {
            return Locale.US;
        }
    }

    private static void replyFailed(ReplySink sink) {
        failQuietly(() -> sink.reply("command.land.selection.failed", Map.of("reason", "selection.failed")));
    }

    private static void failQuietly(Runnable reply) {
        try {
            reply.run();
        } catch (RuntimeException ignored) {
            // Terminal fail-closed path: a broken sink must not escape the form flow.
        }
    }
}
