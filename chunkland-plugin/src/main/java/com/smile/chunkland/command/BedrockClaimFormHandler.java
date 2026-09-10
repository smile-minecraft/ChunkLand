package com.smile.chunkland.command;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Bedrock branch of {@code /land claim}: shows an AceLib public Modal Form
 * and routes an explicit confirmation into the shared chat-confirmation
 * entry point.
 *
 * <p>Only the supported public AceLib surface is used ({@code BedrockService}
 * for the player check, {@code FormSpec.modal} plus the
 * {@code sendForm(UUID, FormSpec, Consumer<FormResponse>)} overload). Nothing
 * is cast to an implementation class and no service is shut down here; the
 * form service is resolved on demand on every send through the injected
 * sender, so a reloaded or failed AceLib facade is never cached.
 *
 * <p>Fail-closed contract: an unknown player kind (the lookup throws), a
 * missing confirm permission, a form that cannot be built or sent, a null or
 * rejected send result, a null response, any {@code CLOSED}/{@code INVALID}
 * status, any button but index {@code 0}, and any callback failure all reply
 * without touching the saga (no ledger row, no charge, no economy call). A
 * non-Bedrock player returns {@code false} so the caller keeps the direct
 * Java claim path untouched.
 *
 * <p>Confirmation path: a {@code VALID} button-{@code 0} response re-enters
 * through the injected confirmation handler with the generation/revision
 * tokens captured at form-show time, so the sessionGeneration,
 * selectionRevision and structure revision revalidation, the single-use
 * replay guard and the saga ordering stay exactly the chat flow's.
 *
 * <p>Folia-safe dispatch: the form callback thread is not a proven Folia
 * region thread, so the callback never touches the player, the live
 * selection read or the confirmation entry directly. It first re-dispatches
 * through the public {@code SafeScheduler.runForPlayer(Player, Runnable)}
 * and only the scheduled task runs the response handling below. A missing
 * scheduler, a dispatch throw, or an unacceptable schedule result (null or
 * an already-cancelled task) fails closed with a single failure reply and
 * never reaches the saga; on success the scheduled task owns every reply,
 * so the dispatch site sends none.
 *
 * <p>The callback never throws: everything runs inside a guard that best-efforts a
 * failure reply instead. It touches no Bukkit state beyond the already-held
 * sender, the live selection read and the reply sink — the same surface the
 * saga-completion replies already use.
 */
public final class BedrockClaimFormHandler {

    /** Player-kind check; a throw means unknown and fails the claim closed. */
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

    private final BedrockLookup bedrock;
    private final FormSender forms;
    private final LandCommand.Handler confirm;
    private final Function<Locale, ClaimFormTexts> texts;
    private final SafeScheduler folia;

    /**
     * @param bedrock player-kind check; never null
     * @param forms on-demand form send; never null
     * @param confirm shared confirmation entry (normally the production
     *        {@link ConfirmCommandHandler} instance behind {@code /land confirm});
     *        never null
     * @param texts per-locale form copy; never null
     * @param folia player-scoped scheduler for the form callback; null means
     *        dispatch is unavailable and every callback fails closed without
     *        touching the saga (unit tests pass an immediate stub, which
     *        covers logic only and proves nothing about Folia threads)
     */
    public BedrockClaimFormHandler(BedrockLookup bedrock, FormSender forms,
            LandCommand.Handler confirm, Function<Locale, ClaimFormTexts> texts,
            SafeScheduler folia) {
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.forms = Objects.requireNonNull(forms, "forms");
        this.confirm = Objects.requireNonNull(confirm, "confirm");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.folia = folia;
    }

    /**
     * Run the Bedrock branch for one claim.
     *
     * @return {@code true} when the Bedrock path took over (form shown, or a
     *         fail-closed reply was sent); {@code false} for non-Bedrock
     *         players so the caller keeps the direct Java path
     */
    public boolean handle(Player player, ClaimPreview preview, ReplySink sink) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(preview, "preview");
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
        boolean mayConfirm;
        try {
            mayConfirm = player.hasPermission(LandPermissions.CONFIRM);
        } catch (RuntimeException failure) {
            replyFailed(sink);
            return true;
        }
        if (!mayConfirm) {
            failQuietly(() -> sink.reply("command.land.denied", Map.of("permission", LandPermissions.CONFIRM)));
            return true;
        }
        ClaimFormTexts copy;
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
            spec = FormSpec.modal(copy.title())
                    .content(copy.bodyFor(preview))
                    .button1(copy.confirmButton())
                    .button2(copy.cancelButton())
                    .build();
        } catch (RuntimeException failure) {
            replyFailed(sink);
            return true;
        }
        FormSendResult result;
        try {
            result = forms.send(actor, spec, response -> onResponse(player, preview, sink, response));
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

    /**
     * Form response callback. Never throws and never touches the player, the
     * live selection or the confirmation entry on the calling thread: the
     * response handling below only runs inside a task scheduled via the
     * public {@code SafeScheduler.runForPlayer(Player, Runnable)}.
     *
     * <p>Fail-closed dispatch: a missing scheduler, a dispatch throw, or an
     * unacceptable schedule result (null or an already-cancelled task) ends
     * in one best-effort failure reply without touching the saga. A
     * successful dispatch leaves every reply to the scheduled task, so this
     * site sends none and a response can never be answered twice.
     */
    private void onResponse(Player player, ClaimPreview preview, ReplySink sink, FormResponse response) {
        SafeScheduler dispatch = folia;
        if (dispatch == null) {
            replyFailed(sink);
            return;
        }
        final FormResponse captured = response;
        final ScheduledTask scheduled;
        try {
            scheduled = dispatch.runForPlayer(player, () -> handleResponse(player, preview, sink, captured));
        } catch (RuntimeException dispatchFailure) {
            replyFailed(sink);
            return;
        }
        if (!accepted(scheduled)) {
            replyFailed(sink);
        }
    }

    /**
     * Accept a schedule result only when it is non-null and not already
     * cancelled. The check itself is guarded: a task whose state read throws
     * counts as unacceptable and fails the callback closed.
     */
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

    /**
     * Response handling that only ever runs on the player's scheduled task
     * (or on an immediate unit-test stub). Never throws: any failure
     * (including a failure inside the shared confirmation entry) ends in a
     * best-effort failure reply, and the saga is only reachable after every
     * guard below passes.
     */
    private void handleResponse(Player player, ClaimPreview preview, ReplySink sink, FormResponse response) {
        try {
            if (response == null || response.status() == null) {
                sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
                return;
            }
            switch (response.status()) {
                case CLOSED, INVALID -> {
                    sink.reply("command.land.claim.cancelled", Map.of());
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
                sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed"));
                return;
            }
            if (button == null || button.intValue() != 0) {
                sink.reply("command.land.claim.cancelled", Map.of());
                return;
            }
            String[] confirmArgs = {
                    "confirm",
                    Long.toString(preview.sessionGeneration()),
                    Long.toString(preview.selectionRevision()),
                    preview.landName()};
            confirm.handle(player, confirmArgs, sink);
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
        failQuietly(() -> sink.reply("command.land.claim.failed", Map.of("reason", "claim.failed")));
    }

    private static void failQuietly(Runnable reply) {
        try {
            reply.run();
        } catch (RuntimeException ignored) {
            // Terminal fail-closed path: a broken sink must not escape the form flow.
        }
    }
}
