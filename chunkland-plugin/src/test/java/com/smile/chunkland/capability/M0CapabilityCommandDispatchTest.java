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
    void dispatchFormDeliversResponseToSameSenderAndUsesCallbackOverload() {
        // Regression for CL-M0-07: /chunkland m0test form must (a) wire the Consumer<FormResponse>
        // overload of sendForm (not the no-callback overload), and (b) when AceLib delivers a
        // FormResponse, send a concise stable message back to the same CommandSender. The
        // StubFormService invokes the supplied callback synchronously with a known VALID
        // FormResponse so we can pin both behaviours in one assertion.
        StubFormService forms = new StubFormService(FormSendResult.SENT, "stub-form");
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(forms))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        CapturingPlayerSender sender = new CapturingPlayerSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "form"},
            java.util.Optional.of(probe(caps)));
        assertTrue(handled);
        // Two messages: the initial send-result, and the follow-up from the delivered FormResponse.
        assertEquals(2, sender.messages.size(),
            "form dispatch must emit the initial send-result AND a follow-up response message; got="
                + sender.messages);
        assertEquals(1L, forms.sendCount(),
            "sendForm must be invoked exactly once");
        assertEquals(1L, forms.callbackSendCount(),
            "form dispatch must use the Consumer<FormResponse> overload; got callbackSendCount="
                + forms.callbackSendCount());
        // The initial structured send-result message is preserved.
        assertTrue(sender.messages.stream().anyMatch(s -> s.startsWith("[form]")
                && s.contains("sendResult=")),
            "initial [form] send-result message must be preserved; got=" + sender.messages);
        // The delivered FormResponse produces a concise, stable, observable sender message.
        assertTrue(sender.messages.stream().anyMatch(s -> s.startsWith("[form response]")
                && s.contains("VALID")),
            "delivered FormResponse must surface as a [form response] VALID message; got="
                + sender.messages);
    }

    @Test
    void dispatchFormNullResponseIsNonFatal() {
        // A null FormResponse must not crash the command and the initial [form] send-result must
        // still reach the sender. Custom FormService invokes the consumer with a null response so
        // only the formatter / sink can defend against it (StubFormService has no role here).
        com.smile.acelib.form.FormService nullResponseForms = new com.smile.acelib.form.FormService() {
            @Override
            public com.smile.acelib.form.FormSendResult sendForm(UUID player,
                com.smile.acelib.form.FormSpec spec) {
                return com.smile.acelib.form.FormSendResult.SENT;
            }
            @Override
            public com.smile.acelib.form.FormSendResult sendForm(UUID player,
                com.smile.acelib.form.FormSpec spec,
                java.util.function.Consumer<com.smile.acelib.form.FormResponse> consumer) {
                if (consumer != null) {
                    consumer.accept(null);
                }
                return com.smile.acelib.form.FormSendResult.SENT;
            }
            @Override public String getModuleStatus() { return "null-response-form"; }
            @Override public void shutdown() { }
        };
        Capabilities caps = Capabilities.builder()
            .scheduler(new StubSafeScheduler())
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(nullResponseForms))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        CapturingPlayerSender sender = new CapturingPlayerSender();
        boolean handled = false;
        try {
            handled = M0CapabilityProbe.handleCommand(sender.sender,
                new String[]{"m0test", "form"},
                java.util.Optional.of(probe(caps)));
        } catch (RuntimeException e) {
            fail("handleCommand must not crash on a null FormResponse: " + e);
        }
        assertTrue(handled);
        assertTrue(sender.messages.stream().anyMatch(s -> s.startsWith("[form]")
                && s.contains("sendResult=")),
            "initial [form] send-result must still be delivered on a null response; got="
                + sender.messages);
        assertTrue(sender.messages.stream().anyMatch(s -> s.startsWith("[form response]")),
            "null response must still surface as a [form response] notice; got=" + sender.messages);
    }

    @Test
    void dispatchFormCallbackSendMessageFailureIsNonFatal() {
        // A buggy downstream formatter (here simulated by a throwing sendMessage on the response
        // line) must not crash the command. Custom FormService that does NOT swallow callback
        // exceptions is used so the only thing protecting the command is the production sink's
        // try/catch.
        com.smile.acelib.form.FormService unswallowingForms = new com.smile.acelib.form.FormService() {
            @Override
            public com.smile.acelib.form.FormSendResult sendForm(UUID player,
                com.smile.acelib.form.FormSpec spec) {
                return com.smile.acelib.form.FormSendResult.SENT;
            }
            @Override
            public com.smile.acelib.form.FormSendResult sendForm(UUID player,
                com.smile.acelib.form.FormSpec spec,
                java.util.function.Consumer<com.smile.acelib.form.FormResponse> consumer) {
                if (consumer != null) {
                    // No try/catch around the consumer — exception propagates if the sink doesn't
                    // absorb it.
                    consumer.accept(new com.smile.acelib.form.FormResponse(
                        com.smile.acelib.form.FormResponseStatus.VALID, 0, java.util.List.of()));
                }
                return com.smile.acelib.form.FormSendResult.SENT;
            }
            @Override public String getModuleStatus() { return "unswallowing-form"; }
            @Override public void shutdown() { }
        };
        Capabilities caps = Capabilities.builder()
            .scheduler(new StubSafeScheduler())
            .guiService(new StubGuiService(GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(unswallowingForms))
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        ThrowingPlayerSender sender = new ThrowingPlayerSender();
        boolean handled = false;
        try {
            handled = M0CapabilityProbe.handleCommand(sender.sender,
                new String[]{"m0test", "form"},
                java.util.Optional.of(probe(caps)));
        } catch (RuntimeException e) {
            fail("handleCommand must not propagate a callback / sendMessage failure: " + e);
        }
        assertTrue(handled);
        assertTrue(sender.initialMessageDelivered,
            "initial [form] send-result must still be delivered even when the response line fails");
    }

    @Test
    void dispatchFormRequiresPlayer() {
        CapturingSender sender = new CapturingSender();
        boolean handled = M0CapabilityProbe.handleCommand(sender.sender,
            new String[]{"m0test", "form"},
            java.util.Optional.of(probe(buildCapabilities())));
        assertTrue(handled);
        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("玩家"),
            "non-player sender running form mode must see the require-player message");
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

    /** JDK-proxy Player double whose {@code sendMessage(...)} throws on the first call (the
     *  FormResponse response line) and records subsequent calls (the initial [form] send-result)
     *  normally. This isolates the production sink's try/catch: only it can stop the response
     *  failure from crashing the command. */
    private static final class ThrowingPlayerSender {
        final Player sender;
        final UUID uuid = UUID.randomUUID();
        final Location location = new Location(null, 0.0, 64.0, 0.0);
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        int sendMessageCalls = 0;
        boolean initialMessageDelivered = false;

        ThrowingPlayerSender() {
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if (name.equals("sendMessage")) {
                        sendMessageCalls++;
                        if (sendMessageCalls == 1) {
                            // Simulate a buggy downstream formatter / sender write failure on
                            // the response line. The production sink must absorb this.
                            throw new RuntimeException("simulated response sendMessage failure");
                        }
                        // Subsequent calls (the initial [form] send-result line) succeed.
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
                        initialMessageDelivered = true;
                        return null;
                    }
                    if (name.equals("getUniqueId")) return uuid;
                    if (name.equals("getLocation")) return location;
                    if (name.equals("isOnline")) return Boolean.TRUE;
                    if (name.equals("getName")) return "ThrowingPlayerSender";
                    if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "ThrowingPlayerSender-proxy";
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
