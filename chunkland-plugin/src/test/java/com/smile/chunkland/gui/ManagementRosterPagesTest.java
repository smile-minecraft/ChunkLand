package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.adapter.gui.ManagementGuiTextProvider;
import com.smile.chunkland.gui.ManagementRosterPages.Entry;
import com.smile.chunkland.gui.ManagementRosterPages.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagementRosterPagesTest {

    private static final UUID VIEWER = UUID.randomUUID();

    /** Seam double recording every call as one readable line. */
    private static final class Recording implements ManagementRosterPages.Actions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void openRoster(GuiClickContext click, Kind kind, int page) {
            calls.add("roster " + kind + " " + page);
        }

        @Override
        public void openPicker(GuiClickContext click, Kind kind, int page) {
            calls.add("picker " + kind + " " + page);
        }

        @Override
        public void add(GuiClickContext click, Kind kind, Entry entry) {
            calls.add("add " + kind + " " + entry.name());
        }

        @Override
        public void remove(GuiClickContext click, Kind kind, Entry entry) {
            calls.add("remove " + kind + " " + entry.name());
        }

        @Override
        public void back(GuiClickContext click) {
            calls.add("back");
        }
    }

    private static List<Entry> players(int count) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new Entry(UUID.randomUUID(), String.format("Player%03d", i)));
        }
        return out;
    }

    private static void click(GuiPage page, int slot) {
        page.buttonAt(slot).orElseThrow().action()
                .handle(new GuiClickContext(VIEWER, 1L, slot, page.id()));
    }

    private static GuiPage.RenderItem itemAt(GuiPage page, int slot) {
        return page.renderItems().stream().filter(item -> item.slot() == slot)
                .findFirst().orElseThrow();
    }

    @Test
    void rootPageKeepsThePermissionEntryAndAddsBothRosters() {
        Recording seam = new Recording();
        List<String> details = new ArrayList<>();
        ManagementGuiActions actions = new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
                details.add("details");
            }

            @Override
            public void back(GuiClickContext click) {
            }

            @Override
            public void requestChange(GuiClickContext click,
                    com.smile.chunkland.api.permission.ProtectionActionType action) {
            }
        };
        GuiPage root = ManagementRosterPages.rootPage(actions,
                ManagementGuiTextProvider.fallbackTexts(), seam, ManagementRosterTexts.english());

        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, root.id());
        click(root, ManagementGuiPages.ENTRY_SLOT);
        click(root, ManagementRosterPages.MEMBERS_SLOT);
        click(root, ManagementRosterPages.BANS_SLOT);
        assertEquals(List.of("details"), details);
        assertEquals(List.of("roster MEMBERS 0", "roster BANS 0"), seam.calls);
        assertEquals("Members", itemAt(root, ManagementRosterPages.MEMBERS_SLOT).name());
        assertEquals("Banned players", itemAt(root, ManagementRosterPages.BANS_SLOT).name());
    }

    @Test
    void rosterListsPlayersAndEveryClickReachesTheSeam() {
        Recording seam = new Recording();
        List<Entry> members = players(2);
        GuiPage page = ManagementRosterPages.rosterPage(Kind.MEMBERS, members, 0, seam,
                ManagementRosterTexts.english());

        assertEquals(ManagementRosterPages.MEMBERS_PAGE_ID, page.id());
        assertEquals("Land Members (2)", page.title());
        assertEquals("Player001", itemAt(page, 1).name());
        assertEquals(List.of("Click to untrust this player."), itemAt(page, 1).lore(),
                "the item must say what a click does");
        click(page, 1);
        click(page, ManagementRosterPages.ADD_SLOT);
        click(page, ManagementRosterPages.BACK_SLOT);
        assertEquals(List.of("remove MEMBERS Player001", "picker MEMBERS 0", "back"), seam.calls);
        assertTrue(page.buttonAt(ManagementRosterPages.NEXT_SLOT).isEmpty(),
                "one screen needs no paging");
        assertTrue(page.buttonAt(ManagementRosterPages.PREVIOUS_SLOT).isEmpty());
    }

    @Test
    void longRosterPagesAndClampsAnOutOfRangeIndex() {
        Recording seam = new Recording();
        List<Entry> banned = players(ManagementRosterPages.ENTRIES_PER_PAGE + 5);

        GuiPage first = ManagementRosterPages.rosterPage(Kind.BANS, banned, 0, seam,
                ManagementRosterTexts.english());
        assertEquals(ManagementRosterPages.BANS_PAGE_ID, first.id());
        assertEquals("Player044", itemAt(first, 44).name());
        click(first, ManagementRosterPages.NEXT_SLOT);

        GuiPage last = ManagementRosterPages.rosterPage(Kind.BANS, banned, 99, seam,
                ManagementRosterTexts.english());
        assertEquals("Player045", itemAt(last, 0).name(), "page 99 clamps to the last screen");
        assertTrue(last.buttonAt(ManagementRosterPages.NEXT_SLOT).isEmpty());
        click(last, ManagementRosterPages.PREVIOUS_SLOT);
        click(last, 4);

        assertEquals(List.of("roster BANS 1", "roster BANS 0", "remove BANS Player049"),
                seam.calls);
        assertEquals(2, ManagementRosterPages.pageCount(banned.size()));
        assertEquals(1, ManagementRosterPages.pageCount(0));
    }

    @Test
    void emptyRosterAndEmptyPickerSaySoWithoutAClickableEntry() {
        Recording seam = new Recording();
        GuiPage roster = ManagementRosterPages.rosterPage(Kind.BANS, List.of(), 0, seam,
                ManagementRosterTexts.english());
        assertEquals("Nobody is banned from this land", itemAt(roster, 0).name());
        click(roster, 0);

        GuiPage picker = ManagementRosterPages.pickerPage(Kind.MEMBERS, List.of(), 0, seam,
                ManagementRosterTexts.english());
        assertEquals(ManagementRosterPages.MEMBER_PICKER_PAGE_ID, picker.id());
        assertEquals("No online player to choose", itemAt(picker, 0).name());
        click(picker, 0);

        assertTrue(seam.calls.isEmpty(), "a notice item must never reach the seam");
        assertFalse(picker.buttonAt(ManagementRosterPages.ADD_SLOT).isPresent(),
                "the picker has no add button of its own");
    }

    @Test
    void pickerItemsAddThatPlayer() {
        Recording seam = new Recording();
        GuiPage picker = ManagementRosterPages.pickerPage(Kind.BANS, players(3), 0, seam,
                ManagementRosterTexts.english());

        assertEquals("Choose a player to ban", picker.title());
        assertEquals(List.of("Click to ban this player."), itemAt(picker, 2).lore());
        click(picker, 2);
        click(picker, ManagementRosterPages.BACK_SLOT);

        assertEquals(List.of("add BANS Player002", "back"), seam.calls);
    }
}
