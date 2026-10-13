package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.WorldSettings;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The claim gate follows the live conservative flag: leaving the fallback
 * applies to the next claim without rebuilding the validator or the saga,
 * while listed worlds keep their configured answer either way.
 */
class WorldClaimPolicyReloadTest {

    private static final UUID LISTED = UUID.randomUUID();
    private static final UUID UNLISTED = UUID.randomUUID();

    private static WorldClaimPolicy policy(AtomicBoolean conservative) {
        ChunkLandConfig config = new ChunkLandConfig(
                Map.of("world", new WorldSettings(true)), 0L, Map.of());
        return WorldClaimPolicy.fromConfig(() -> config,
                worldId -> {
                    if (LISTED.equals(worldId)) {
                        return Optional.of("world");
                    }
                    if (UNLISTED.equals(worldId)) {
                        return Optional.of("fresh");
                    }
                    return Optional.empty();
                },
                conservative::get);
    }

    @Test
    void strictnessFlipAppliesWithoutRebuild() {
        AtomicBoolean conservative = new AtomicBoolean(true);
        WorldClaimPolicy gate = policy(conservative);

        assertThrows(ClaimRejectedException.class, () -> gate.checkClaimAllowed(UNLISTED),
                "while conservative, an unlisted world must reject");

        conservative.set(false);

        assertDoesNotThrow(() -> gate.checkClaimAllowed(UNLISTED),
                "after leaving the fallback the same gate must follow the enabled default");
    }

    @Test
    void listedWorldsIgnoreTheFlag() {
        AtomicBoolean conservative = new AtomicBoolean(true);
        WorldClaimPolicy gate = policy(conservative);

        assertDoesNotThrow(() -> gate.checkClaimAllowed(LISTED));
        conservative.set(false);
        assertDoesNotThrow(() -> gate.checkClaimAllowed(LISTED));
    }
}
