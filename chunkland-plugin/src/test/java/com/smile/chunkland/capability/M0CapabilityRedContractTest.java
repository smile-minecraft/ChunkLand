package com.smile.chunkland.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.ChunkLandPlugin;

import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Contracts for the capability probe:
 *
 * <ul>
 *   <li>Scheduler smoke calls {@code runForPlayer} + {@code runAtLocation(player.getLocation(), ...)};
 *       no longer relies on {@code runGlobal}/{@code runAsync} as evidence.</li>
 *   <li>Cancel-all residual is derived from the actual {@link ScheduledTask} handles
 *       captured during this probe, never from a static append-only list or a hardcoded count.</li>
 *   <li>Permission gate: {@code m0message} requires {@code chunkland.debug.m0message};
 *       {@code m0test} requires {@code chunkland.debug.m0test}; either can be denied without
 *       affecting the other; the {@code chunkland} command itself is not bound to a single
 *       permission; non-player senders running {@code /chunkland m0test scheduler} are rejected
 *       safely.</li>
 * </ul>
 */
class M0CapabilityRedContractTest {

    private static final UUID PLAYER_UUID = UUID.randomUUID();

    // ----- shared helpers -----

    /** Build a JDK-proxy {@link Player} returning {@code uuid} from {@code getUniqueId()} and a
     *  zero {@link Location} from {@code getLocation()} so the StubSafeScheduler can record the
     *  type without a live Bukkit server. */
    private static Player proxyPlayer(UUID uuid, Location location) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("getLocation")) {
                        return location;
                    }
                    if (name.equals("isOnline")) {
                        return true;
                    }
                    if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "StubPlayer-" + uuid;
                        };
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return Boolean.FALSE;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0d;
                    if (rt == float.class) return 0f;
                    return null;
                }
            });
    }

    /** Build a JDK-proxy {@link World} returning {@code null}/{@code false} from every method so
     *  the Location we hand to the scheduler stays a plain record. */
    private static World proxyWorld() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, args) -> {
                if (method.getName().equals("equals") || method.getName().equals("hashCode")
                    || method.getName().equals("toString")) {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return "StubWorld";
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                if (rt == double.class) return 0d;
                if (rt == float.class) return 0f;
                return null;
            });
    }

    private static Capabilities capabilities(SafeScheduler scheduler) {
        return Capabilities.builder()
            .scheduler(scheduler)
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(new StubFormService(FormSendResult.REJECTED, "stub")))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
    }

    // ----- scheduler must use runForPlayer + runAtLocation -----

    @Test
    void testSchedulerDispatchesPlayerAndLocationNotGlobalOrAsync() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(capabilities(scheduler)).orElseThrow();

        Location location = new Location(proxyWorld(), 1.0, 64.0, 1.0);
        Player player = proxyPlayer(PLAYER_UUID, location);

        M0CapabilityProbe.SchedulerReport r = probe.testScheduler(player);

        assertTrue(r.ready(), "scheduler wiring must succeed");
        assertEquals(2, scheduler.totalTracked(), "two tasks must be recorded");
        List<StubSafeScheduler.TrackedTask> snap = scheduler.snapshot();
        assertEquals(TaskType.PLAYER, snap.get(0).type(),
            "first dispatch must be region-scoped to the player");
        assertEquals(TaskType.LOCATION, snap.get(1).type(),
            "second dispatch must be region-scoped to the player's location");
        assertFalse(r.taskCounts().contains("global"),
            "scheduler report must not advertise global dispatch as capability evidence");
        assertFalse(r.taskCounts().contains("async"),
            "scheduler report must not advertise async dispatch as capability evidence");
        assertTrue(r.taskCounts().contains("player") || r.taskCounts().contains("PLAYER"),
            "scheduler report must advertise the player dispatch");
        assertTrue(r.taskCounts().contains("location") || r.taskCounts().contains("LOCATION"),
            "scheduler report must advertise the location dispatch");
    }

    @Test
    void testSchedulerRejectsNullPlayer() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(capabilities(scheduler)).orElseThrow();

        M0CapabilityProbe.SchedulerReport r = probe.testScheduler(null);

        assertFalse(r.ready(), "missing player must keep the smoke not-ready");
        assertEquals(0, scheduler.totalTracked(),
            "no dispatch may happen when player is missing");
    }

    @Test
    void testSchedulerRejectsPlayerWithoutLocation() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(capabilities(scheduler)).orElseThrow();

        Player noLoc = proxyPlayer(PLAYER_UUID, null);

        M0CapabilityProbe.SchedulerReport r = probe.testScheduler(noLoc);

        assertFalse(r.ready(), "a player without a location cannot drive runAtLocation safely");
        assertEquals(0, scheduler.totalTracked(),
            "no dispatch may happen when runAtLocation would fail-closed");
    }

    // ----- cancelAll residual must come from real handles -----

    @Test
    void testCancelAllResidualIsComputedFromCapturedHandles() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(capabilities(scheduler)).orElseThrow();

        // Drive the scheduler probe first so the residual list contains real PLAYER/LOCATION tasks.
        Location location = new Location(proxyWorld(), 1.0, 64.0, 1.0);
        Player player = proxyPlayer(PLAYER_UUID, location);
        probe.testScheduler(player);

        M0CapabilityProbe.CancelAllReport r = probe.testCancelAll();

        assertTrue(r.ready(), "scheduler present; cancelAll path is reachable");
        // StubSafeScheduler.cancelAll() marks every tracked task cancelled, so the residual count
        // MUST be 0 (every captured handle reports isCancelled()==true). Importantly, this is
        // computed from the actual handles, not a hardcoded "0".
        assertEquals("0", r.taskCounts(),
            "StubSafeScheduler.cancelAll() flips every tracked task; residual must be 0");
        long stillAlive = scheduler.snapshot().stream().filter(t -> !t.isCancelled()).count();
        assertEquals(0L, stillAlive, "no captured handle may remain uncancelled");
        assertTrue(scheduler.cancelAllCount() >= 1L, "scheduler.cancelAll() must have been invoked");
    }

    @Test
    void testCancelAllSurvivesSchedulerThrowing() {
        SafeSchedulerThrowing stub = new SafeSchedulerThrowing();
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(capabilities(stub)).orElseThrow();

        Location location = new Location(proxyWorld(), 1.0, 64.0, 1.0);
        Player player = proxyPlayer(PLAYER_UUID, location);
        probe.testScheduler(player); // exercises the path that captures handles

        M0CapabilityProbe.CancelAllReport r = probe.testCancelAll();
        assertFalse(r.ready(), "a throwing cancelAll must surface as NOT_READY");
        assertTrue(r.message().contains("cancelAll"),
            "error message must mention cancelAll");
        assertEquals(1, stub.cancelAttempts);
    }

    @Test
    void testSchedulerAndCancelAllUseInstanceStateNotStaticTracking() throws Exception {
        // The static append-only SMOKE_TASKS list must be gone after the fix.
        StubSafeScheduler scheduler = new StubSafeScheduler();
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(capabilities(scheduler)).orElseThrow();

        // Run twice with two distinct probes to assert no leakage across instances.
        Location location = new Location(proxyWorld(), 1.0, 64.0, 1.0);
        Player player = proxyPlayer(PLAYER_UUID, location);
        probe.testScheduler(player);
        probe.testCancelAll();

        // A second, fresh probe must not see any leftover handles from the first one.
        StubSafeScheduler scheduler2 = new StubSafeScheduler();
        M0CapabilityProbe probe2 = M0CapabilityProbe.fromCapabilities(capabilities(scheduler2)).orElseThrow();
        probe2.testScheduler(player);
        // The fresh scheduler must contain only its own pair (no cross-instance pollution).
        assertEquals(2, scheduler2.totalTracked(),
            "fresh scheduler must contain only this probe's pair; no static carryover");

        // Reflectively confirm SMOKE_TASKS is no longer the carrier.
        boolean staticListGone = true;
        try {
            Class<?> cls = Class.forName("com.smile.chunkland.capability.M0CapabilityProbe");
            cls.getDeclaredField("SMOKE_TASKS");
            staticListGone = false;
        } catch (NoSuchFieldException expected) {
            staticListGone = true;
        }
        assertTrue(staticListGone, "static SMOKE_TASKS list must be removed");
    }

    // ----- permission contract -----

    @Test
    void pluginDescriptorHasBothDebugPermissionsDefaultingToOp() throws Exception {
        java.io.InputStream in = getClass().getResourceAsStream("/plugin.yml");
        assertNotNull(in, "plugin.yml must be on the test classpath");
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> yml = yaml.load(in);
        in.close();

        Object commands = yml.get("commands");
        assertNotNull(commands, "commands section must exist");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> chunkland = (java.util.Map<String, Object>) commands;
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cmd = (java.util.Map<String, Object>) chunkland.get("chunkland");
        assertNotNull(cmd, "chunkland command entry must exist");
        assertFalse(cmd.containsKey("permission"),
            "the chunkland command must NOT be bound to a single permission; subcommands gate themselves");

        Object permissions = yml.get("permissions");
        assertNotNull(permissions, "permissions section must exist");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> permMap = (java.util.Map<String, Object>) permissions;
        for (String key : new String[]{"chunkland.debug.m0message", "chunkland.debug.m0test"}) {
            assertTrue(permMap.containsKey(key),
                key + " permission must be declared in plugin.yml");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> node = (java.util.Map<String, Object>) permMap.get(key);
            assertEquals("op", String.valueOf(node.get("default")),
                key + " must default to op");
        }
    }

    @Test
    void chunkLandPluginOnCommandRejectsM0TestWithoutPermissionAndNeverProbes() {
        // The probe must NEVER be invoked when the sender lacks the m0test permission.
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkLandPlugin.PermissionProbedProbeGate probeGate = (sender, args, probe) -> {
            invoked.set(true);
            return false;
        };
        CapturingSender sender = new CapturingSender(false /* hasM0Test */, true /* hasM0Message */);
        StubCommand cmd = new StubCommand("chunkland");
        boolean handled = ChunkLandPlugin.dispatchForTest(sender.sender, cmd, "chunkland",
            new String[]{"m0test", "scheduler"}, () -> null, probeGate);
        assertTrue(handled, "onCommand must consume the chunkland alias");
        assertFalse(invoked.get(), "denied route must NOT call the probe");
        assertTrue(sender.messages.stream().anyMatch(s -> s.contains("權限") || s.contains("permission")
                || s.toLowerCase().contains("denied") || s.contains("不允許")),
            "denied sender must see a permission notice; got=" + sender.messages);
    }

    @Test
    void chunkLandPluginOnCommandRejectsM0MessageWithoutPermissionAndNeverProbes() {
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkLandPlugin.PermissionProbedProbeGate probeGate = (sender, args, probe) -> {
            invoked.set(true);
            return false;
        };
        CapturingSender sender = new CapturingSender(true /* hasM0Test */, false /* hasM0Message */);
        StubCommand cmd = new StubCommand("chunkland");
        boolean handled = ChunkLandPlugin.dispatchForTest(sender.sender, cmd, "chunkland",
            new String[]{"m0message", "chat"}, () -> null, probeGate);
        assertTrue(handled, "onCommand must consume the chunkland alias");
        assertFalse(invoked.get(), "denied m0message route must NOT call the probe");
        assertTrue(sender.messages.stream().anyMatch(s -> s.contains("權限") || s.contains("permission")
                || s.toLowerCase().contains("denied") || s.contains("不允許")),
            "denied sender must see a permission notice; got=" + sender.messages);
    }

    @Test
    void chunkLandPluginOnCommandAllowsM0TestWhenPermittedAndDispatchesProbe() {
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkLandPlugin.PermissionProbedProbeGate probeGate = (sender, args, probe) -> {
            invoked.set(true);
            return false;
        };
        CapturingSender sender = new CapturingSender(true /* hasM0Test */, true /* hasM0Message */);
        StubCommand cmd = new StubCommand("chunkland");
        boolean handled = ChunkLandPlugin.dispatchForTest(sender.sender, cmd, "chunkland",
            new String[]{"m0test", "scheduler"}, () -> null, probeGate);
        assertTrue(handled);
        assertTrue(invoked.get(), "permitted route must call the probe");
    }

    @Test
    void chunkLandPluginOnCommandAllowsM0MessageWhenPermittedAndDispatchesProbe() {
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkLandPlugin.PermissionProbedProbeGate probeGate = (sender, args, probe) -> {
            invoked.set(true);
            return false;
        };
        CapturingSender sender = new CapturingSender(true /* hasM0Test */, true /* hasM0Message */);
        StubCommand cmd = new StubCommand("chunkland");
        boolean handled = ChunkLandPlugin.dispatchForTest(sender.sender, cmd, "chunkland",
            new String[]{"m0message", "chat"}, () -> null, probeGate);
        assertTrue(handled);
        assertTrue(invoked.get(), "permitted route must call the probe");
    }

    @Test
    void dispatchSchedulerRequiresPlayer() {
        CapturingSender sender = new CapturingSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "scheduler"},
            java.util.Optional.of(M0CapabilityProbe.fromCapabilities(
                capabilities(new StubSafeScheduler())).orElseThrow()));
        assertTrue(handled, "command must be consumed");
        assertTrue(sender.messages.stream().anyMatch(s -> s.contains("玩家")),
            "non-player sender running scheduler mode must see the require-player message; got="
                + sender.messages);
    }

    // ----- helpers -----

    private static final class CapturingSender {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        final CommandSender sender;

        CapturingSender() {
            this(false, false);
        }

        CapturingSender(boolean hasM0Test, boolean hasM0Message) {
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if (name.equals("sendMessage")) {
                        if (args != null) {
                            for (Object a : args) {
                                if (a == null) continue;
                                if (a instanceof String[] arr) {
                                    for (String s : arr) messages.add(String.valueOf(s));
                                } else if (a instanceof Object[] arr) {
                                    for (Object s : arr) {
                                        if (s != null) messages.add(String.valueOf(s));
                                    }
                                } else {
                                    messages.add(String.valueOf(a));
                                }
                            }
                        }
                        return null;
                    }
                    if (name.equals("hasPermission")) {
                        if (args != null && args.length == 1 && args[0] instanceof String perm) {
                            if ("chunkland.debug.m0test".equals(perm)) return hasM0Test;
                            if ("chunkland.debug.m0message".equals(perm)) return hasM0Message;
                            return false;
                        }
                        return false;
                    }
                    if (name.equals("isPermissionSet")) return false;
                    if (name.equals("getName")) return "CapturingSender";
                    if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "CapturingSender-proxy";
                        };
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return Boolean.FALSE;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0d;
                    if (rt == float.class) return 0f;
                    return null;
                }
            };
            sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                handler);
        }
    }

    private static final class StubCommand extends org.bukkit.command.Command {
        StubCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
            return true;
        }
    }

    /** Local throwing scheduler; not exposed beyond this test. */
    private static final class SafeSchedulerThrowing implements SafeScheduler {
        int cancelAttempts = 0;

        @Override
        public void cancelAll() {
            cancelAttempts++;
            throw new RuntimeException("scheduler boom");
        }

        @Override
        public ScheduledTask runGlobal(Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runAsync(Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runLater(Runnable r, long delay) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runTimer(Runnable r, long delay, long period) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runForPlayer(Player p, Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runForPlayerLater(Player p, Runnable r, long delay) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runForEntity(org.bukkit.entity.Entity e, Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runAtLocation(Location l, Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) { return List.of(); }
    }
}