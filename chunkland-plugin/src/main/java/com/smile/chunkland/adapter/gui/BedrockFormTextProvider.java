package com.smile.chunkland.adapter.gui;

import com.smile.chunkland.command.BedrockManageFormHandler;
import com.smile.chunkland.gui.BedrockFormTexts;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Caller-side source of the Bedrock form copy.
 *
 * <p>This is where translation happens for Bedrock forms: every key renders
 * through the message pipeline with the player as context, so the locale
 * chain (stored preference, client locale, Bedrock language code, default)
 * is the same one chat messages use, and permission constants arrive as
 * their display names. Forms paint literal text, so the result is
 * plain-serialized. Any failure for one key falls back to the bundled
 * English wording for that key alone, so a missing pipeline or a broken
 * template can never keep a form from opening.
 */
public final class BedrockFormTextProvider {

    private BedrockFormTextProvider() {
    }

    /**
     * @param pipelines current message pipeline; {@code null}, a
     *                  {@code null} answer or a failing read keeps the
     *                  bundled English wording
     */
    public static BedrockManageFormHandler.TextSource over(
            Supplier<ChunkLandMessagePipeline> pipelines) {
        return player -> {
            ChunkLandMessagePipeline pipeline;
            try {
                pipeline = pipelines == null ? null : pipelines.get();
            } catch (RuntimeException unavailable) {
                pipeline = null;
            }
            if (pipeline == null) {
                return BedrockFormTexts.english();
            }
            return forPlayer(pipeline, player);
        };
    }

    static BedrockFormTexts forPlayer(ChunkLandMessagePipeline pipeline, Player player) {
        return (key, vars) -> {
            Map<String, Object> safeVars = vars == null ? Map.of() : vars;
            try {
                String rendered = PlainTextComponentSerializer.plainText()
                        .serialize(pipeline.render(key, safeVars, null, player));
                if (rendered != null && !rendered.isBlank()) {
                    return rendered;
                }
            } catch (RuntimeException failed) {
                // Fall through to the bundled wording for this key.
            }
            return BedrockFormTexts.english().text(key, safeVars);
        };
    }
}
