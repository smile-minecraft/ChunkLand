package com.smile.chunkland.message.rejection;

import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Production {@link RejectionNotifier.Renderer}: renders the shared rejection
 * template through the message pipeline.
 *
 * <p>Only facts already on the deny path are used as variables (the action
 * name and the decision explanation), so rendering never touches storage or
 * the world. Any pipeline failure surfaces as a {@link RuntimeException},
 * which the notifier already swallows into a silent {@code false}.
 */
public final class PipelineRejectionRenderer implements RejectionNotifier.Renderer {

    /** Shared template; uses only the always-supplied {@code action}/{@code reason} vars. */
    public static final String MESSAGE_KEY = "protection.rejection.action_denied";

    private final ChunkLandMessagePipeline pipeline;

    public PipelineRejectionRenderer(ChunkLandMessagePipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    @Override
    public Component render(Player player, ProtectionActionType action,
                            PermissionDecision decision) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(decision, "decision");
        String reason = decision.explanation() == null ? "" : decision.explanation();
        return pipeline.render(MESSAGE_KEY,
                Map.of("action", action.name(), "reason", reason), null, player);
    }
}
