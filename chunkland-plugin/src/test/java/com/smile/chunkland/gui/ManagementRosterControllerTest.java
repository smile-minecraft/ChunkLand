package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.adapter.gui.ManagementGuiTextProvider;
import com.smile.chunkland.adapter.gui.ManagementRosterController;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.gui.ManagementRosterPages.Entry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * The roster controller turns page clicks into gated reads and changes: it
 * shows only what the snapshot confirms, changes only through the injected
 * services, and redraws once the durable change completes.
 */
class ManagementRosterControllerTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    /** Everything the controller talks to, with observable state. */
    private static final class Fixture {
        final FakeGuiService service = new FakeGuiService();
        final GuiNavigator navigator = new GuiNavigator(service);
        final Set<UUID> members = new HashSet<>();
        final Set<UUID> bans = new HashSet<>();
        final List<String> notices = new ArrayList<>();
        final List<String> gateChecks = new ArrayList<>();
        final List<CompletableFuture<Void>> pending = new ArrayList<>();
        final List<GuiPage> rendered = new ArrayList<>();
        boolean loaded = true;
        boolean gateOpen = true;
        boolean completeInline = true;
        final ManagementRosterController controller;

        Fixture() {
            ManagementRosterController.Navigation navigation =
                    new ManagementRosterController.Navigation() {
                        @Override
                        public GuiNavigator navigator() {
                            return navigator;
                        }

                        @Override
                        public void render(UUID viewer, long generation, GuiPage page) {
                            rendered.add(page);
                        }

                        @Override
                        public void back(GuiClickContext click) {
                            navigator.back(click.playerUuid());
                        }
                    };
            ManagementRosterController.Rosters rosters = new ManagementRosterController.Rosters() {
                @Override
                public Optional<Set<UUID>> members(LandId landId) {
                    return loaded ? Optional.of(Set.copyOf(members)) : Optional.empty();
                }

                @Override
                public Optional<Set<UUID>> bans(LandId landId) {
                    return loaded ? Optional.of(Set.copyOf(bans)) : Optional.empty();
                }

                @Override
                public UUID ownerOf(LandId landId) {
                    return OWNER;
                }
            };
            ManagementRosterController.People people = new ManagementRosterController.People() {
                @Override
                public String nameOf(UUID playerId) {
                    return ALICE.equals(playerId) ? "Alice" : null;
                }

                @Override
                public List<Entry> online() {
                    return List.of(new Entry(VIEWER, "Viewer"), new Entry(OWNER, "Owner"),
                            new Entry(ALICE, "Alice"), new Entry(BOB, "Bob"));
                }
            };
            controller = new ManagementRosterController(navigation, rosters,
                    (viewer, landId, slot) -> {
                        gateChecks.add(slot);
                        return gateOpen;
                    },
                    new ManagementRosterController.Changes(
                            change("trust", members, true), change("untrust", members, false),
                            change("ban", bans, true), change("unban", bans, false)),
                    people,
                    (viewer, key, vars) -> notices.add(key + " " + vars),
                    (viewer, task) -> task.run(),
                    viewer -> ManagementRosterTexts.english());
        }

        private ManagementRosterController.Change change(String name, Set<UUID> roster,
                boolean add) {
            return (actor, landId, target) -> {
                CompletableFuture<Void> stage = new CompletableFuture<>();
                CompletionStage<Void> applied = stage.thenRun(() -> {
                    if (add) {
                        roster.add(target);
                    } else {
                        roster.remove(target);
                    }
                });
                if (completeInline) {
                    stage.complete(null);
                } else {
                    pending.add(stage);
                }
                return applied;
            };
        }

        void openRoot() {
            navigator.open(VIEWER, ManagementRosterPages.rootPage(ManagementGuiActions.noop(),
                    ManagementGuiTextProvider.fallbackTexts(), controller.actionsFor(LAND),
                    controller.textsFor(VIEWER))).orElseThrow();
        }

        void click(int slot) {
            navigator.handleClick(VIEWER, navigator.currentGeneration(VIEWER).orElseThrow(), slot);
        }

        GuiPage top() {
            return navigator.currentPage(VIEWER).orElseThrow();
        }

        List<String> names() {
            List<String> out = new ArrayList<>();
            for (GuiPage.RenderItem item : top().renderItems()) {
                if (item.slot() < ManagementRosterPages.ENTRIES_PER_PAGE) {
                    out.add(item.name());
                }
            }
            return out;
        }
    }

    @Test
    void rosterShowsNamesFromTheSnapshotAndAPlaceholderForUnknownPlayers() {
        Fixture fx = new Fixture();
        fx.members.add(ALICE);
        fx.members.add(BOB);
        fx.openRoot();

        fx.click(ManagementRosterPages.MEMBERS_SLOT);

        assertEquals(ManagementRosterPages.MEMBERS_PAGE_ID, fx.top().id());
        assertEquals("Land Members (2)", fx.top().title());
        assertEquals(List.of("Alice",
                "Unknown player (" + BOB.toString().substring(0, 8) + ")"), fx.names());
        assertEquals(fx.top(), fx.rendered.get(fx.rendered.size() - 1),
                "the opened page must be painted");
    }

    @Test
    void pickerOffersOnlinePlayersExceptViewerOwnerAndAlreadyListed() {
        Fixture fx = new Fixture();
        fx.members.add(ALICE);
        fx.openRoot();
        fx.click(ManagementRosterPages.MEMBERS_SLOT);

        fx.click(ManagementRosterPages.ADD_SLOT);

        assertEquals(ManagementRosterPages.MEMBER_PICKER_PAGE_ID, fx.top().id());
        assertEquals(List.of("Bob"), fx.names());
    }

    @Test
    void pickingAPlayerTrustsThemAndReturnsToTheRefreshedRoster() {
        Fixture fx = new Fixture();
        fx.openRoot();
        fx.click(ManagementRosterPages.MEMBERS_SLOT);
        fx.click(ManagementRosterPages.ADD_SLOT);

        fx.click(0);

        assertTrue(fx.members.contains(ALICE), "the first candidate (Alice) is trusted");
        assertEquals(ManagementRosterPages.MEMBERS_PAGE_ID, fx.top().id(),
                "the viewer lands back on the roster");
        assertEquals(List.of("Alice"), fx.names(), "the roster shows the new member");
        assertEquals(List.of("command.land.trust.success {player=Alice}"), fx.notices);
        assertEquals(2, fx.navigator.depth(VIEWER), "root plus roster, the picker is gone");
    }

    @Test
    void clickingAListedPlayerRemovesThemAndRedrawsInPlace() {
        Fixture fx = new Fixture();
        fx.bans.add(ALICE);
        fx.openRoot();
        fx.click(ManagementRosterPages.BANS_SLOT);

        fx.click(0);

        assertFalse(fx.bans.contains(ALICE));
        assertEquals(ManagementRosterPages.BANS_PAGE_ID, fx.top().id());
        assertEquals(List.of("Nobody is banned from this land"), fx.names());
        assertEquals(List.of("command.land.unban.success {player=Alice}"), fx.notices);
        assertEquals(2, fx.navigator.depth(VIEWER), "a redraw must not stack pages");
    }

    @Test
    void everyOpenAndChangeIsGatedBySlot() {
        Fixture fx = new Fixture();
        fx.bans.add(ALICE);
        fx.openRoot();
        fx.click(ManagementRosterPages.BANS_SLOT);
        fx.click(0);
        assertEquals(List.of("ban", "unban", "ban"), fx.gateChecks,
                "open, change, and the redraw each consult the gate");

        fx.gateOpen = false;
        fx.bans.add(BOB);
        fx.click(ManagementRosterPages.ADD_SLOT);
        assertEquals(ManagementRosterPages.BANS_PAGE_ID, fx.top().id(),
                "a refused click leaves the screen where it was");
        assertEquals(ManagementRosterController.DENIED_KEY + " {}",
                fx.notices.get(fx.notices.size() - 1));
        assertTrue(fx.bans.contains(BOB), "nothing was changed");
    }

    @Test
    void unloadedSnapshotOpensNothingInsteadOfAnEmptyList() {
        Fixture fx = new Fixture();
        fx.loaded = false;
        fx.openRoot();

        fx.click(ManagementRosterPages.MEMBERS_SLOT);

        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, fx.top().id());
    }

    @Test
    void secondClickWhileAChangeIsInFlightIsIgnored() {
        Fixture fx = new Fixture();
        fx.members.add(ALICE);
        fx.members.add(BOB);
        fx.completeInline = false;
        fx.openRoot();
        fx.click(ManagementRosterPages.MEMBERS_SLOT);

        fx.click(0);
        fx.click(1);
        assertEquals(1, fx.pending.size(), "one change at a time per viewer");

        fx.pending.get(0).complete(null);
        assertEquals(1, fx.members.size());
        assertEquals(1, fx.names().size(), "the roster redraws after the durable completion");
    }

    @Test
    void failedChangeTellsTheViewerAndKeepsTheRoster() {
        Fixture fx = new Fixture();
        fx.members.add(ALICE);
        fx.completeInline = false;
        fx.openRoot();
        fx.click(ManagementRosterPages.MEMBERS_SLOT);
        fx.click(0);

        fx.pending.get(0).completeExceptionally(new IllegalStateException("store closed"));

        assertTrue(fx.members.contains(ALICE));
        assertEquals(List.of("command.land.untrust.failed {reason=untrust.failed}"), fx.notices);
        assertEquals(List.of("Alice"), fx.names());
    }

    @Test
    void viewerWhoNavigatedAwayKeepsTheScreenTheyChose() {
        Fixture fx = new Fixture();
        fx.members.add(ALICE);
        fx.completeInline = false;
        fx.openRoot();
        fx.click(ManagementRosterPages.MEMBERS_SLOT);
        fx.click(0);
        fx.click(ManagementRosterPages.BACK_SLOT);
        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, fx.top().id());

        fx.pending.get(0).complete(null);

        assertEquals(ManagementGuiPages.ROOT_PAGE_ID, fx.top().id(),
                "a late completion must not pull the viewer back");
        assertEquals(List.of("command.land.untrust.success {player=Alice}"), fx.notices);
    }
}
