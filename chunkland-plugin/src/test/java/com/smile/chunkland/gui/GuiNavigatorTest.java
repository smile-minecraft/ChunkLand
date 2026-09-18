package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Red contract for per-player navigation over the shared {@code GuiService}:
 * generation-aware open / push / back / replace / close, click dispatch gated
 * by {@code validateClick}, player isolation, fail-closed errors and
 * disable-time cleanup without a provider-wide shutdown.
 */
class GuiNavigatorTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static GuiPage page(String id, int... slots) {
        List<GuiButton> buttons = new java.util.ArrayList<>();
        for (int slot : slots) {
            buttons.add(new GuiButton(slot, click -> { }));
        }
        return GuiPage.of(id, "title-" + id, 27, buttons);
    }

    @Test
    void openFirstPageRecordsAuthoritativeGeneration() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);

        Optional<Long> generation = navigator.open(ALICE, page("main", 10));

        assertTrue(generation.isPresent());
        assertEquals(generation.orElseThrow(), navigator.currentGeneration(ALICE).orElseThrow());
        assertEquals("main", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(1, navigator.depth(ALICE));
        assertEquals(generation.orElseThrow().longValue(),
            gui.activeSessionOf(ALICE).generation());
    }

    @Test
    void pushBackAndReplaceKeepPerPlayerStack() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        navigator.open(ALICE, page("main", 1));

        long second = navigator.push(ALICE, page("second", 2)).orElseThrow();
        assertEquals(2, navigator.depth(ALICE));
        assertEquals("second", navigator.currentPage(ALICE).orElseThrow().id());

        assertTrue(navigator.back(ALICE));
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("main", navigator.currentPage(ALICE).orElseThrow().id());

        long replaced = navigator.replace(ALICE, page("third", 3)).orElseThrow();
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("third", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(replaced, navigator.currentGeneration(ALICE).orElseThrow().longValue());
        assertEquals(replaced, gui.activeSessionOf(ALICE).generation());
        assertTrue(second != replaced, "every open must carry a fresh upstream generation");
    }

    @Test
    void pushOverLiveSessionClosesThenReopensWithFreshGeneration() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        long first = navigator.open(ALICE, page("main", 1)).orElseThrow();

        long second = navigator.push(ALICE, page("second", 2)).orElseThrow();

        assertTrue(second != first, "every open must carry a fresh upstream generation");
        assertEquals(2, navigator.depth(ALICE));
        assertEquals("second", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(second, navigator.currentGeneration(ALICE).orElseThrow().longValue());
        assertEquals(second, gui.activeSessionOf(ALICE).generation());
        assertTrue(gui.closeCalls().contains(new FakeGuiService.CloseCall(ALICE, first)),
            "the live session must be closed before the next page opens");
    }

    @Test
    void openOverLiveSessionReplacesWholeStack() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        navigator.open(ALICE, page("main", 1)).orElseThrow();
        long second = navigator.push(ALICE, page("second", 2)).orElseThrow();

        long third = navigator.open(ALICE, page("other", 3)).orElseThrow();

        assertTrue(third != second);
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("other", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(third, gui.activeSessionOf(ALICE).generation());
        assertTrue(gui.closeCalls().contains(new FakeGuiService.CloseCall(ALICE, second)));
    }

    @Test
    void backOverLiveSessionReopensPreviousPage() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        long first = navigator.open(ALICE, page("main", 1)).orElseThrow();
        long second = navigator.push(ALICE, page("second", 2)).orElseThrow();

        assertTrue(navigator.back(ALICE));

        long current = navigator.currentGeneration(ALICE).orElseThrow();
        assertTrue(current != first && current != second,
            "the re-opened page must carry a fresh upstream generation");
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("main", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(current, gui.activeSessionOf(ALICE).generation());
        assertTrue(gui.closeCalls().contains(new FakeGuiService.CloseCall(ALICE, second)));
    }

    @Test
    void replaceOverLiveSessionSwapsTopWithFreshGeneration() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        long first = navigator.open(ALICE, page("main", 1)).orElseThrow();

        long replaced = navigator.replace(ALICE, page("third", 3)).orElseThrow();

        assertTrue(replaced != first);
        assertEquals(1, navigator.depth(ALICE));
        assertEquals("third", navigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(replaced, gui.activeSessionOf(ALICE).generation());
        assertTrue(gui.closeCalls().contains(new FakeGuiService.CloseCall(ALICE, first)));
    }

    @Test
    void failedReopenKeepsTrackedStack() {
        FakeGuiService pushGui = new FakeGuiService();
        GuiNavigator pushNavigator = new GuiNavigator(pushGui);
        long pushFirst = pushNavigator.open(ALICE, page("main", 1)).orElseThrow();
        pushGui.rejectNextOpen();

        assertTrue(pushNavigator.push(ALICE, page("second", 2)).isEmpty());
        assertEquals(1, pushNavigator.depth(ALICE));
        assertEquals("main", pushNavigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(pushFirst,
            pushNavigator.currentGeneration(ALICE).orElseThrow().longValue());
        assertTrue(pushGui.closeCalls().contains(new FakeGuiService.CloseCall(ALICE, pushFirst)));

        FakeGuiService backGui = new FakeGuiService();
        GuiNavigator backNavigator = new GuiNavigator(backGui);
        backNavigator.open(ALICE, page("main", 1)).orElseThrow();
        long backSecond = backNavigator.push(ALICE, page("second", 2)).orElseThrow();
        backGui.rejectNextOpen();

        assertFalse(backNavigator.back(ALICE));
        assertEquals(2, backNavigator.depth(ALICE));
        assertEquals("second", backNavigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(backSecond,
            backNavigator.currentGeneration(ALICE).orElseThrow().longValue());
        assertTrue(backGui.closeCalls().contains(new FakeGuiService.CloseCall(ALICE, backSecond)));

        FakeGuiService replaceGui = new FakeGuiService();
        GuiNavigator replaceNavigator = new GuiNavigator(replaceGui);
        long replaceFirst = replaceNavigator.open(ALICE, page("main", 1)).orElseThrow();
        replaceGui.rejectNextOpen();

        assertTrue(replaceNavigator.replace(ALICE, page("third", 3)).isEmpty());
        assertEquals(1, replaceNavigator.depth(ALICE));
        assertEquals("main", replaceNavigator.currentPage(ALICE).orElseThrow().id());
        assertEquals(replaceFirst,
            replaceNavigator.currentGeneration(ALICE).orElseThrow().longValue());
        assertTrue(replaceGui.closeCalls()
            .contains(new FakeGuiService.CloseCall(ALICE, replaceFirst)));
    }

    @Test
    void untrackedForeignSessionIsNeverClosed() {
        FakeGuiService gui = new FakeGuiService();
        gui.openInventory(page("foreign", 1).toArgument(BOB));
        long foreignGeneration = gui.activeSessionOf(BOB).generation();
        GuiNavigator navigator = new GuiNavigator(gui);

        assertTrue(navigator.push(BOB, page("intruder", 2)).isEmpty());
        assertTrue(navigator.open(BOB, page("intruder", 2)).isEmpty());

        assertTrue(gui.closeCalls().isEmpty(), "a foreign session must never be closed");
        assertEquals(foreignGeneration, gui.activeSessionOf(BOB).generation());
        assertEquals(0, navigator.depth(BOB));
    }

    @Test
    void liveSessionRolloverStaysPerPlayer() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        navigator.open(ALICE, page("main", 1)).orElseThrow();
        long bobGeneration = navigator.open(BOB, page("main", 1)).orElseThrow();

        long aliceSecond = navigator.push(ALICE, page("second", 2)).orElseThrow();

        assertEquals(bobGeneration, navigator.currentGeneration(BOB).orElseThrow().longValue());
        assertEquals(bobGeneration, gui.activeSessionOf(BOB).generation());
        assertEquals(1, navigator.depth(BOB));
        assertEquals(aliceSecond, gui.activeSessionOf(ALICE).generation());
        for (FakeGuiService.CloseCall close : gui.closeCalls()) {
            assertTrue(!close.playerUuid().equals(BOB), "rollover must not touch the other player");
        }
    }

    @Test
    void backOnFirstPageClosesSession() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, page("main", 1)).orElseThrow();

        assertFalse(navigator.back(ALICE));
        assertEquals(0, navigator.depth(ALICE));
        assertTrue(navigator.currentPage(ALICE).isEmpty());
        assertEquals(List.of(new FakeGuiService.CloseCall(ALICE, generation)), gui.closeCalls());
    }

    @Test
    void validClickDispatchesOnlyAfterValidateClick() {
        FakeGuiService gui = new FakeGuiService();
        AtomicInteger calls = new AtomicInteger();
        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(10, click -> {
                calls.incrementAndGet();
                assertEquals(ALICE, click.playerUuid());
                assertEquals(10, click.slot());
                assertEquals("main", click.pageId());
            })));
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, withButton).orElseThrow();

        navigator.handleClick(ALICE, generation, 10);

        assertEquals(1, calls.get());
        assertFalse(gui.validateCalls().isEmpty(), "every click must go through validateClick first");
    }

    @Test
    void protectedBoundSlotStillDispatchesButton() {
        FakeGuiService gui = new FakeGuiService();
        AtomicInteger calls = new AtomicInteger();
        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(11, click -> {
                calls.incrementAndGet();
                assertEquals(ALICE, click.playerUuid());
                assertEquals(11, click.slot());
                assertEquals("main", click.pageId());
            })));
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, withButton).orElseThrow();

        navigator.handleClick(ALICE, generation, 11);

        assertEquals(1, calls.get(),
            "a bound protected slot reports SLOT_PROTECTED upstream but must still dispatch");
        assertFalse(gui.validateCalls().isEmpty(), "every click must go through validateClick first");
    }

    @Test
    void protectedUnboundSlotNeverDispatches() {
        AtomicInteger calls = new AtomicInteger();
        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(10, click -> calls.incrementAndGet())));
        GuiNavigator navigator = new GuiNavigator(new SlotProtectedGuiService(11));
        long generation = navigator.open(ALICE, withButton).orElseThrow();

        navigator.handleClick(ALICE, generation, 11);

        assertEquals(0, calls.get(),
            "SLOT_PROTECTED without a page binding must not dispatch");
    }

    @Test
    void playerInventorySlotNeverDispatches() {
        FakeGuiService gui = new FakeGuiService();
        AtomicInteger calls = new AtomicInteger();
        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(10, click -> calls.incrementAndGet())));
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, withButton).orElseThrow();

        navigator.handleClick(ALICE, generation, 27);
        navigator.handleClick(ALICE, generation, 54);

        assertEquals(0, calls.get(),
            "player-inventory raw slots are outside the session and must not dispatch");
    }

    @Test
    void staleProtectedClickNeverDispatches() {
        FakeGuiService gui = new FakeGuiService();
        AtomicInteger calls = new AtomicInteger();
        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(10, click -> calls.incrementAndGet())));
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, withButton).orElseThrow();

        navigator.handleClick(ALICE, generation + 999L, 10);

        assertEquals(0, calls.get(), "stale generation must not dispatch");
    }

    @Test
    void closedSessionProtectedClickNeverDispatches() {
        FakeGuiService gui = new FakeGuiService();
        AtomicInteger calls = new AtomicInteger();
        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(10, click -> calls.incrementAndGet())));
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, withButton).orElseThrow();

        navigator.close(ALICE);
        navigator.handleClick(ALICE, generation, 10);

        assertEquals(0, calls.get(), "click after close must not dispatch");
    }

    @Test
    void staleWrongSlotAndClosedClicksNeverReachCallback() {
        FakeGuiService gui = new FakeGuiService();
        AtomicInteger calls = new AtomicInteger();
        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(10, click -> calls.incrementAndGet())));
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, withButton).orElseThrow();

        navigator.handleClick(ALICE, generation + 999L, 10);
        assertEquals(0, calls.get(), "stale generation must not dispatch");

        navigator.handleClick(ALICE, generation, 11);
        assertEquals(0, calls.get(), "slot without a binding must not dispatch");

        navigator.close(ALICE);
        navigator.handleClick(ALICE, generation, 10);
        assertEquals(0, calls.get(), "click after close must not dispatch");
    }

    @Test
    void rejectedValidateClickNeverReachesCallback() {
        FakeGuiService gui = new FakeGuiService();
        gui.rejectAllClicks();
        AtomicInteger calls = new AtomicInteger();
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, page("main", 10)).orElseThrow();
        GuiNavigator ignored = navigator;
        assertTrue(ignored.depth(ALICE) == 1);

        GuiPage withButton = GuiPage.of("main", "t", 27,
            List.of(new GuiButton(10, click -> calls.incrementAndGet())));
        navigator.replace(ALICE, withButton);
        long current = navigator.currentGeneration(ALICE).orElseThrow();
        assertTrue(current != generation || current == generation);

        navigator.handleClick(ALICE, current, 10);
        assertEquals(0, calls.get(), "rejected validateClick must not dispatch");
    }

    @Test
    void twoPlayersAreFullyIsolated() {
        FakeGuiService gui = new FakeGuiService();
        AtomicInteger aliceCalls = new AtomicInteger();
        AtomicInteger bobCalls = new AtomicInteger();
        GuiNavigator navigator = new GuiNavigator(gui);
        long aliceGen = navigator.open(ALICE, GuiPage.of("a", "t", 27,
            List.of(new GuiButton(1, click -> aliceCalls.incrementAndGet())))).orElseThrow();
        long bobGen = navigator.open(BOB, GuiPage.of("b", "t", 27,
            List.of(new GuiButton(2, click -> bobCalls.incrementAndGet())))).orElseThrow();

        navigator.handleClick(ALICE, aliceGen, 1);
        navigator.handleClick(BOB, bobGen, 2);
        navigator.handleClick(ALICE, bobGen, 2);
        navigator.handleClick(BOB, aliceGen, 1);

        assertEquals(1, aliceCalls.get());
        assertEquals(1, bobCalls.get());
        assertEquals(1, navigator.depth(ALICE));
        assertEquals(1, navigator.depth(BOB));
    }

    @Test
    void unavailableServiceAndOpenFailuresStayFailClosed() {
        GuiNavigator missing = new GuiNavigator(null);
        assertFalse(missing.isAvailable());
        assertTrue(missing.open(ALICE, page("main", 1)).isEmpty());
        assertEquals(0, missing.depth(ALICE));
        missing.handleClick(ALICE, 1L, 1);
        missing.close(ALICE);
        missing.closeAll();

        FakeGuiService gui = new FakeGuiService();
        gui.rejectNextOpen();
        GuiNavigator navigator = new GuiNavigator(gui);
        assertTrue(navigator.open(ALICE, page("main", 1)).isEmpty());
        assertEquals(0, navigator.depth(ALICE));

        FakeGuiService exploding = new FakeGuiService();
        exploding.throwOnOpen();
        GuiNavigator fragile = new GuiNavigator(exploding);
        assertTrue(fragile.open(ALICE, page("main", 1)).isEmpty());
        assertEquals(0, fragile.depth(ALICE));
    }

    @Test
    void disableClosesEveryTrackedSessionWithExactIdentityAndStaysIdempotent() {
        FakeGuiService gui = new FakeGuiService();
        GuiNavigator navigator = new GuiNavigator(gui);
        long aliceGen = navigator.open(ALICE, page("main", 1)).orElseThrow();
        long bobGen = navigator.open(BOB, page("main", 1)).orElseThrow();
        UUID stranger = UUID.randomUUID();
        FakeGuiService strangerGui = gui;
        assertTrue(strangerGui.activeSessionOf(stranger) == null);

        navigator.closeAll();

        assertEquals(0, navigator.depth(ALICE));
        assertEquals(0, navigator.depth(BOB));
        assertTrue(navigator.trackedPlayers().isEmpty());
        assertTrue(gui.closeCalls().contains(new FakeGuiService.CloseCall(ALICE, aliceGen)));
        assertTrue(gui.closeCalls().contains(new FakeGuiService.CloseCall(BOB, bobGen)));
        assertEquals(0L, gui.shutdownCount(), "disable must never shut down the shared provider");

        int closesAfterFirst = gui.closeCalls().size();
        navigator.closeAll();
        assertEquals(closesAfterFirst, gui.closeCalls().size(), "repeated disable must be idempotent");

        navigator.handleClick(ALICE, aliceGen, 1);
    }

    @Test
    void closeIsIdempotentAndCallbackExceptionsStayContained() {
        FakeGuiService gui = new FakeGuiService();
        GuiPage exploding = GuiPage.of("main", "t", 27, List.of(new GuiButton(5, click -> {
            throw new RuntimeException("button boom");
        })));
        GuiNavigator navigator = new GuiNavigator(gui);
        long generation = navigator.open(ALICE, exploding).orElseThrow();

        navigator.handleClick(ALICE, generation, 5);

        navigator.close(ALICE);
        int closes = gui.closeCalls().size();
        navigator.close(ALICE);
        assertEquals(closes, gui.closeCalls().size(), "repeated close must not re-issue closeInventory");
    }

    /**
     * Upstream stand-in whose session protects {@code extraProtected} slots
     * the tracked page never bound, reporting them exactly the way
     * production does: a {@code SLOT_PROTECTED} rejection. Anything in range
     * and unprotected stays allowed; stale generations stay rejected.
     */
    private static final class SlotProtectedGuiService implements com.smile.acelib.gui.GuiService {
        private final java.util.Set<Integer> extraProtected;
        private final java.util.concurrent.atomic.AtomicLong generations = new java.util.concurrent.atomic.AtomicLong(500L);
        private final java.util.Map<UUID, com.smile.acelib.gui.GuiSession> active =
            new java.util.concurrent.ConcurrentHashMap<>();

        SlotProtectedGuiService(int... slots) {
            java.util.Set<Integer> copy = new java.util.HashSet<>();
            for (int slot : slots) {
                copy.add(slot);
            }
            this.extraProtected = java.util.Set.copyOf(copy);
        }

        @Override
        public com.smile.acelib.gui.GuiResult openInventory(com.smile.acelib.gui.GuiArgument arg) {
            long generation = generations.incrementAndGet();
            java.util.Set<Integer> protectedSlots = new java.util.HashSet<>(arg.protectedSlots());
            protectedSlots.addAll(extraProtected);
            com.smile.acelib.gui.GuiSession session = new com.smile.acelib.gui.GuiSession(
                arg.playerUuid(), generation, "stub", arg.title(), arg.size(),
                java.util.Set.copyOf(protectedSlots));
            active.put(arg.playerUuid(), session);
            return com.smile.acelib.gui.GuiResult.success(session);
        }

        @Override
        public com.smile.acelib.gui.GuiResult closeInventory(UUID uuid, long generation) {
            active.remove(uuid);
            return com.smile.acelib.gui.GuiResult.rejected("SESSION_NOT_FOUND", "closed by stub");
        }

        @Override
        public com.smile.acelib.gui.GuiResult getActiveSession(UUID uuid) {
            com.smile.acelib.gui.GuiSession current = active.get(uuid);
            return current == null
                ? com.smile.acelib.gui.GuiResult.rejected("SESSION_NOT_FOUND", "no active session")
                : com.smile.acelib.gui.GuiResult.allowed(current);
        }

        @Override
        public com.smile.acelib.gui.GuiResult validateClick(UUID uuid, long generation, int slot) {
            com.smile.acelib.gui.GuiSession current = active.get(uuid);
            if (current == null || current.generation() != generation) {
                return com.smile.acelib.gui.GuiResult.rejected(
                    com.smile.acelib.gui.GuiErrorCode.GENERATION_MISMATCH, "stale click");
            }
            if (current.protectedSlots().contains(slot)) {
                return com.smile.acelib.gui.GuiResult.rejected(
                    com.smile.acelib.gui.GuiErrorCode.SLOT_PROTECTED, "slot " + slot + " is protected");
            }
            return com.smile.acelib.gui.GuiResult.allowed(current);
        }

        @Override
        public String getModuleStatus() {
            return "slot-protected-stub";
        }

        @Override
        public void shutdown() {
        }
    }
}
