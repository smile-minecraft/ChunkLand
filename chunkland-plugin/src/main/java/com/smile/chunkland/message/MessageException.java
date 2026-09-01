package com.smile.chunkland.message;

/**
 * Fail-closed exception for the message pipeline.
 *
 * <p>Thrown when a message key is unknown, a resource is missing, or the
 * MiniMessage template is malformed. Callers must not fall back to
 * {@code Component.empty()} silently.</p>
 */
public final class MessageException extends RuntimeException {

    private final String messageKey;

    public MessageException(String messageKey, String detail) {
        super(detail);
        this.messageKey = messageKey;
    }

    public MessageException(String messageKey, String detail, Throwable cause) {
        super(detail, cause);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
