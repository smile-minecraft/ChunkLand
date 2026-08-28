package com.smile.chunkland.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Encodes and decodes the 16-byte UUID storage contract used by the schema.
 *
 * <p>UUIDs are stored as {@code BLOB(16)} so the binary form is stable and
 * index-friendly. SQLite does not enforce the length of a {@code BLOB(16)}
 * column, so the exact 16-byte length is guaranteed by this codec rather than
 * by the database type affinity.
 */
final class UuidBlob {

    static final int BYTE_LENGTH = 16;

    private UuidBlob() {
    }

    static byte[] encode(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[BYTE_LENGTH]);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    static UUID decode(byte[] bytes) {
        if (bytes == null || bytes.length != BYTE_LENGTH) {
            throw new IllegalArgumentException(
                    "UUID blob must be exactly " + BYTE_LENGTH + " bytes, got "
                            + (bytes == null ? "null" : bytes.length));
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long most = buffer.getLong();
        long least = buffer.getLong();
        return new UUID(most, least);
    }
}
