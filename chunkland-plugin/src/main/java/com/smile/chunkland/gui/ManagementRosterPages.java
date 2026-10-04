package com.smile.chunkland.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Member and ban roster pages over the shared {@link GuiNavigator}.
 *
 * <p>The first layer gains two entries next to the permission detail: the
 * players trusted on the land and the players banned from it. A roster page
 * lists one item per player (click removes that player), an add button that
 * opens the online-player picker, and paging when the list is longer than
 * one screen. Every button only reaches the caller-owned {@link Actions}
 * seam: the pages never mutate domain state, never resolve a player, and
 * never offer an {@code EVERYONE} entry.
 *
 * <p>All pages are Bukkit-free immutable values, and every visible string
 * arrives through the injected texts.
 */
public final class ManagementRosterPages {

    /** Which roster a page shows. */
    public enum Kind {
        /** Players trusted on the land. */
        MEMBERS,
        /** Players banned from the land. */
        BANS
    }

    /**
     * One listed player.
     *
     * @param id player id; never {@code null}
     * @param name display name; never {@code null} (the caller supplies a
     *             placeholder when the name is unknown)
     */
    public record Entry(UUID id, String name) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Caller-owned seam behind the roster buttons. The production wiring
     * re-checks the management gate and delegates every change to the
     * existing trust and ban services.
     */
    public interface Actions {

        /** Open (or page through) one roster. */
        void openRoster(GuiClickContext click, Kind kind, int page);

        /** Open (or page through) the online-player picker for one roster. */
        void openPicker(GuiClickContext click, Kind kind, int page);

        /** A picker item was clicked: add that player to the roster. */
        void add(GuiClickContext click, Kind kind, Entry entry);

        /** A roster item was clicked: remove that player from the roster. */
        void remove(GuiClickContext click, Kind kind, Entry entry);

        /** Back button: return to the previous page. */
        void back(GuiClickContext click);
    }

    public static final String MEMBERS_PAGE_ID = "chunkland:land-manage-members";
    public static final String BANS_PAGE_ID = "chunkland:land-manage-bans";
    public static final String MEMBER_PICKER_PAGE_ID = "chunkland:land-manage-member-picker";
    public static final String BAN_PICKER_PAGE_ID = "chunkland:land-manage-ban-picker";

    /** Root-page slot of the member roster entry. */
    public static final int MEMBERS_SLOT = 13;

    /** Root-page slot of the ban roster entry. */
    public static final int BANS_SLOT = 15;

    /** Six chest rows: five for players, one for controls. */
    static final int PAGE_SIZE = 54;

    /** Players shown per screen. */
    public static final int ENTRIES_PER_PAGE = 45;

    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 47;
    public static final int ADD_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private ManagementRosterPages() {
    }

    /** Page id of one roster. */
    public static String rosterPageId(Kind kind) {
        return kind == Kind.MEMBERS ? MEMBERS_PAGE_ID : BANS_PAGE_ID;
    }

    /** Page id of one roster's picker. */
    public static String pickerPageId(Kind kind) {
        return kind == Kind.MEMBERS ? MEMBER_PICKER_PAGE_ID : BAN_PICKER_PAGE_ID;
    }

