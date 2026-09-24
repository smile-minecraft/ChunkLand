package com.smile.chunkland.message.rejection;

import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Production {@link RejectionNotifier.Renderer}: renders the rejection
 * template matching the deny through the message pipeline.
 *
 * <p>Routing: an {@code ENTRY} deny whose explanation is the banned-inside
 * marker renders {@code protection.rejection.banned_inside}; any other
 * {@code ENTRY} deny renders {@code protection.rejection.entry_denied};
 * every other action keeps the shared {@code action_denied} template with
 * the {@code action}/{@code reason} vars. The marker itself is consumed by
 * the branch and never sent as visible text.
 *
 * <p>Only facts already on the deny path are used as variables (the action
 * name and the decision explanation), so rendering never touches storage or
 * the world. Rendering uses no player context: the locale resolves to the
 * pipeline default without touching the {@link Player}, so this stays safe
 * on the event thread; the actual send hops to the player thread inside
 * the {@link RejectionNotifier.Sender}. Any pipeline failure surfaces as a
 * {@link RuntimeException}, which the notifier already swallows into a
 * silent {@code false}.
 */
public final class PipelineRejectionRenderer implements RejectionNotifier.Renderer {

    /** Shared template; uses only the always-supplied {@code action}/{@code reason} vars. */
    public static final String MESSAGE_KEY = "protection.rejection.action_denied";

    /** ENTRY deny template; carries no vars. */
    public static final String ENTRY_DENIED_KEY = "protection.rejection.entry_denied";

    /** Banned-inside stop template; carries no vars. */
    public static final String BANNED_INSIDE_KEY = "protection.rejection.banned_inside";

    private final ChunkLandMessagePipeline pipeline;

    public PipelineRejectionRenderer(ChunkLandMessagePipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * Selects the template key for a deny without rendering.
     *
     * @param action the denied action, never {@code null}
     * @param explanation the decision explanation, may be {@code null}
     * @return the message key to render
     */
    public static String routeKey(ProtectionActionType action, String explanation) {
        Objects.requireNonNull(action, "action");
        if (action == ProtectionActionType.ENTRY) {
            if (RejectionNotifier.BANNED_INSIDE_REASON.equals(explanation)) {
                return BANNED_INSIDE_KEY;
            }
            return ENTRY_DENIED_KEY;
        }
        return MESSAGE_KEY;
    }

    @Override
    public Component render(Player player, ProtectionActionType action,
                            PermissionDecision decision) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(decision, "decision");
        String key = routeKey(action, decision.explanation());
        if (MESSAGE_KEY.equals(key)) {
            String reason = decision.explanation() == null ? "" : decision.explanation();
            return pipeline.render(MESSAGE_KEY,
                    Map.of("action", action.name(), "reason", reason), null, null);
        }
        return pipeline.render(key, Map.of(), null, null);
    }
}
