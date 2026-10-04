package com.smile.chunkland.adapter.gui;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.gui.GuiClickContext;
import com.smile.chunkland.gui.GuiNavigator;
import com.smile.chunkland.gui.GuiPage;
import com.smile.chunkland.gui.ManagementRosterPages;
import com.smile.chunkland.gui.ManagementRosterPages.Entry;
import com.smile.chunkland.gui.ManagementRosterPages.Kind;
import com.smile.chunkland.gui.ManagementRosterTexts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caller-side behaviour behind the member and ban roster pages.
 *
 * <p>The pages only emit clicks; this controller turns them into reads and
 * changes. Every open and every change re-checks the management gate over
 * the live snapshot, and a change goes through the same trust and ban
 * services the chat commands use, so the GUI can do nothing a command could
 * not. Rosters are read from the immutable authorisation snapshot; a
 * snapshot that is not loaded opens nothing rather than showing an empty
 * list that looks like "nobody".
 *
 * <p>A change completes on the persistence thread. The redraw and the chat
 * notice therefore hop back to the viewer's own thread through the injected
 * {@link Hop}, and one viewer can only have one change in flight at a time.
 * Every seam failure is absorbed: a click that cannot be served leaves the
 * current screen as it is.
 */
public final class ManagementRosterController {

    /** Navigator access plus the page painter owned by the caller. */
    public interface Navigation {
        /** @return the live navigator, or {@code null} when the GUI is unavailable */
        GuiNavigator navigator();

        void render(UUID viewer, long generation, GuiPage page);

        /** Back navigation shared with the other management pages. */
        void back(GuiClickContext click);
    }

    /** Memory-only roster reads over the immutable snapshots. */
    public interface Rosters {
        /** @return trusted players, or empty when the snapshot cannot answer */
        Optional<Set<UUID>> members(LandId landId);

        /** @return banned players, or empty when the snapshot cannot answer */
        Optional<Set<UUID>> bans(LandId landId);

        /** @return the owning player, or {@code null} for a server land or unknown land */
        UUID ownerOf(LandId landId);
    }

    /** Management gate for one command slot (trust, untrust, ban, unban). */
    @FunctionalInterface
    public interface Gate {
        boolean allows(UUID viewer, LandId landId, String slot);
    }

    /** One durable roster change; the same entry the chat command uses. */
    @FunctionalInterface
    public interface Change {
        CompletionStage<Void> apply(UUID actor, LandId landId, UUID target);
    }

    /** The four roster changes; a {@code null} entry reports unavailable. */
    public record Changes(Change trust, Change untrust, Change ban, Change unban) {
    }

    /** Player names and the online-player list; memory reads only. */
    public interface People {
        /** @return the player's name, or {@code null} when it is not known without blocking */
        String nameOf(UUID playerId);

        /** @return the players currently online */
        List<Entry> online();
    }

    /** Chat notice to the viewer; best effort. */
    @FunctionalInterface
    public interface Feedback {
        void tell(UUID viewer, String messageKey, Map<String, Object> vars);
    }

    /** Runs a task on the viewer's own thread; may throw when the viewer left. */
    @FunctionalInterface
    public interface Hop {
        void run(UUID viewer, Runnable task);
    }

    /** Visible copy for one viewer. */
    @FunctionalInterface
    public interface Texts {
        ManagementRosterTexts textsFor(UUID viewer);
    }

    /** Chat notice shown when the gate refuses a roster click. */
    public static final String DENIED_KEY = "gui.manage.roster.denied";

