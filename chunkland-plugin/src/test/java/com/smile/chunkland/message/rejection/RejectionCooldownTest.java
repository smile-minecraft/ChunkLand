package com.smile.chunkland.message.rejection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.selection.SelectionClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    void concurrentAcquireForSameKeyAllowsExactlyOne() throws Exception {
        int contenders = 32;
        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int round = 0; round < rounds; round++) {
                FakeClock clock = new FakeClock();
                RejectionCooldown cooldown =
                        new RejectionCooldown(clock, Duration.ofSeconds(3));
                UUID player = UUID.randomUUID();
                CountDownLatch ready = new CountDownLatch(contenders);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>(contenders);
                for (int i = 0; i < contenders; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return cooldown.tryAcquire(player, ProtectionActionType.BLOCK_BREAK);
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS),
                        "all contenders must reach the start gate");
                start.countDown();
                int passed = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(10, TimeUnit.SECONDS)) {
                        passed++;
                    }
                }
                assertEquals(1, passed,
                        "round " + round + " must allow exactly one concurrent send");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
