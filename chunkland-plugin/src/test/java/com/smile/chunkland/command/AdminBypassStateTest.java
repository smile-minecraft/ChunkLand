package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit contract for the per-enable player-scoped bypass memory.
 *
 * <p>The state is off by default, scoped to one actor, bounded, and
 * cleanable: a fresh instance behaves like a just-enabled plugin, and
 * {@code clear()} behaves like a disable.
 */
class AdminBypassStateTest {

    @Test
    void freshStateIsOffForEveryActor() {
        AdminBypassState states = new AdminBypassState();
        assertFalse(states.isOn(UUID.randomUUID()));
        assertFalse(states.isOn(UUID.randomUUID()));
        assertEquals(0, states.size());
    }

    @Test
    void toggleIsActorScoped() {
        AdminBypassState states = new AdminBypassState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(states.setEnabled(first, true));
        assertTrue(states.isOn(first));
        assertFalse(states.isOn(second));
        assertTrue(states.setEnabled(first, false));
        assertFalse(states.isOn(first));
    }

    @Test
    void repeatedToggleIsDeterministic() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            assertTrue(states.setEnabled(actor, true));
            assertTrue(states.isOn(actor));
            assertTrue(states.setEnabled(actor, false));
            assertFalse(states.isOn(actor));
        }
        assertEquals(0, states.size());
    }

    @Test
    void clearResetsEveryActorLikeADisable() {
        AdminBypassState states = new AdminBypassState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        states.setEnabled(first, true);
        states.setEnabled(second, true);
        states.clear();
        assertFalse(states.isOn(first));
        assertFalse(states.isOn(second));
        assertEquals(0, states.size());
    }

    @Test
    void enablingIsBoundedSoOneEnableCannotGrowWithoutLimit() {
        AdminBypassState states = new AdminBypassState();
        List<UUID> actors = new ArrayList<>();
        for (int i = 0; i < AdminBypassState.MAX_ACTORS; i++) {
            UUID actor = UUID.randomUUID();
            actors.add(actor);
            assertTrue(states.setEnabled(actor, true), "slot " + i + " must fit the bound");
        }
        assertFalse(states.setEnabled(UUID.randomUUID(), true),
                "a full enable must refuse a new bypass instead of growing");
        assertTrue(states.setEnabled(actors.get(0), false),
                "disabling must always succeed, even when full");
        assertTrue(states.setEnabled(UUID.randomUUID(), true),
                "the freed slot must admit a new actor");
    }

    @Test
    void nullActorsAreRejectedFailClosed() {
        AdminBypassState states = new AdminBypassState();
        assertThrows(NullPointerException.class, () -> states.isOn(null));
        assertThrows(NullPointerException.class, () -> states.setEnabled(null, true));
    }

    @Test
    void concurrentEnableOfOneActorStaysDeterministic() throws Exception {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return states.setEnabled(actor, true);
                }));
            }
            start.countDown();
            for (Future<Boolean> outcome : outcomes) {
                assertTrue(outcome.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(states.isOn(actor));
        assertEquals(1, states.size());
    }
}
