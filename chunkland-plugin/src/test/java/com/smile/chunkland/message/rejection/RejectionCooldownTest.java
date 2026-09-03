package com.smile.chunkland.message.rejection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.selection.SelectionClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RejectionCooldownTest {

    private static final class FakeClock implements SelectionClock {
        private final AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-09-03T00:00:00Z"));

        @Override
        public Instant now() {
            return now.get();
        }

        void advance(Duration delta) {
            now.updateAndGet(t -> t.plus(delta));
        }
    }

    @Test
    void firstAcquirePasses() {
        FakeClock clock = new FakeClock();
        RejectionCooldown cooldown = new RejectionCooldown(clock, Duration.ofSeconds(3));
        assertTrue(cooldown.tryAcquire(UUID.randomUUID(), ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void secondAcquireWithinCooldownIsSuppressed() {
        FakeClock clock = new FakeClock();
        RejectionCooldown cooldown = new RejectionCooldown(clock, Duration.ofSeconds(3));
        UUID player = UUID.randomUUID();
        assertTrue(cooldown.tryAcquire(player, ProtectionActionType.BLOCK_BREAK));
        clock.advance(Duration.ofSeconds(2));
        assertFalse(cooldown.tryAcquire(player, ProtectionActionType.BLOCK_BREAK),
                "same (player, action) inside the window must not resend");
    }

    @Test
    void acquireAfterCooldownExpiryPassesAgain() {
        FakeClock clock = new FakeClock();
        RejectionCooldown cooldown = new RejectionCooldown(clock, Duration.ofSeconds(3));
        UUID player = UUID.randomUUID();
        assertTrue(cooldown.tryAcquire(player, ProtectionActionType.BLOCK_BREAK));
        clock.advance(Duration.ofSeconds(3));
        assertTrue(cooldown.tryAcquire(player, ProtectionActionType.BLOCK_BREAK),
                "expiry boundary must allow one resend");
    }

    @Test
    void cooldownIsPerAction() {
        FakeClock clock = new FakeClock();
        RejectionCooldown cooldown = new RejectionCooldown(clock, Duration.ofSeconds(3));
        UUID player = UUID.randomUUID();
        assertTrue(cooldown.tryAcquire(player, ProtectionActionType.BLOCK_BREAK));
        assertTrue(cooldown.tryAcquire(player, ProtectionActionType.ENTRY),
                "a different action must have its own window");
        assertFalse(cooldown.tryAcquire(player, ProtectionActionType.BLOCK_BREAK));
    }

    @Test
    void cooldownIsPerPlayer() {
        FakeClock clock = new FakeClock();
        RejectionCooldown cooldown = new RejectionCooldown(clock, Duration.ofSeconds(3));
        assertTrue(cooldown.tryAcquire(UUID.randomUUID(), ProtectionActionType.BLOCK_BREAK));
        assertTrue(cooldown.tryAcquire(UUID.randomUUID(), ProtectionActionType.BLOCK_BREAK),
                "a different player must have its own window");
    }
}
