package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibApi;
import com.smile.chunkland.command.VisualizationDebugCommand;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ChunkLandVisualizationWiringTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void visualizationSchedulerBuildIsFailClosedWithoutServer() {
        assertTrue(ChunkLandPlugin.tryBuildVisualizationScheduler(null, null).isEmpty());
        assertTrue(ChunkLandPlugin.tryBuildVisualizationScheduler(null, AceLibApi.uninitialized()).isEmpty());
    }

    private static final class Sender {
        final List<String> messages = new ArrayList<>();
        final boolean isPlayer;
        final boolean permitted;

        Sender(boolean isPlayer, boolean permitted) {
            this.isPlayer = isPlayer;
            this.permitted = permitted;
        }

        CommandSender sender() {
            Class<?>[] faces = isPlayer
                    ? new Class<?>[] {Player.class, CommandSender.class}
                    : new Class<?>[] {CommandSender.class};
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hasPermission" -> {
                        return permitted;
                    }
                    case "sendMessage" -> {
                        if (args != null && args.length == 1 && args[0] instanceof String text) {
                            messages.add(text);
                        }
                        return null;
                    }
                    case "getUniqueId" -> {
                        return PLAYER_ID;
                    }
                    case "getName" -> {
                        return isPlayer ? "viz-player" : "console";
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "toString" -> {
                        return "test-sender";
                    }
                    default -> throw new UnsupportedOperationException("unexpected call: " + method);
                }
            };
            return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), faces, handler);
        }
    }

    private static SelectionSessionManager manager() {
        return new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                () -> Instant.parse("2026-01-01T00:00:00Z"),
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                SelectionStructureRevisionLookup.unavailable());
    }

    @Test
    void vizRejectsNonVizEntryPoint() {
        Sender sender = new Sender(true, true);

        assertFalse(VisualizationDebugCommand.handle(
                sender.sender(), new String[] {"m0test"}, manager(), Optional::empty,
                SelectionVisualizationTaskController.noop()));
        assertTrue(sender.messages.isEmpty());
    }

    @Test
    void vizRequiresAPlayer() {
        Sender console = new Sender(false, true);

        assertTrue(VisualizationDebugCommand.handle(
                console.sender(), new String[] {"viz", "start"}, manager(), Optional::empty,
                SelectionVisualizationTaskController.noop()));
        assertEquals(1, console.messages.size());
        assertTrue(console.messages.get(0).contains("須由玩家執行"));
    }

    @Test
    void vizRequiresPermission() {
        Sender sender = new Sender(true, false);

        assertTrue(VisualizationDebugCommand.handle(
                sender.sender(), new String[] {"viz", "start"}, manager(), Optional::empty,
                SelectionVisualizationTaskController.noop()));
        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains(VisualizationDebugCommand.PERMISSION));
    }

    @Test
    void vizStartWithoutSchedulerRepliesNotReadyAndCreatesNothing() {
        Sender sender = new Sender(true, true);
        SelectionSessionManager selections = manager();

        assertTrue(VisualizationDebugCommand.handle(
                sender.sender(), new String[] {"viz", "start"}, selections, Optional::empty,
                SelectionVisualizationTaskController.noop()));

        assertTrue(selections.sessionFor(PLAYER_ID).isEmpty());
        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("尚未就緒"));
    }

    @Test
    void vizStopWithoutSessionRepliesIdle() {
        Sender sender = new Sender(true, true);

        assertTrue(VisualizationDebugCommand.handle(
                sender.sender(), new String[] {"viz", "stop"}, manager(), Optional::empty,
                SelectionVisualizationTaskController.noop()));

        assertTrue(sender.messages.get(0).contains("沒有作用中"));
    }

    @Test
    void vizStatusReportsSessionAndRenderState() {
        Sender sender = new Sender(true, true);

        assertTrue(VisualizationDebugCommand.handle(
                sender.sender(), new String[] {"viz"}, manager(), Optional::empty,
                SelectionVisualizationTaskController.noop()));

        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("session=false"));
        assertTrue(sender.messages.get(0).contains("rendering=false"));
    }

    @Test
    void vizRejectsUnknownVerbsWithUsage() {
        Sender sender = new Sender(true, true);

        assertTrue(VisualizationDebugCommand.handle(
                sender.sender(), new String[] {"viz", "dance"}, manager(), Optional::empty,
                SelectionVisualizationTaskController.noop()));

        assertTrue(sender.messages.get(0).contains("用法"));
    }

    @Test
    void vizWithoutRegistryRepliesNotReady() {
        Sender sender = new Sender(true, true);

        assertTrue(VisualizationDebugCommand.handle(
                sender.sender(), new String[] {"viz", "status"}, null, Optional::empty,
                SelectionVisualizationTaskController.noop()));

        assertTrue(sender.messages.get(0).contains("尚未就緒"));
    }
}
