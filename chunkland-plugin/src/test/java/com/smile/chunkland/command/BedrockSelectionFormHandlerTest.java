package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionEditService;
import com.smile.chunkland.selection.SelectionLandIndex;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Bedrock menu for chunk edits: one button per operation, each re-entering
 * the same chat handler verbs, so form edits and chat edits cannot drift
 * apart.
 */
class BedrockSelectionFormHandlerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    private static final class Harness {
        final SelectionLandIndex index = (world, packed) -> null;
        final SelectionSessionManager selections = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                new SelectionVisualizationTaskController() {
                    @Override
                    public void stop(UUID playerId) {
                    }

                    @Override
                    public void start(SelectionSession session) {
                    }

                    @Override
                    public void refresh(SelectionSession session) {
                    }
                },
                notification -> {
                },
                (SelectionClock) () -> NOW,
                () -> Duration.ofMinutes(10),
                uuid -> Optional.empty(),
                landId -> OptionalLong.empty());
        final SelectionEditService edits = new SelectionEditService(
                new LimitSettings(5, 256, 128, 16, 32, 1024), index);
        final Map<UUID, ChunkKey> standing = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>();
        final ReplySink sink = new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> variables) {
                keys.add(key);
            }

            @Override
            public void reply(String key, Map<String, Object> variables, Locale locale) {
                reply(key, variables);
            }
        };
        volatile boolean bedrock = true;
        volatile FormSendResult next = FormSendResult.SENT;
        volatile FormSpec lastSpec;
        volatile Consumer<FormResponse> lastCallback;

        SelectionCommandHandler chat() {
            return new SelectionCommandHandler(selections, () -> edits,
                    player -> Optional.ofNullable(standing.get(player.getUniqueId())));
        }

        BedrockSelectionFormHandler forms() {
            return new BedrockSelectionFormHandler(
                    playerId -> bedrock,
                    (playerId, spec, callback) -> {
                        lastSpec = spec;
                        lastCallback = callback;
                        return next;
                    },
                    chat(), selections, SelectionFormTexts::forLocale, immediateScheduler());
        }

        void fire(FormResponse response) {
            lastCallback.accept(response);
        }

        SelectionSession claimSession(Set<ChunkKey> chunks) {
            SelectionSession initial = SelectionSession.initial(
                    PLAYER_ID, WORLD_ID, SelectionMode.CREATE_LAND, Optional.empty(), Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)),
                    Optional.of(new SelectionPoint(WORLD_ID, 15, 64, 15)), 0L, NOW);
            SelectionSession stamped = selections.start(initial);
            return selections.updateSelection(PLAYER_ID, stamped, new SelectionUpdate(
                            stamped.pointA(), stamped.pointB(), chunks, Map.of()))
                    .orElseThrow(() -> new IllegalStateException("setup update must succeed"));
        }
    }

    private static com.smile.acelib.scheduler.SafeScheduler immediateScheduler() {
        return new ImmediateScheduler();
    }

    static final class ImmediateScheduler implements com.smile.acelib.scheduler.SafeScheduler {
        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayer(Player player, Runnable runnable) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(runnable, "runnable");
            runnable.run();
            return new ImmediateTask();
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runGlobal(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAsync(Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runLater(Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runTimer(Runnable runnable, long delay, long period) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayerLater(
                Player player, Runnable runnable, long delay) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForEntity(
                org.bukkit.entity.Entity entity, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAtLocation(
                org.bukkit.Location location, Runnable runnable) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public java.util.List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) {
            return java.util.List.of();
        }

        @Override
        public com.smile.acelib.scheduler.TaskScope scopeFor(Player player) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public com.smile.acelib.scheduler.TaskScope scopeFor(org.bukkit.entity.Entity entity) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public void cancelAll() {
        }
    }

    static final class ImmediateTask implements com.smile.acelib.scheduler.ScheduledTask {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public org.bukkit.plugin.java.JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public com.smile.acelib.scheduler.TaskType getType() {
            return com.smile.acelib.scheduler.TaskType.PLAYER;
        }

        @Override
        public long getCreationTick() {
            return 0L;
        }
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId" -> { return PLAYER_ID; }
                        case "getName" -> { return "TestPlayer"; }
                        case "locale" -> { return Locale.US; }
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                        }
                    }
                });
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD_ID, x, z);
    }

    @Test
    void nonBedrockKeepsTheChatPath() {
        Harness harness = new Harness();
        harness.bedrock = false;
        harness.claimSession(Set.of(chunk(0, 0)));

        boolean handled = harness.forms().handle(player(),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow(), harness.sink);

        assertFalse(handled);
        assertTrue(harness.keys.isEmpty());
    }

    @Test
    void bedrockWithoutSessionFailsClosedWithoutForm() {
        Harness harness = new Harness();

        boolean handled = harness.forms().handle(player(),
                harness.selections.sessionFor(PLAYER_ID).orElse(null), harness.sink);

        assertTrue(handled);
        assertEquals(List.of("command.land.selection.no_selection"), harness.keys);
    }

    @Test
    void menuShowsModeAndCountWithOneButtonPerOperation() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));

        boolean handled = harness.forms().handle(player(),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow(), harness.sink);

        assertTrue(handled);
        assertTrue(harness.lastSpec instanceof FormSpec.Simple);
        assertEquals(4, ((FormSpec.Simple) harness.lastSpec).buttons().size());
    }

    @Test
    void toggleButtonArmsChunkEditsThroughTheChatVerb() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.forms().handle(player(),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow(), harness.sink);

        harness.fire(new FormResponse(FormResponseStatus.VALID, 0, List.of()));

        assertTrue(harness.selections.isChunkEdit(PLAYER_ID));
        assertEquals(List.of("command.land.selection.mode_chunk"), harness.keys);
    }

    @Test
    void addAndRemoveButtonsEditTheStandingChunk() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.standing.put(PLAYER_ID, chunk(1, 0));
        harness.forms().handle(player(),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow(), harness.sink);

        harness.fire(new FormResponse(FormResponseStatus.VALID, 1, List.of()));
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
        assertEquals(List.of("command.land.selection.added"), harness.keys);

        harness.fire(new FormResponse(FormResponseStatus.VALID, 2, List.of()));
        assertEquals(Set.of(chunk(0, 0)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
        assertEquals("command.land.selection.removed", harness.keys.get(harness.keys.size() - 1));
    }

    @Test
    void closeButtonCancelsWithoutTouchingSelection() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.forms().handle(player(),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow(), harness.sink);

        harness.fire(new FormResponse(FormResponseStatus.CLOSED, null, List.of()));

        assertEquals(Set.of(chunk(0, 0)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
        assertEquals(List.of("command.land.selection.cancelled"), harness.keys);
    }

    @Test
    void rejectedSendFailsClosed() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.next = FormSendResult.REJECTED;

        boolean handled = harness.forms().handle(player(),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow(), harness.sink);

        assertTrue(handled);
        assertEquals(List.of("command.land.selection.failed"), harness.keys);
    }
}
