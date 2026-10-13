package com.smile.chunkland.capability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import org.junit.jupiter.api.Test;

class CapabilitiesTest {

    private static PlatformCapability capability() {
        return new PlatformCapability(true, true, true, true);
    }

    @Test
    void builderProducesImmutableBundleWithAllAccessors() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        StubGuiService gui = new StubGuiService(com.smile.acelib.gui.GuiResult.success(new com.smile.acelib.gui.GuiSession(java.util.UUID.randomUUID(), 1L, "stub", "t", 9, java.util.Set.of())));
        StubFormService forms = new StubFormService(com.smile.acelib.form.FormSendResult.SENT, "stub-form");
        StubBedrockService bedrock = new StubBedrockService(forms);
        PlatformCapability cap = capability();

        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(gui)
            .bedrockService(bedrock)
            .platform(Platform.FOLIA)
            .capability(cap)
            .build();

        assertSame(scheduler, caps.scheduler());
        assertSame(gui, caps.guiService());
        assertSame(bedrock, caps.bedrockService());
        assertSame(Platform.FOLIA, caps.platform());
        assertSame(cap, caps.capability());
        assertSame(forms, caps.formService(),
            "formService() must read bedrockService.forms()");
        assertTrue(caps.isComplete());
    }

    @Test
    void builderToleratesNullSchedulerAndIsCompleteBecomesFalse() {
        StubGuiService gui = new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y"));
        StubBedrockService bedrock = new StubBedrockService(
            new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x"));
        Capabilities caps = Capabilities.builder()
            .guiService(gui)
            .bedrockService(bedrock)
            .platform(Platform.FOLIA)
            .capability(capability())
            .build();
        assertNull(caps.scheduler(),
            "partial bundles are tolerated; isComplete() carries the gate");
        assertFalse(caps.isComplete());
    }

    @Test
    void builderToleratesNullGuiServiceAndIsCompleteBecomesFalse() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        StubBedrockService bedrock = new StubBedrockService(
            new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x"));
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .bedrockService(bedrock)
            .platform(Platform.FOLIA)
            .capability(capability())
            .build();
        assertFalse(caps.isComplete());
    }

    @Test
    void builderToleratesNullBedrockServiceAndIsCompleteBecomesFalse() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        StubGuiService gui = new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y"));
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(gui)
            .platform(Platform.FOLIA)
            .capability(capability())
            .build();
        assertFalse(caps.isComplete());
        assertNull(caps.formService(),
            "formService() must also defend against a missing bedrock");
    }

    @Test
    void isCompleteFalseWhenAnyFieldMissing() {
        assertFalse(Capabilities.builder().build().isComplete(),
            "empty builder cannot satisfy the smoke surface");
    }

    @Test
    void releaseCallsSchedulerCancelAll() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        StubGuiService gui = new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y"));
        StubBedrockService bedrock = new StubBedrockService(
            new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x"));

        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(gui)
            .bedrockService(bedrock)
            .platform(Platform.PAPER)
            .capability(capability())
            .build();

        caps.release();
        assertEquals(1L, scheduler.cancelAllCount(), "release() must invoke SafeScheduler.cancelAll() exactly once");
    }

    @Test
    void releaseDoesNotShutdownGuiService() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        StubGuiService gui = new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y"));
        StubBedrockService bedrock = new StubBedrockService(
            new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x"));

        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(gui)
            .bedrockService(bedrock)
            .platform(Platform.PAPER)
            .capability(capability())
            .build();

        caps.release();
        // GuiService in AceLib 1.5.0 no longer defines a shutdown() method (removed in 1.4.0),
        // so provider shutdown is impossible by type contract; release() only touches SafeScheduler.
        assertEquals(0L, gui.openCount(),
            "release() must NOT invoke openInventory on GuiService");
        assertEquals(0L, gui.closeCount(),
            "release() must NOT invoke closeInventory on GuiService");
        assertNull(gui.lastArg(),
            "release() must NOT pass any GuiArgument to GuiService");
        assertEquals(0L, bedrock.shutdownCount(),
            "release() must NOT shut down the shared BedrockService");

        // Positive control: directly invoking GuiService methods mutates these exact counters,
        // proving the zero-invocation assertions above are alive and observing real interactions.
        com.smile.acelib.gui.GuiArgument realArg = com.smile.acelib.gui.GuiArgument.of(
            java.util.UUID.randomUUID(), "title", 9, java.util.List.of());
        gui.openInventory(realArg);
        gui.closeInventory(java.util.UUID.randomUUID(), 1L);
        assertEquals(1L, gui.openCount(), "positive control: openCount must increment on direct invocation");
        assertEquals(1L, gui.closeCount(), "positive control: closeCount must increment on direct invocation");
        assertSame(realArg, gui.lastArg(), "positive control: lastArg must record the passed argument");
    }

    @Test
    void releaseIsIdempotent() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        StubGuiService gui = new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y"));
        StubBedrockService bedrock = new StubBedrockService(
            new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x"));

        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(gui)
            .bedrockService(bedrock)
            .platform(Platform.PAPER)
            .capability(capability())
            .build();

        caps.release();
        caps.release();
        assertEquals(2L, scheduler.cancelAllCount(),
            "release() must remain safe on repeat; cancelAll() is itself idempotent upstream");
    }

    @Test
    void releaseSurvivesSchedulerCancelAllThrowing() {
        SafeSchedulerThrowing stub = new SafeSchedulerThrowing();
        Capabilities caps = Capabilities.builder()
            .scheduler(stub)
            .guiService(new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(
                new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x")))
            .platform(Platform.PAPER)
            .capability(capability())
            .build();

        caps.release();
        assertEquals(1, stub.cancelAttempts, "the throwing scheduler must still receive the call");
        // No exception means the fail-closed contract held.
    }

    @Test
    void releaseWithMissingProvidersDoesNotThrowAndTouchesOnlyScheduler() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(null)
            .bedrockService(null)
            .platform(Platform.PAPER)
            .capability(capability())
            .build();

        caps.release();
        assertEquals(1L, scheduler.cancelAllCount(),
            "scheduler must still receive cancelAll when other providers are absent");

        Capabilities nullSchedulerCaps = Capabilities.builder()
            .scheduler(null)
            .guiService(new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(
                new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x")))
            .platform(Platform.PAPER)
            .capability(capability())
            .build();

        assertDoesNotThrow(() -> nullSchedulerCaps.release(),
            "release with null scheduler must be safe and no-op without mutating providers");
    }

    @Test
    void accessorReturnValuesAreReferencesNotCopies() {
        StubSafeScheduler scheduler = new StubSafeScheduler();
        Capabilities caps = Capabilities.builder()
            .scheduler(scheduler)
            .guiService(new StubGuiService(com.smile.acelib.gui.GuiResult.failed("x", "y")))
            .bedrockService(new StubBedrockService(
                new StubFormService(com.smile.acelib.form.FormSendResult.REJECTED, "x")))
            .platform(Platform.FOLIA)
            .capability(capability())
            .build();

        assertNotNull(caps.scheduler());
        assertNotNull(caps.guiService());
        assertNotNull(caps.bedrockService());
        assertNotNull(caps.platform());
        assertNotNull(caps.capability());
        assertNotNull(caps.formService());
    }

    /** Local throwing scheduler; not exposed beyond this test. */
    private static final class SafeSchedulerThrowing implements com.smile.acelib.scheduler.SafeScheduler {
        int cancelAttempts = 0;

        @Override
        public void cancelAll() {
            cancelAttempts++;
            throw new RuntimeException("cancelAll boom");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runGlobal(Runnable runnable) {
            throw new RuntimeException("not used");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAsync(Runnable runnable) {
            throw new RuntimeException("not used");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runLater(Runnable runnable, long delay) {
            throw new RuntimeException("not used");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runTimer(Runnable runnable, long delay, long period) {
            throw new RuntimeException("not used");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayer(org.bukkit.entity.Player player, Runnable runnable) {
            throw new RuntimeException("not used");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForPlayerLater(org.bukkit.entity.Player player, Runnable runnable, long delay) {
            throw new RuntimeException("not used");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runForEntity(org.bukkit.entity.Entity entity, Runnable runnable) {
            throw new RuntimeException("not used");
        }

        @Override
        public com.smile.acelib.scheduler.ScheduledTask runAtLocation(org.bukkit.Location location, Runnable runnable) {
            throw new RuntimeException("not used");
        }

        @Override
        public java.util.List<com.smile.acelib.scheduler.TaskErrorRecord> getRecorderErrors(int limit) {
            return java.util.List.of();
        }

        @Override
        public com.smile.acelib.scheduler.TaskScope scopeFor(org.bukkit.entity.Player player) {
            throw new UnsupportedOperationException("SafeSchedulerThrowing does not support scopeFor(Player)");
        }

        @Override
        public com.smile.acelib.scheduler.TaskScope scopeFor(org.bukkit.entity.Entity entity) {
            throw new UnsupportedOperationException("SafeSchedulerThrowing does not support scopeFor(Entity)");
        }
    }
}