    private final Navigation navigation;
    private final Rosters rosters;
    private final Gate gate;
    private final Changes changes;
    private final People people;
    private final Feedback feedback;
    private final Hop hop;
    private final Texts texts;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public ManagementRosterController(Navigation navigation, Rosters rosters, Gate gate,
            Changes changes, People people, Feedback feedback, Hop hop, Texts texts) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.people = Objects.requireNonNull(people, "people");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.hop = Objects.requireNonNull(hop, "hop");
        this.texts = Objects.requireNonNull(texts, "texts");
    }

    /** Visible roster copy for one viewer; never {@code null}. */
    public ManagementRosterTexts textsFor(UUID viewer) {
        try {
            ManagementRosterTexts resolved = texts.textsFor(viewer);
            return resolved == null ? ManagementRosterTexts.english() : resolved;
        } catch (RuntimeException unresolved) {
            return ManagementRosterTexts.english();
        }
    }

    /** Button seam for the roster pages of one land. */
    public ManagementRosterPages.Actions actionsFor(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        return new ManagementRosterPages.Actions() {
            @Override
            public void openRoster(GuiClickContext click, Kind kind, int page) {
                guarded(() -> showRoster(click.playerUuid(), landId, kind, page));
            }

            @Override
            public void openPicker(GuiClickContext click, Kind kind, int page) {
                guarded(() -> showPicker(click.playerUuid(), landId, kind, page));
            }

            @Override
            public void add(GuiClickContext click, Kind kind, Entry entry) {
                guarded(() -> change(click.playerUuid(), landId, kind, entry, true));
            }

            @Override
            public void remove(GuiClickContext click, Kind kind, Entry entry) {
                guarded(() -> change(click.playerUuid(), landId, kind, entry, false));
            }

            @Override
            public void back(GuiClickContext click) {
                guarded(() -> navigation.back(click));
            }
        };
    }

    private static void guarded(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException absorbed) {
            // A click that cannot be served leaves the current screen as it is.
        }
    }

    /** Command slot whose gate guards a roster view or change. */
    private static String slot(Kind kind, boolean add) {
        if (kind == Kind.MEMBERS) {
            return add ? "trust" : "untrust";
        }
        return add ? "ban" : "unban";
    }

    private boolean allowed(UUID viewer, LandId landId, String slot) {
        try {
            return gate.allows(viewer, landId, slot);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private void showRoster(UUID viewer, LandId landId, Kind kind, int page) {
        GuiNavigator nav = navigation.navigator();
        if (nav == null) {
            return;
        }
        if (!allowed(viewer, landId, slot(kind, true))) {
            tell(viewer, DENIED_KEY, Map.of());
            return;
        }
        ManagementRosterTexts copy = textsFor(viewer);
        Optional<List<Entry>> entries = rosterEntries(landId, kind, copy);
        if (entries.isEmpty()) {
            return;
        }
        present(viewer, nav, ManagementRosterPages.rosterPage(kind, entries.get(), page,
                actionsFor(landId), copy));
    }

    private void showPicker(UUID viewer, LandId landId, Kind kind, int page) {
        GuiNavigator nav = navigation.navigator();
        if (nav == null) {
            return;
        }
        if (!allowed(viewer, landId, slot(kind, true))) {
            tell(viewer, DENIED_KEY, Map.of());
            return;
        }
        Optional<Set<UUID>> listed = roster(landId, kind);
        if (listed.isEmpty()) {
            return;
        }
        UUID owner = rosters.ownerOf(landId);
        List<Entry> candidates = new ArrayList<>();
        List<Entry> online = people.online();
        if (online != null) {
            for (Entry player : online) {
                if (player == null || player.id().equals(viewer) || player.id().equals(owner)
                        || listed.get().contains(player.id())) {
                    continue;
                }
                candidates.add(player);
            }
        }
        candidates.sort(BY_NAME);
        present(viewer, nav, ManagementRosterPages.pickerPage(kind, candidates, page,
                actionsFor(landId), textsFor(viewer)));
    }

    private static final Comparator<Entry> BY_NAME = Comparator
            .comparing((Entry entry) -> entry.name().toLowerCase(Locale.ROOT))
            .thenComparing(Entry::id);

    private Optional<Set<UUID>> roster(LandId landId, Kind kind) {
        Optional<Set<UUID>> found = kind == Kind.MEMBERS
                ? rosters.members(landId) : rosters.bans(landId);
        return found == null ? Optional.empty() : found;
    }

    private Optional<List<Entry>> rosterEntries(LandId landId, Kind kind,
            ManagementRosterTexts copy) {
        Optional<Set<UUID>> listed = roster(landId, kind);
        if (listed.isEmpty()) {
            return Optional.empty();
        }
        List<Entry> entries = new ArrayList<>(listed.get().size());
        for (UUID playerId : listed.get()) {
            if (playerId != null) {
                entries.add(new Entry(playerId, displayName(playerId, copy)));
            }
        }
        entries.sort(BY_NAME);
        return Optional.of(entries);
    }

    private String displayName(UUID playerId, ManagementRosterTexts copy) {
        String name;
        try {
            name = people.nameOf(playerId);
        } catch (RuntimeException unresolved) {
            name = null;
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        return copy.text(ManagementRosterTexts.UNKNOWN_PLAYER,
                Map.of("value", playerId.toString().substring(0, 8)));
    }

    /** Shows a page: in place when the viewer already sits on it, pushed otherwise. */
    private void present(UUID viewer, GuiNavigator nav, GuiPage page) {
        Optional<GuiPage> top = nav.currentPage(viewer);
        Optional<Long> generation = top.isPresent() && top.get().id().equals(page.id())
                ? nav.replace(viewer, page)
                : nav.push(viewer, page);
        if (generation.isPresent()) {
            navigation.render(viewer, generation.get(), page);
        }
    }

    private void change(UUID viewer, LandId landId, Kind kind, Entry entry, boolean add) {
        String slot = slot(kind, add);
        if (!allowed(viewer, landId, slot)) {
            tell(viewer, DENIED_KEY, Map.of());
            return;
        }
        Change selected = switch (slot) {
            case "trust" -> changes.trust();
            case "untrust" -> changes.untrust();
            case "ban" -> changes.ban();
            default -> changes.unban();
        };
        String failedKey = "command.land." + slot + ".failed";
        if (selected == null) {
            tell(viewer, failedKey, Map.of("reason", slot + ".unavailable"));
            return;
        }
        if (!inFlight.add(viewer)) {
            return;
        }
        CompletionStage<Void> stage;
        try {
            stage = selected.apply(viewer, landId, entry.id());
        } catch (RuntimeException failure) {
            stage = null;
        }
        if (stage == null) {
            inFlight.remove(viewer);
            tell(viewer, failedKey, Map.of("reason", slot + ".failed"));
            return;
        }
        stage.whenComplete((ignored, failure) -> {
            inFlight.remove(viewer);
            try {
                hop.run(viewer, () -> {
                    if (failure != null) {
                        tell(viewer, failedKey, Map.of("reason", slot + ".failed"));
                    } else {
                        tell(viewer, "command.land." + slot + ".success",
                                Map.of("player", entry.name()));
                    }
                    guarded(() -> refresh(viewer, landId, kind, add));
                });
            } catch (RuntimeException retired) {
                // The viewer left; there is no screen to refresh.
            }
        });
    }

    /**
     * Redraws the roster after a change. A viewer who picked a player is
     * taken back from the picker to the roster; a viewer who navigated
     * somewhere else in the meantime keeps the screen they chose.
     */
    private void refresh(UUID viewer, LandId landId, Kind kind, boolean add) {
        GuiNavigator nav = navigation.navigator();
        if (nav == null) {
            return;
        }
        Optional<GuiPage> top = nav.currentPage(viewer);
        if (top.isEmpty()) {
            return;
        }
        if (add && top.get().id().equals(ManagementRosterPages.pickerPageId(kind))) {
            if (!nav.back(viewer)) {
                return;
            }
            top = nav.currentPage(viewer);
        }
        if (top.isPresent() && top.get().id().equals(ManagementRosterPages.rosterPageId(kind))) {
            showRoster(viewer, landId, kind, 0);
        }
    }

    private void tell(UUID viewer, String key, Map<String, Object> vars) {
        try {
            feedback.tell(viewer, key, vars);
        } catch (RuntimeException ignored) {
            // A notice that cannot be sent never blocks the GUI.
        }
    }
}
