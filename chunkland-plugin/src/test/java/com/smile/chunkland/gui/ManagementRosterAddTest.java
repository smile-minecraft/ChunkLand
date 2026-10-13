package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.adapter.gui.ManagementRosterController;
import com.smile.chunkland.command.OfflinePlayerResolver;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.command.RosterChangeOperation;
import com.smile.chunkland.gui.ManagementRosterPages.Entry;
import com.smile.chunkland.gui.ManagementRosterPages.Kind;
import com.smile.chunkland.api.land.LandId;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The roster controller accepts UUID text, online names and known offline
 * names on its reference entry: all three resolve to the same player through
 * the shared async operation, while unknown names and mid-flight revocations
 * deny without writing.
 */
class ManagementRosterAddTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hasPermission" -> true;
                    case "getName" -> "Viewer";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Player-proxy";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    private static final class RecordingExecutor implements Executor {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable task) {
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    private static final class DiscardingScheduler
            implements com.smile.chunkland.command.DiscardingPlayerScheduler {
        final List<Runnable> pending = new CopyOnWriteArrayList<>();
        volatile boolean discardNext;

        @Override
        public void runForPlayer(Player player, Runnable task) {
            runForPlayer(player, task, null);
        }

        @Override
        public void runForPlayer(Player player, Runnable task, Runnable onDiscarded) {
            if (discardNext) {
                discardNext = false;
                if (onDiscarded != null) {
                    onDiscarded.run();
                }
                return;
            }
            pending.add(task);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    private static final class Fixture {
        final Set<UUID> members = new HashSet<>();
        final Set<UUID> bans = new HashSet<>();
        final List<String> notices = new ArrayList<>();
        final RecordingExecutor executor = new RecordingExecutor();
        final AtomicBoolean gateOpen = new AtomicBoolean(true);
        final AtomicBoolean viewerOnline = new AtomicBoolean(true);
        final AtomicBoolean landKnown = new AtomicBoolean(true);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final ManagementRosterController controller;

        Fixture() {
            this(PlayerScheduler.direct());
        }

        Fixture(PlayerScheduler scheduler) {
            ManagementRosterController.Navigation navigation =
                    new ManagementRosterController.Navigation() {
                        @Override
                        public GuiNavigator navigator() {
                            return null;
                        }

                        @Override
                        public void render(UUID viewer, long generation, GuiPage page) {
                        }

                        @Override
                        public void back(GuiClickContext click) {
                        }
                    };
            ManagementRosterController.Rosters rosters =
                    new ManagementRosterController.Rosters() {
                        @Override
                        public Optional<Set<UUID>> members(LandId landId) {
                            return Optional.of(Set.copyOf(members));
                        }

                        @Override
                        public Optional<Set<UUID>> bans(LandId landId) {
                            return Optional.of(Set.copyOf(bans));
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
                    return List.of(new Entry(ALICE, "Alice"));
                }
            };
            OfflinePlayerResolver resolver = new OfflinePlayerResolver(
                    name -> "Alice".equals(name) ? Optional.of(ALICE) : Optional.empty(),
                    name -> "OfflineAlice".equals(name) ? Optional.of(ALICE) : Optional.empty(),
                    executor);
            RosterChangeOperation operation = new RosterChangeOperation(
                    name -> "Alice".equals(name) ? Optional.of(ALICE) : Optional.empty(),
                    resolver, scheduler,
                    (viewer, landId, slot) -> gateOpen.get(),
                    viewer -> viewerOnline.get(),
                    landId -> landKnown.get(),
                    alive::get,
                    null,
                    slot -> switch (slot) {
                        case TRUST -> (actor, landId, target) -> {
                            members.add(target);
                            return CompletableFuture.<Void>completedFuture(null);
                        };
                        case UNTRUST -> (actor, landId, target) -> {
                            members.remove(target);
                            return CompletableFuture.<Void>completedFuture(null);
                        };
                        case BAN -> (actor, landId, target) -> {
                            bans.add(target);
                            return CompletableFuture.<Void>completedFuture(null);
                        };
                        case UNBAN -> (actor, landId, target) -> {
                            bans.remove(target);
                            return CompletableFuture.<Void>completedFuture(null);
                        };
                    });
            controller = new ManagementRosterController(navigation, rosters,
                    (viewer, landId, slot) -> gateOpen.get(),
                    new ManagementRosterController.Changes(
                            (actor, landId, target) -> {
                                members.add(target);
                                return CompletableFuture.completedFuture(null);
                            },
                            (actor, landId, target) -> {
                                members.remove(target);
                                return CompletableFuture.completedFuture(null);
                            },
                            (actor, landId, target) -> {
                                bans.add(target);
                                return CompletableFuture.completedFuture(null);
                            },
                            (actor, landId, target) -> {
                                bans.remove(target);
                                return CompletableFuture.completedFuture(null);
                            }),
                    people,
                    (viewer, key, vars) -> notices.add(key + " " + vars),
                    (viewer, task) -> task.run(),
                    viewer -> ManagementRosterTexts.english(),
                    operation,
                    viewer -> viewerOnline.get() ? player(viewer) : null);
        }

        void add(Kind kind, boolean add, String raw) {
            controller.addByReference(VIEWER, LAND, kind, add, raw);
        }
    }

    @Test
    void uuidTextOnlineNameAndOfflineNameTrustTheSamePlayer() {
        Fixture uuid = new Fixture();
        uuid.add(Kind.MEMBERS, true, ALICE.toString());
        assertTrue(uuid.members.contains(ALICE));
        assertEquals(List.of("command.land.trust.success {player=" + ALICE + "}"), uuid.notices);

        Fixture online = new Fixture();
        online.add(Kind.MEMBERS, true, "Alice");
        assertTrue(online.members.contains(ALICE));

        Fixture offline = new Fixture();
        offline.add(Kind.MEMBERS, true, "OfflineAlice");
        assertFalse(offline.members.contains(ALICE), "no write before the async lookup runs");
        assertTrue(offline.notices.isEmpty(), "no notice before the async lookup runs");
        assertEquals(1, offline.executor.pending.size(), "exactly one async lookup must be queued");
        offline.executor.runAll();
        assertTrue(offline.members.contains(ALICE), "the offline name resolves to the same player");
        assertEquals(1, offline.notices.size());
        assertTrue(offline.notices.get(0).startsWith("command.land.trust.success"));
    }

    @Test
    void banByOfflineNameWritesTheSamePlayer() {
        Fixture fx = new Fixture();
        fx.add(Kind.BANS, true, "OfflineAlice");
        fx.executor.runAll();
        assertTrue(fx.bans.contains(ALICE));
        assertTrue(fx.notices.get(0).startsWith("command.land.ban.success"));
    }

    @Test
    void discardedHopReleasesTheFlightSoReentryWorks() {
        DiscardingScheduler scheduler = new DiscardingScheduler();
        Fixture fx = new Fixture(scheduler);
        scheduler.discardNext = true;
        fx.add(Kind.MEMBERS, true, "OfflineAlice");
        fx.executor.runAll();
        assertTrue(fx.members.isEmpty(), "a discarded task must never write");
        assertTrue(fx.notices.isEmpty(), "a discarded task must not report");

        fx.add(Kind.MEMBERS, true, "OfflineAlice");
        fx.executor.runAll();
        scheduler.runAll();
        scheduler.runAll();
        assertTrue(fx.members.contains(ALICE),
                "the released flight must let the re-entered change through");
        assertEquals(1, fx.notices.size());
        assertTrue(fx.notices.get(0).startsWith("command.land.trust.success"));
    }

    @Test
    void unknownReferenceIsDeniedWithoutWriting() {
        Fixture fx = new Fixture();
        fx.add(Kind.MEMBERS, true, "NobodyEverPlayed");
        fx.executor.runAll();
        assertTrue(fx.members.isEmpty(), "unknown names must never write");
        assertEquals(1, fx.notices.size());
        assertTrue(fx.notices.get(0).startsWith("command.land.trust.failed"));
        assertTrue(fx.notices.get(0).contains("trust.unknown_target"));
    }

    @Test
    void reservedReferenceIsDeniedWithoutWriting() {
        Fixture fx = new Fixture();
        fx.add(Kind.BANS, true, "EVERYONE");
        fx.executor.runAll();
        assertTrue(fx.bans.isEmpty());
        assertTrue(fx.notices.get(0).startsWith("command.land.ban.failed"));
        assertTrue(fx.executor.pending.isEmpty(), "reserved names must never queue a lookup");
    }

    @Test
    void revokedGateDeniesWithoutWriting() {
        Fixture fx = new Fixture();
        fx.add(Kind.MEMBERS, true, "OfflineAlice");
        fx.gateOpen.set(false);
        fx.executor.runAll();
        assertTrue(fx.members.isEmpty(), "a viewer revoked mid-resolution must not write");
        assertEquals(List.of(ManagementRosterController.DENIED_KEY + " {}"), fx.notices);
    }

    @Test
    void deletedLandDeniesWithoutWriting() {
        Fixture fx = new Fixture();
        fx.add(Kind.MEMBERS, true, "OfflineAlice");
        fx.landKnown.set(false);
        fx.executor.runAll();
        assertTrue(fx.members.isEmpty(), "a land deleted mid-resolution must not be written to");
        assertEquals(1, fx.notices.size());
        assertTrue(fx.notices.get(0).startsWith("command.land.trust.failed"));
    }

    @Test
    void departedViewerDeniesWithoutWriting() {
        Fixture fx = new Fixture();
        fx.add(Kind.MEMBERS, true, "OfflineAlice");
        fx.viewerOnline.set(false);
        fx.executor.runAll();
        assertTrue(fx.members.isEmpty(), "a viewer who left mid-resolution must not write");
        assertEquals(1, fx.notices.size());
    }
}
