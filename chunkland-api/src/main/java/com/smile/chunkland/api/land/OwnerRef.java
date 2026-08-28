package com.smile.chunkland.api.land;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable reference to the owner of a Land.
 *
 * <p>V1 supports {@link PlayerOwnerRef} and {@link ServerOwnerRef}. The type is
 * a sealed interface so future owner kinds (e.g. {@code GUILD}) can be added
 * without breaking the public contract (spec §10).
 *
 * <p>Thread-safe: all implementations are immutable value objects.
 */
public sealed interface OwnerRef permits OwnerRef.PlayerOwnerRef, OwnerRef.ServerOwnerRef {

    /** Stable string key, e.g. {@code PLAYER:<uuid>} or {@code SERVER}. */
    String key();

    static OwnerRef player(UUID uuid) {
        return new PlayerOwnerRef(uuid);
    }

    static OwnerRef server() {
        return ServerOwnerRef.INSTANCE;
    }

    /** Player-owned Land, keyed by the owner's Minecraft UUID (spec §10, §11). */
    record PlayerOwnerRef(UUID uuid) implements OwnerRef {
        public PlayerOwnerRef {
            Objects.requireNonNull(uuid, "uuid");
        }

        @Override
        public String key() {
            return "PLAYER:" + uuid;
        }
    }

    /** Server-owned Land (spec §12). */
    record ServerOwnerRef() implements OwnerRef {
        static final ServerOwnerRef INSTANCE = new ServerOwnerRef();

        @Override
        public String key() {
            return "SERVER";
        }
    }
}