    /**
     * First-layer page with the permission entry plus both roster entries.
     * Keeps the identity, title and permission entry of
     * {@link ManagementGuiPages#rootPage}.
     */
    public static GuiPage rootPage(ManagementGuiActions actions, ManagementGuiTexts texts,
            Actions roster, ManagementRosterTexts rosterTexts) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        Objects.requireNonNull(texts, "texts");
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(rosterTexts, "rosterTexts");
        List<GuiButton> buttons = List.of(
                new GuiButton(ManagementGuiPages.ENTRY_SLOT, click -> {
                    Objects.requireNonNull(click, "click");
                    seam.openDetails(click);
                }),
                new GuiButton(MEMBERS_SLOT, click -> {
                    Objects.requireNonNull(click, "click");
                    roster.openRoster(click, Kind.MEMBERS, 0);
                }),
                new GuiButton(BANS_SLOT, click -> {
                    Objects.requireNonNull(click, "click");
                    roster.openRoster(click, Kind.BANS, 0);
                }));
        String membersName = rosterTexts.text(ManagementRosterTexts.MEMBERS_ENTRY_NAME);
        String membersHint = rosterTexts.text(ManagementRosterTexts.MEMBERS_ENTRY_HINT);
        String bansName = rosterTexts.text(ManagementRosterTexts.BANS_ENTRY_NAME);
        String bansHint = rosterTexts.text(ManagementRosterTexts.BANS_ENTRY_HINT);
        List<String> lines = new ArrayList<>(texts.entryLines());
        lines.add(membersHint);
        lines.add(bansHint);
        List<GuiPage.RenderItem> items = List.of(
                new GuiPage.RenderItem(ManagementGuiPages.ENTRY_SLOT, texts.entryName(),
                        texts.entryLines(), "entry"),
                new GuiPage.RenderItem(MEMBERS_SLOT, membersName, List.of(membersHint),
                        "members"),
                new GuiPage.RenderItem(BANS_SLOT, bansName, List.of(bansHint), "bans"));
        return GuiPage.of(ManagementGuiPages.ROOT_PAGE_ID, texts.rootTitle(),
                ManagementGuiPages.ROOT_SIZE, buttons, lines, items);
    }

    /**
     * One screen of a roster: a clickable item per player, the add button,
     * back, and paging when needed. An out-of-range page index is clamped,
     * so a roster that shrank while it was open still renders.
     */
    public static GuiPage rosterPage(Kind kind, List<Entry> entries, int page, Actions seam,
            ManagementRosterTexts texts) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(seam, "seam");
        Objects.requireNonNull(texts, "texts");
        boolean members = kind == Kind.MEMBERS;
        int shown = clampPage(page, entries.size());
        List<GuiButton> buttons = new ArrayList<>();
        List<GuiPage.RenderItem> items = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        String removeLine = texts.text(members
                ? ManagementRosterTexts.MEMBERS_REMOVE_LINE
                : ManagementRosterTexts.BANS_REMOVE_LINE);
        int from = shown * ENTRIES_PER_PAGE;
        int to = Math.min(entries.size(), from + ENTRIES_PER_PAGE);
        for (int index = from; index < to; index++) {
            Entry entry = entries.get(index);
            int slot = index - from;
            buttons.add(new GuiButton(slot, click -> {
                Objects.requireNonNull(click, "click");
                seam.remove(click, kind, entry);
            }));
            items.add(new GuiPage.RenderItem(slot, entry.name(), List.of(removeLine),
                    members ? "member" : "banned"));
            lines.add(entry.name());
        }
        if (entries.isEmpty()) {
            String empty = texts.text(members
                    ? ManagementRosterTexts.MEMBERS_EMPTY
                    : ManagementRosterTexts.BANS_EMPTY);
            buttons.add(inert(0));
            items.add(new GuiPage.RenderItem(0, empty, List.of(), "empty"));
            lines.add(empty);
        }
        String addName = texts.text(members
                ? ManagementRosterTexts.MEMBERS_ADD_NAME
                : ManagementRosterTexts.BANS_ADD_NAME);
        String addHint = texts.text(members
                ? ManagementRosterTexts.MEMBERS_ADD_HINT
                : ManagementRosterTexts.BANS_ADD_HINT);
        buttons.add(new GuiButton(ADD_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.openPicker(click, kind, 0);
        }));
        items.add(new GuiPage.RenderItem(ADD_SLOT, addName, List.of(addHint), "add"));
        addControls(buttons, items, texts, shown, entries.size(),
                target -> click -> seam.openRoster(click, kind, target), seam);
        String title = texts.text(members
                ? ManagementRosterTexts.MEMBERS_TITLE
                : ManagementRosterTexts.BANS_TITLE, Map.of("count", entries.size()));
        return GuiPage.of(rosterPageId(kind), title, PAGE_SIZE, buttons, lines, items);
    }

    /**
     * One screen of the online-player picker for a roster: a clickable item
     * per candidate, back, and paging when needed. With no candidate the
     * page says so and points at the chat command for offline players.
     */
    public static GuiPage pickerPage(Kind kind, List<Entry> candidates, int page, Actions seam,
            ManagementRosterTexts texts) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(seam, "seam");
        Objects.requireNonNull(texts, "texts");
        boolean members = kind == Kind.MEMBERS;
        int shown = clampPage(page, candidates.size());
        List<GuiButton> buttons = new ArrayList<>();
        List<GuiPage.RenderItem> items = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        String pickLine = texts.text(members
                ? ManagementRosterTexts.MEMBERS_PICK_LINE
                : ManagementRosterTexts.BANS_PICK_LINE);
        int from = shown * ENTRIES_PER_PAGE;
        int to = Math.min(candidates.size(), from + ENTRIES_PER_PAGE);
        for (int index = from; index < to; index++) {
            Entry entry = candidates.get(index);
            int slot = index - from;
            buttons.add(new GuiButton(slot, click -> {
                Objects.requireNonNull(click, "click");
                seam.add(click, kind, entry);
            }));
            items.add(new GuiPage.RenderItem(slot, entry.name(), List.of(pickLine), "member"));
            lines.add(entry.name());
        }
        if (candidates.isEmpty()) {
            String empty = texts.text(ManagementRosterTexts.PICKER_EMPTY);
            String hint = texts.text(ManagementRosterTexts.PICKER_EMPTY_HINT);
            buttons.add(inert(0));
            items.add(new GuiPage.RenderItem(0, empty, List.of(hint), "empty"));
            lines.add(empty);
            lines.add(hint);
        }
        addControls(buttons, items, texts, shown, candidates.size(),
                target -> click -> seam.openPicker(click, kind, target), seam);
        String title = texts.text(members
                ? ManagementRosterTexts.MEMBERS_PICKER_TITLE
                : ManagementRosterTexts.BANS_PICKER_TITLE);
        return GuiPage.of(pickerPageId(kind), title, PAGE_SIZE, buttons, lines, items);
    }

    /** Number of screens needed for {@code total} players; at least one. */
    public static int pageCount(int total) {
        return Math.max(1, (total + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    private static int clampPage(int page, int total) {
        return Math.max(0, Math.min(page, pageCount(total) - 1));
    }

    /** Builds the click action for a target page index. */
    @FunctionalInterface
    private interface PageTurn {
        GuiAction to(int page);
    }

    private static void addControls(List<GuiButton> buttons, List<GuiPage.RenderItem> items,
            ManagementRosterTexts texts, int shown, int total, PageTurn turn, Actions seam) {
        buttons.add(new GuiButton(BACK_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.back(click);
        }));
        items.add(new GuiPage.RenderItem(BACK_SLOT, texts.text(ManagementRosterTexts.BACK),
                List.of(), "back"));
        if (shown > 0) {
            buttons.add(new GuiButton(PREVIOUS_SLOT, turn.to(shown - 1)));
            items.add(new GuiPage.RenderItem(PREVIOUS_SLOT,
                    texts.text(ManagementRosterTexts.PREVIOUS), List.of(), "previous"));
        }
        if (shown + 1 < pageCount(total)) {
            buttons.add(new GuiButton(NEXT_SLOT, turn.to(shown + 1)));
            items.add(new GuiPage.RenderItem(NEXT_SLOT,
                    texts.text(ManagementRosterTexts.NEXT), List.of(), "next"));
        }
    }

    /** Protected slot with no effect, so a painted notice cannot be picked up. */
    private static GuiButton inert(int slot) {
        return new GuiButton(slot, click -> Objects.requireNonNull(click, "click"));
    }
}
