package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the immutable page value: identity, render data and
 * slot button bindings. Fails until the framework page exists.
 */
class GuiPageTest {

    @Test
    void pageExposesImmutableRenderDataAndButtons() {
        GuiPage page = GuiPage.of("main", "Land Menu", 27, List.of(
            new GuiButton(10, click -> { }),
            new GuiButton(16, click -> { })));

        assertEquals("main", page.id());
        assertEquals("Land Menu", page.title());
        assertEquals(27, page.size());
        assertEquals(Set.of(10, 16), page.slots());
        assertTrue(page.buttonAt(10).isPresent());
        assertTrue(page.buttonAt(11).isEmpty());
        assertEquals(10, page.buttonAt(10).orElseThrow().slot());
    }

    @Test
    void pageButtonsMapIsNotModifiable() {
        GuiPage page = GuiPage.of("main", "Land Menu", 9, List.of(
            new GuiButton(0, click -> { })));

        Map<Integer, GuiButton> buttons = page.buttons();
        assertThrows(UnsupportedOperationException.class,
            () -> buttons.put(1, new GuiButton(1, click -> { })));
    }

    @Test
    void pageBuildsGuiArgumentForPlayer() {
        UUID player = UUID.randomUUID();
        GuiPage page = GuiPage.of("main", "Land Menu", 9, List.of(
            new GuiButton(3, click -> { })));

        var arg = page.toArgument(player);

        assertEquals(player, arg.playerUuid());
        assertEquals("Land Menu", arg.title());
        assertEquals(9, arg.size());
        assertEquals(Set.of(3), arg.protectedSlots());
    }

    @Test
    void duplicateSlotsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GuiPage.of("main", "t", 9, List.of(
            new GuiButton(4, click -> { }),
            new GuiButton(4, click -> { }))));
    }

    @Test
    void clickContextCarriesPlayerGenerationSlotAndPage() {
        UUID player = UUID.randomUUID();
        GuiClickContext click = new GuiClickContext(player, 42L, 7, "main");

        assertEquals(player, click.playerUuid());
        assertEquals(42L, click.generation());
        assertEquals(7, click.slot());
        assertEquals("main", click.pageId());
        assertTrue(click.pageId() != null && !click.pageId().isEmpty());
        assertEquals(Optional.of("main"), Optional.of(click.pageId()));
    }
}
