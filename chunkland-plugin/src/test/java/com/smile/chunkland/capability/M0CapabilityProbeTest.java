package com.smile.chunkland.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiSession;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M0CapabilityProbeTest {

    private static final UUID PLAYER_UUID = UUID.randomUUID();

    private static Capabilities capabilities(com.smile.acelib.scheduler.SafeScheduler scheduler,
                                            GuiService gui,
                                            BedrockService bedrock) {
        return Capabilities.builder()
            .scheduler(scheduler)
            .guiService(gui)
            .bedrockService(bedrock)
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
    }

    private static BedrockService bedrockAlways(boolean isBedrock, FormService forms) {
        return new BedrockService() {
            @Override
            public boolean isBedrockPlayer(UUID uuid) {
                return isBedrock;
            }
            @Override
            public Optional<BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
                return Optional.empty();
            }
            @Override
            public FormService forms() {
                return forms;
            }
            @Override
            public String getModuleStatus() {
                return "test-bedrock";
            }
            @Override
            public void shutdown() {
                // no-op
            }
        };
    }

    private static GuiService guiAlways(GuiResult r) {
        return new GuiService() {
            @Override
            public org.bukkit.event.Listener getListener() {
                return new org.bukkit.event.Listener() { };
            }
            @Override
            public GuiResult openInventory(com.smile.acelib.gui.GuiArgument arg) {
                return r;
            }
            @Override
            public GuiResult closeInventory(UUID uuid, long generation) {
                return r;
            }
            @Override
            public GuiResult getActiveSession(UUID uuid) {
                return r;
            }
            @Override
            public GuiResult validateClick(UUID uuid, long generation, int slot) {
                return r;
            }
            @Override
            public String getModuleStatus() {
                return "test-gui";
            }
            @Override
            public void shutdown() {
                // no-op; release() must not drive this counter.
            }
        };
    }

    // ----- isApiUsable -----

    @SuppressWarnings("deprecation")
    @Test
    void isApiUsableFalseForNullApi() {
        assertFalse(M0CapabilityProbe.isApiUsable(null));
    }

    @SuppressWarnings("deprecation")
    @Test
    void isApiUsableFalseForUninitializedApi() {
        // uninitialized() returns an API with all-null services; isApiUsable must reject it.
        assertFalse(M0CapabilityProbe.isApiUsable(AceLibApi.uninitialized()));
    }

    // ----- fromCapabilities (fail-closed) -----

    @Test
    void fromCapabilitiesEmptyOnNullBundle() {
        assertTrue(M0CapabilityProbe.fromCapabilities(null).isEmpty());
    }

    @Test
    void fromCapabilitiesEmptyOnPartialBundle() {
        Capabilities partial = Capabilities.builder()
            .scheduler(new StubSafeScheduler())
            .platform(Platform.FOLIA)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        assertTrue(M0CapabilityProbe.fromCapabilities(partial).isEmpty(),
            "missing gui/bedrock must keep the probe empty");
    }

    @Test
    void fromCapabilitiesBuildsWhenComplete() {
        Capabilities caps = capabilities(new StubSafeScheduler(),
            guiAlways(GuiResult.failed("x", "y")),
            bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")));
        Optional<M0CapabilityProbe> probe = M0CapabilityProbe.fromCapabilities(caps);
        assertTrue(probe.isPresent());
        assertSame(caps, probe.get().capabilities());
    }

    // ----- testScheduler -----

    @Test
    void testSchedulerReportsReadyAndDispatchesPlayerAndLocation() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = capabilities(scheduler,
            guiAlways(GuiResult.failed("x", "y")),
            bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        org.bukkit.entity.Player player = probePlayer();
        M0CapabilityProbe.SchedulerReport r = probe.testScheduler(player);

        assertTrue(r.ready());
        assertEquals("FOLIA", r.platform());
        assertTrue(r.regionScheduling(), "FOLIA capability must report true");
        assertEquals("player+location", r.taskCounts());
        assertEquals(2, scheduler.totalTracked(),
            "two region-scoped runs (runForPlayer + runAtLocation) must be tracked before cancelAll()");
        assertEquals(com.smile.acelib.scheduler.TaskType.PLAYER, scheduler.snapshot().get(0).type());
        assertEquals(com.smile.acelib.scheduler.TaskType.LOCATION, scheduler.snapshot().get(1).type());
        assertEquals(0, r.errorCount());
    }

    @Test
    void testSchedulerRejectsNullPlayer() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = capabilities(scheduler,
            guiAlways(GuiResult.failed("x", "y")),
            bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.SchedulerReport r = probe.testScheduler(null);
        assertFalse(r.ready());
        assertEquals(0, scheduler.totalTracked());
    }

    @Test
    void testSchedulerSurvivesLiveTaskCountError() {
        com.smile.acelib.scheduler.SafeScheduler throwing = new com.smile.acelib.scheduler.SafeScheduler() {
            @Override public void cancelAll() { }
            @Override public ScheduledTask runGlobal(Runnable r) { return null; }
            @Override public ScheduledTask runAsync(Runnable r) { return null; }
            @Override public ScheduledTask runLater(Runnable r, long delay) { return null; }
            @Override public ScheduledTask runTimer(Runnable r, long delay, long period) { return null; }
            @Override public ScheduledTask runForPlayer(org.bukkit.entity.Player p, Runnable r) { return null; }
            @Override public ScheduledTask runForPlayerLater(org.bukkit.entity.Player p, Runnable r, long delay) { return null; }
            @Override public ScheduledTask runForEntity(org.bukkit.entity.Entity e, Runnable r) { return null; }
            @Override public ScheduledTask runAtLocation(org.bukkit.Location l, Runnable r) { return null; }
            @Override public List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) {
                throw new RuntimeException("recorder boom");
            }
        };
        Capabilities caps = capabilities(throwing,
            guiAlways(GuiResult.failed("x", "y")),
            bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.SchedulerReport r = probe.testScheduler(probePlayer());
        assertTrue(r.ready(), "scheduler wiring must succeed even when the recorder throws");
        assertEquals(0, r.errorCount());
    }

    /** Build a JDK-proxy Player returning a fixed UUID and zero Location for unit tests. */
    private static org.bukkit.entity.Player probePlayer() {
        return probePlayer(new org.bukkit.Location(null, 0.0, 64.0, 0.0));
    }

    private static org.bukkit.entity.Player probePlayer(org.bukkit.Location location) {
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.entity.Player.class.getClassLoader(),
            new Class<?>[]{org.bukkit.entity.Player.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getUniqueId")) return PLAYER_UUID;
                if (name.equals("getLocation")) return location;
                if (name.equals("isOnline")) return Boolean.TRUE;
                if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                    if (name.equals("equals")) return proxy == args[0];
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    return "probePlayer-" + PLAYER_UUID;
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

    // ----- testGui -----

    @Test
    void testGuiReportsAcceptedAndClosesSession() {
        GuiSession session = new GuiSession(PLAYER_UUID, 42L, "stub", "GUI capability smoke", 9, java.util.Set.of());
        StubGuiService gui = new StubGuiService(GuiResult.allowed(session));
        Capabilities caps = capabilities(new StubSafeScheduler(), gui,
            bedrockAlways(false, new StubFormService(FormSendResult.SENT, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.GuiReport r = probe.testGui(PLAYER_UUID, "GUI capability smoke");

        assertTrue(r.ready());
        assertEquals("ALLOWED", r.openState());
        assertEquals(1L, gui.openCount(), "openInventory called once");
        assertEquals(0L, gui.closeCount(), "probe must not close immediately; lifecycle is player-driven");
        assertEquals(Long.valueOf(42L), r.sessionGeneration());
        // session must remain active after successful open; player can close manually later
        GuiResult active = gui.getActiveSession(PLAYER_UUID);
        assertNotNull(active.session(), "session must remain active after probe open");
        assertEquals(42L, active.session().generation(), "generation must be retained");
        assertEquals(PLAYER_UUID, active.session().playerUuid());
        // explicit close later should succeed
        GuiResult closed = gui.closeInventory(PLAYER_UUID, r.sessionGeneration());
        assertEquals(1L, gui.closeCount(), "explicit close must be observed after probe");
        assertTrue(closed.isSuccess() || closed.isAllowed() || "SUCCESS".equals(closed.state().name()),
            "explicit close should succeed");
        assertTrue(gui.getActiveSession(PLAYER_UUID).isRejected() || gui.getActiveSession(PLAYER_UUID).session() == null,
            "session must be gone after explicit close");
    }

    @Test
    void testGuiReportsRejectedWithoutClose() {
        StubGuiService gui = new StubGuiService(GuiResult.rejected("SESSION_EXISTS", "occupied"));
        Capabilities caps = capabilities(new StubSafeScheduler(), gui,
            bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.GuiReport r = probe.testGui(PLAYER_UUID, "GUI capability smoke");

        assertTrue(r.ready());
        assertEquals("REJECTED", r.openState());
        assertEquals("SESSION_EXISTS", r.errorCode());
        assertEquals(0L, gui.closeCount(),
            "no close attempt when there is no live session");
    }

    @Test
    void testGuiFailsClosedWhenUuidNull() {
        Capabilities caps = capabilities(new StubSafeScheduler(),
            new StubGuiService(GuiResult.failed("x", "y")),
            bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.GuiReport r = probe.testGui((UUID) null, "GUI capability smoke");
        assertFalse(r.ready());
        assertEquals("缺少 Player 參數（UUID）", r.message());
    }

    // ----- testForm -----

    @Test
    void testFormReportsSentForBedrockPlayer() {
        StubFormService forms = new StubFormService(FormSendResult.SENT, "stub-form");
        BedrockService bedrock = bedrockAlways(true, forms);
        Capabilities caps = capabilities(new StubSafeScheduler(),
            guiAlways(GuiResult.failed("x", "y")), bedrock);
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        java.util.List<FormResponse> received = new java.util.ArrayList<>();
        M0CapabilityProbe.FormReport r = probe.testForm(PLAYER_UUID, "Form capability smoke",
            StubBedrockService.capturingSink(received));

        assertTrue(r.ready());
        assertEquals("SENT", r.sendResult());
        assertTrue(r.bedrockPlayer());
        assertEquals("stub-form", r.moduleStatus());
        assertEquals(1L, forms.sendCount());
        assertEquals(PLAYER_UUID, forms.lastUuid());
        assertNotNull(forms.lastSpec(), "Modal spec must be passed to sendForm");
        assertEquals("MODAL", forms.lastSpec().kind().name());
        assertEquals(1, received.size(), "response sink must be invoked when present");
    }

    @Test
    void testFormReportsRejectedForJavaPlayer() {
        StubFormService forms = new StubFormService(FormSendResult.REJECTED, "stub-form");
        BedrockService bedrock = bedrockAlways(false, forms);
        Capabilities caps = capabilities(new StubSafeScheduler(),
            guiAlways(GuiResult.failed("x", "y")), bedrock);
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.FormReport r = probe.testForm(PLAYER_UUID, "Form capability smoke", null);

        assertTrue(r.ready());
        assertEquals("REJECTED", r.sendResult());
        assertFalse(r.bedrockPlayer());
        assertEquals(1L, forms.sendCount(),
            "sendForm must be called once even when not a Bedrock player (Java receives the same Modal)");
    }

    @Test
    void testFormFailsClosedWhenUuidNull() {
        Capabilities caps = capabilities(new StubSafeScheduler(),
            guiAlways(GuiResult.failed("x", "y")),
            bedrockAlways(false, new StubFormService(FormSendResult.SENT, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.FormReport r = probe.testForm((UUID) null, "Form capability smoke", null);
        assertFalse(r.ready());
        assertEquals("缺少 Player 參數（UUID）", r.message());
    }

    @Test
    void testFormSurvivesThrowingBedrockLookup() {
        StubFormService forms = new StubFormService(FormSendResult.REJECTED, "stub-form");
        BedrockService throwing = new BedrockService() {
            @Override
            public boolean isBedrockPlayer(UUID uuid) {
                throw new RuntimeException("bedrock lookup down");
            }
            @Override
            public Optional<BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
                return Optional.empty();
            }
            @Override
            public FormService forms() { return forms; }
            @Override
            public String getModuleStatus() { return "throwing"; }
            @Override
            public void shutdown() { }
        };
        Capabilities caps = capabilities(new StubSafeScheduler(),
            guiAlways(GuiResult.failed("x", "y")), throwing);
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.FormReport r = probe.testForm(PLAYER_UUID, "Form capability smoke", null);
        assertTrue(r.ready(), "an exception in Bedrock lookup must NOT throw — only switch off bedrockPlayer");
        assertFalse(r.bedrockPlayer());
        assertEquals("REJECTED", r.sendResult());
    }

    // ----- testCancelAll -----

    @Test
    void testCancelAllCallsSchedulerCancelAllAndReportsResidualFromHandles() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = capabilities(scheduler,
            guiAlways(GuiResult.failed("x", "y")),
            bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")));
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        // Drive testScheduler first so the per-instance handle list contains real PLAYER/LOCATION
        // handles for testCancelAll() to evaluate against.
        probe.testScheduler(probePlayer());

        M0CapabilityProbe.CancelAllReport r = probe.testCancelAll();

        assertTrue(r.ready());
        assertEquals("0", r.taskCounts(),
            "StubSafeScheduler.cancelAll() flips every tracked task; residual must be 0");
        assertEquals(0L, scheduler.liveTaskCount(),
            "no captured handle may remain uncancelled after cancelAll()");
        assertTrue(scheduler.cancelAllCount() >= 1L, "scheduler.cancelAll() must be invoked");
    }

    @Test
    void testCancelAllSurvivesSchedulerThrowing() {
        SafeSchedulerThrowing stub = new SafeSchedulerThrowing();
        Capabilities caps = Capabilities.builder()
            .scheduler(stub)
            .guiService(guiAlways(GuiResult.failed("x", "y")))
            .bedrockService(bedrockAlways(false, new StubFormService(FormSendResult.REJECTED, "stub")))
            .platform(Platform.PAPER)
            .capability(new PlatformCapability(true, true, true, true))
            .build();
        M0CapabilityProbe probe = M0CapabilityProbe.fromCapabilities(caps).orElseThrow();

        M0CapabilityProbe.CancelAllReport r = probe.testCancelAll();
        assertFalse(r.ready());
        assertTrue(r.message().contains("cancelAll"),
            "error message must mention cancelAll");
        assertEquals(1, stub.cancelAttempts);
    }

    // ----- structural evidence -----

    @Test
    void productionSourceUsesAceLibSchedulerPublicFactory() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get(
            "src/main/java/com/smile/chunkland/capability/M0CapabilityProbe.java"));
        assertTrue(src.contains("AceLibScheduler.create("),
            "capability probe contract: must use the public factory");
        assertTrue(src.contains("cancelAll()"),
            "capability probe contract: must call cancelAll() at disable");
        assertFalse(src.contains("AceLibPlugin"),
            "must not import the implementation class");
        assertFalse(src.contains("FoliaScheduler"),
            "must not bypass the public AceLibScheduler facade");
        assertFalse(src.contains("PaperScheduler"),
            "must not bypass the public AceLibScheduler facade");
    }

    @Test
    void commandDescriptionAdvertisesTheFourModes() {
        assertTrue(M0CapabilityProbe.COMMAND_DESCRIPTION.contains("scheduler"));
        assertTrue(M0CapabilityProbe.COMMAND_DESCRIPTION.contains("gui"));
        assertTrue(M0CapabilityProbe.COMMAND_DESCRIPTION.contains("form"));
        assertTrue(M0CapabilityProbe.COMMAND_DESCRIPTION.contains("cancelall"));
    }

    // ----- stubs -----

    private static final class SafeSchedulerThrowing implements com.smile.acelib.scheduler.SafeScheduler {
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
        public ScheduledTask runForPlayer(org.bukkit.entity.Player p, Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runForPlayerLater(org.bukkit.entity.Player p, Runnable r, long delay) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runForEntity(org.bukkit.entity.Entity e, Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public ScheduledTask runAtLocation(org.bukkit.Location l, Runnable r) { throw new RuntimeException("unused"); }
        @Override
        public List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) { return List.of(); }
    }
}
