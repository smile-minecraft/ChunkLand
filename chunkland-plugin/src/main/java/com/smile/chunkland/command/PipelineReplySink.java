package com.smile.chunkland.command;

import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Pipeline-backed {@link ReplySink}.
 *
 * <p>Player senders go through the pipeline's {@code sendChat} (including
 * Bedrock fallback); console / non-Player senders use
 * {@code renderForBroadcast} so the same {@code messageKey + vars} renders
 * without a Player context and no second copy is maintained.</p>
 */
public final class PipelineReplySink implements ReplySink {

    private final CommandSender sender;
    private final ChunkLandMessagePipeline pipeline;

    public PipelineReplySink(CommandSender sender, ChunkLandMessagePipeline pipeline) {
        this.sender = sender;
        this.pipeline = pipeline;
    }

    @Override
    public void reply(String messageKey, Map<String, Object> vars) {
        reply(messageKey, vars, null);
    }

    @Override
    public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
        Map<String, Object> safeVars = vars == null ? Map.of() : vars;
        if (sender instanceof Player player && pipeline != null) {
            try {
                pipeline.sendChat(player, messageKey, safeVars, localeOverride);
                return;
            } catch (RuntimeException ignored) {
                // fall through to broadcast-safe path
            }
        }
        if (pipeline != null) {
            try {
                Component rendered = pipeline.renderForBroadcast(messageKey, safeVars, localeOverride);
                sender.sendMessage(rendered);
                return;
            } catch (RuntimeException ignored) {
                // fall through to plain fallback
            }
        }
        // Ultimate fallback when pipeline is absent or rendering failed.
        sender.sendMessage(messageKey);
    }
}
