package com.smile.chunkland.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class M0CapabilityCommandDispatchTest {

    /**
     * Build a JDK-proxy {@link CommandSender} whose {@code sendMessage(...)} methods append
     * every string (or component string-form) to {@link #messages}. All other methods return
     * null/false/defaults so the dispatcher remains single-purpose.
     */
    private static final class CapturingSender {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        final CommandSender sender;

        CapturingSender() {
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if (name.equals("sendMessage")) {
                        if (args == null) {
                            messages.add("");
                        } else {
                            for (Object a : args) {
                                if (a == null) {
                                    continue;
                                }
                                if (a instanceof String[] arr) {
                                    for (String s : arr) {
                                        messages.add(String.valueOf(s));
                                    }
                                } else if (a instanceof Object[] arr) {
                                    for (Object s : arr) {
                                        if (s != null) {
                                            messages.add(String.valueOf(s));
                                        }
                                    }
                                } else {
                                    messages.add(String.valueOf(a));
                                }
                            }
                        }
                        return null;
                    }
                    if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "CapturingSender-proxy";
                        };
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == float.class) return 0f;
                    if (rt == double.class) return 0d;
                    return null;
                }
            };
            sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                handler);
        }
    }

    private static Capabilities buildCapabilities() {
        return Capabilities.builder()
            .scheduler(new StubSafeScheduler())
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(new StubFormService(FormSendResult.REJECTED, "stub")))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
    }

    private static M0CapabilityProbe probe(Capabilities caps) {
        return M0CapabilityProbe.fromCapabilities(caps).orElseThrow(
            () -> new AssertionError("complete bundle must build a probe"));
    }

    // ----- mode parsing -----

    @Test
    void modeFromStringAcceptsFourKeywords() {
        assertTrue(M0CapabilityProbe.Mode.fromString("scheduler").isPresent());
        assertTrue(M0CapabilityProbe.Mode.fromString("GUI").isPresent());
        assertTrue(M0CapabilityProbe.Mode.fromString("Form").isPresent());
        assertTrue(M0CapabilityProbe.Mode.fromString("CANCELALL").isPresent());
    }

    @Test
    void modeFromStringRejectsUnknownsAndNull() {
        assertFalse(M0CapabilityProbe.Mode.fromString("garbage").isPresent());
        assertFalse(M0CapabilityProbe.Mode.fromString("schedulerx").isPresent());
        assertFalse(M0CapabilityProbe.Mode.fromString(null).isPresent());
    }

    // ----- dispatch when probe empty -----

    @Test
    void emptyProbeAlwaysReturnsNotReadyMessage() {
        CapturingSender sender = new CapturingSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test"}, java.util.Optional.empty());
        assertTrue(handled);
        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("未就緒"),
            "must report capability smoke not ready without throwing");
    }

    @Test
    void unknownModeHoldsBackAndPrintsUsage() {
        CapturingSender sender = new CapturingSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "nope"},
            java.util.Optional.of(probe(buildCapabilities())));
        assertTrue(handled);
        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("m0test 用法"),
            "unknown mode must not run any test, only show usage");
    }

    // ----- dispatch each mode -----

    @Test
    void dispatchSchedulerReportsOkPath() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(new StubFormService(FormSendResult.REJECTED, "stub")))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        CapturingPlayerSender sender = new CapturingPlayerSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "scheduler"},
            java.util.Optional.of(probe(caps)));
        assertTrue(handled);
        assertEquals(1, sender.messages.size());
        String out = sender.messages.get(0);
        assertTrue(out.startsWith("[scheduler]"),
            "scheduler dispatch must produce the [scheduler] prefix");
        assertTrue(out.contains("OK"));
        assertEquals(2, scheduler.totalTracked());
    }

    @Test
    void dispatchGuiRequiresPlayer() {
        CapturingSender sender = new CapturingSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "gui"},
            java.util.Optional.of(probe(buildCapabilities())));
        assertTrue(handled);
        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("玩家"),
            "non-player sender running gui mode must see the require-player message");
    }

    @Test
    void dispatchCancelAllRunsAndReportsResidualCount() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(new StubFormService(FormSendResult.REJECTED, "stub")))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        CapturingSender sender = new CapturingSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "cancelall"},
            java.util.Optional.of(probe(caps)));
        assertTrue(handled);
        assertEquals(1, sender.messages.size());
        assertTrue(scheduler.cancelAllCount() >= 1L,
            "cancelall dispatch must invoke scheduler.cancelAll()");
        assertTrue(sender.messages.get(0).contains("cancelAll"));
    }

    @Test
    void dispatchCancelAllRejectsNonexistentModeDoesNotCallScheduler() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(new StubFormService(FormSendResult.REJECTED, "stub")))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        CapturingSender sender = new CapturingSender();
        M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "unknown"},
            java.util.Optional.of(probe(caps)));
        assertEquals(0L, scheduler.cancelAllCount(),
            "an unknown mode must not pre-cancel any task");
    }

    @Test
    void dispatchRequiresPluginLevelPermissionByDesign() {
        // Note: the chunkland command in plugin.yml no longer carries a top-level `permission`;
        // permission gating is enforced by ChunkLandPlugin.onCommand on a per-subcommand basis
        // (m0message -> chunkland.debug.m0message, m0test -> chunkland.debug.m0test). The
        // dispatcher itself only enforces the "sender must be a Player" constraint for modes
        // that require one (scheduler / gui / form). This test pins the contract:
        // handleCommand never NPEs on a sender without a backing Server.
        CapturingSender sender = new CapturingSender();
        try {
            M0CapabilityProbe.handleCommand(sender.sender,
                new String[]{"m0test", "scheduler"},
                java.util.Optional.of(probe(buildCapabilities())));
        } catch (NullPointerException e) {
            fail("handleCommand must tolerate a sender whose Server is null (Bukkit handles gating): " + e);
        }
    }

    @Test
    void dispatchSchedulerRequiresPlayer() {
        CapturingSender sender = new CapturingSender();
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(new StubFormService(FormSendResult.REJECTED, "stub")))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "scheduler"},
            java.util.Optional.of(probe(caps)));
        assertTrue(handled);
        assertTrue(sender.messages.stream().anyMatch(s -> s.contains("玩家")),
            "non-player sender running scheduler mode must see the require-player message");
        assertEquals(0L, scheduler.cancelAllCount());
        assertEquals(0, scheduler.totalTracked());
    }

    // ----- capability surface contract -----

    @Test
    void capabilitiesAccessorReturnsBundle() {
        Capabilities caps = buildCapabilities();
        M0CapabilityProbe p = probe(caps);
        assertEquals(caps, p.capabilities());
    }

    @SuppressWarnings("unused")
    private static void touchImports() {
        // Keeps imports the test file declares alive even if a future refactor drops the only usage.
        assertNull(CommandSender.class.getMethods());
    }

    /** JDK-proxy Player+CommandSender double for tests that need a Player sender without a
     *  live server. Records every sendMessage() call so tests can assert dispatcher output. */
    private static final class CapturingPlayerSender {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        final Player sender;
        final UUID uuid = UUID.randomUUID();
        final Location location = new Location(null, 0.0, 64.0, 0.0);

        CapturingPlayerSender() {
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
                    if (name.equals("getUniqueId")) return uuid;
                    if (name.equals("getLocation")) return location;
                    if (name.equals("isOnline")) return Boolean.TRUE;
                    if (name.equals("getName")) return "CapturingPlayerSender";
                    if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "CapturingPlayerSender-proxy";
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
            sender = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler);
        }
    }
}
