package com.smile.chunkland.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.platform.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class AceLibLifecycleTest {

    private static final AceLibApi READY = AceLibApi.ready("1.2.0", Platform.UNKNOWN, () -> true, () -> {});

    private static final class RecordingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Logger recordingLogger(RecordingHandler handler) {
        Logger logger = Logger.getLogger("chunkland-lifecycle-test");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        return logger;
    }

    @Test
    void missingProviderWarnsAndDisablesWithoutThrowing() {
        AceLibBridge bridge = new AceLibBridge();
        RecordingHandler handler = new RecordingHandler();
        Logger logger = recordingLogger(handler);
        boolean[] disabled = {false};
        boolean ok = AceLibLifecycle.enable(bridge, () -> null, logger, () -> disabled[0] = true);

        assertFalse(ok);
        assertTrue(disabled[0], "self-disable action must run on missing provider");
        assertFalse(bridge.isAcquired());
        assertTrue(handler.records.stream()
            .anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("AceLib")),
            "a warning mentioning AceLib must be logged");
    }

    @Test
    void notReadyProviderWarnsAndDisables() {
        AceLibBridge bridge = new AceLibBridge();
        RecordingHandler handler = new RecordingHandler();
        Logger logger = recordingLogger(handler);
        boolean[] disabled = {false};
        boolean ok = AceLibLifecycle.enable(bridge, () -> () -> AceLibApi.uninitialized(), logger, () -> disabled[0] = true);

        assertFalse(ok);
        assertTrue(disabled[0]);
        assertFalse(bridge.isAcquired());
    }

    @Test
    void readyProviderEnablesWithoutDisable() {
        AceLibBridge bridge = new AceLibBridge();
        RecordingHandler handler = new RecordingHandler();
        Logger logger = recordingLogger(handler);
        boolean[] disabled = {false};
        boolean ok = AceLibLifecycle.enable(bridge, () -> () -> READY, logger, () -> disabled[0] = true);

        assertTrue(ok);
        assertFalse(disabled[0], "disable must NOT run when provider is ready");
        assertTrue(bridge.isAcquired());
    }

    @Test
    void resolverThrowsRuntimeExceptionWarnsAndDisables() {
        AceLibBridge bridge = new AceLibBridge();
        RecordingHandler handler = new RecordingHandler();
        Logger logger = recordingLogger(handler);
        boolean[] disabled = {false};
        boolean ok = AceLibLifecycle.enable(bridge, () -> {
            throw new RuntimeException("services manager down");
        }, logger, () -> disabled[0] = true);

        assertFalse(ok, "enable must fail closed on provider exception");
        assertTrue(disabled[0], "self-disable must run on provider exception");
        assertFalse(bridge.isAcquired());
        assertTrue(handler.records.stream()
            .anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("AceLib")),
            "a warning mentioning AceLib must be logged");
    }
}
