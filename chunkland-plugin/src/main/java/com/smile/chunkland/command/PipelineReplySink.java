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
        if (pipeline == null) {
            return;
        }
        if (sender instanceof Player player) {
            try {
                pipeline.sendChat(player, messageKey, safeVars, localeOverride);
            } catch (RuntimeException ignored) {
                // fail-closed: do not leak raw key and do not retry via broadcast path
            }
            return;
        }
        try {
            Component rendered = pipeline.renderForBroadcast(messageKey, safeVars, localeOverride);
            sender.sendMessage(rendered);
        } catch (RuntimeException ignored) {
            // fail-closed: no raw key fallback
        }
    }
}
