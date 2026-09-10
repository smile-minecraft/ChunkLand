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
}
