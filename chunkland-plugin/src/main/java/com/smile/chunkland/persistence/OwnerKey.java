package com.smile.chunkland.persistence;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit string contract for the {@code owner_key} column.
 *
 * <p>The namespace is never implicit: a player-owned chunk uses
 * {@code PLAYER:<uuid>} while server-owned state uses the literal
 * {@code SERVER}. Storing the namespace in the value keeps the semantics
 * readable in raw SQL and prevents UUID/owner meaning from leaking into an
 * undocumented convention.
 */
final class OwnerKey {

    static final String PLAYER_PREFIX = "PLAYER:";
    static final String SERVER_VALUE = "SERVER";

    private final String value;

    private OwnerKey(String value) {
        this.value = value;
    }

    static OwnerKey player(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return new OwnerKey(PLAYER_PREFIX + uuid);
    }

    static OwnerKey server() {
        return new OwnerKey(SERVER_VALUE);
    }

    static OwnerKey parse(String value) {
        Objects.requireNonNull(value, "value");
        if (SERVER_VALUE.equals(value)) {
            return new OwnerKey(SERVER_VALUE);
        }
        if (value.startsWith(PLAYER_PREFIX)) {
            String uuidText = value.substring(PLAYER_PREFIX.length());
            if (uuidText.isEmpty()) {
                throw new IllegalArgumentException(
                        "owner_key PLAYER namespace requires a UUID: " + value);
            }
            UUID parsed;
            try {
                parsed = UUID.fromString(uuidText);
            } catch (IllegalArgumentException invalidUuid) {
                throw new IllegalArgumentException(
                        "owner_key has an invalid PLAYER UUID: " + value, invalidUuid);
            }
            if (!parsed.toString().equalsIgnoreCase(uuidText)) {
                throw new IllegalArgumentException(
                        "owner_key PLAYER UUID must use the canonical 8-4-4-4-12 form: " + value);
            }
            return new OwnerKey(value);
        }
        throw new IllegalArgumentException(
                "owner_key must be SERVER or PLAYER:<uuid>: " + value);
    }

    String asString() {
        return value;
    }

    boolean isServer() {
        return SERVER_VALUE.equals(value);
    }

    boolean isPlayer() {
        return value.startsWith(PLAYER_PREFIX);
    }

    UUID playerUuid() {
        if (!isPlayer()) {
            throw new IllegalStateException("owner_key is not a player key: " + value);
        }
        return UUID.fromString(value.substring(PLAYER_PREFIX.length()));
    }
}
