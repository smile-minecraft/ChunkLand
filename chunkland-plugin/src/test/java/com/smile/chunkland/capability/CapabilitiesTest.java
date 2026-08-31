package com.smile.chunkland.capability;

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
        assertEquals(0L, gui.shutdownCount(),
            "release() must NOT shut down the shared GuiService");
        assertEquals(0L, bedrock.shutdownCount(),
            "release() must NOT shut down the shared BedrockService");
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
    }
}
