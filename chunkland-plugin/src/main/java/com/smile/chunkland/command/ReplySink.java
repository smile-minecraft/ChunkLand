package com.smile.chunkland.command;

import java.util.Locale;
import java.util.Map;

/**
 * Abstraction over command reply rendering.
 *
 * <p>Callers supply only {@code messageKey + vars}; the sink routes through
 * {@link com.smile.chunkland.message.ChunkLandMessagePipeline} so no caller
 * owns MiniMessage or builds a second copy of the copy.</p>
 *
 * <p>Player senders use the pipeline's player-aware chat path (which handles
 * Bedrock fallback internally); non-Player senders use the pipeline's
 * broadcast-safe {@code renderForBroadcast} path and send the resulting
 * Component directly. The same key and vars are used for both branches.</p>
 */
public interface ReplySink {

    void reply(String messageKey, Map<String, Object> vars);

    void reply(String messageKey, Map<String, Object> vars, Locale localeOverride);
}
