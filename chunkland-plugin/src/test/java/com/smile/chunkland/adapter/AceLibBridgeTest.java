package com.smile.chunkland.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.platform.Platform;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class AceLibBridgeTest {

    private static final AceLibApi READY = AceLibApi.ready("1.3.0", Platform.UNKNOWN, () -> true, () -> {});
    private static final AceLibApi NOT_READY = AceLibApi.uninitialized();

    private static AceLibBridge.ProviderResolver resolverReturning(AceLibApi.AceLibProvider provider) {
        return () -> provider;
    }

    @Test
    void missingProviderIsNotAcquiredAndDoesNotThrow() {
        AceLibBridge bridge = new AceLibBridge();
        boolean ok = bridge.acquire(resolverReturning(null));
        assertFalse(ok);
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void nullApiIsNotAcquired() {
        AceLibBridge bridge = new AceLibBridge();
        boolean ok = bridge.acquire(resolverReturning(() -> null));
        assertFalse(ok);
        assertFalse(bridge.isAcquired());
    }

    @Test
    void notReadyApiIsNotAcquired() {
        AceLibBridge bridge = new AceLibBridge();
        boolean ok = bridge.acquire(resolverReturning(() -> NOT_READY));
        assertFalse(ok);
        assertFalse(bridge.isAcquired());
    }

    @Test
    void readyApiIsAcquired() {
        AceLibBridge bridge = new AceLibBridge();
        assertTrue(bridge.acquire(resolverReturning(() -> READY)));
        assertTrue(bridge.isAcquired());
        assertTrue(bridge.isReady());
        assertSame(READY, bridge.getApi());
    }

    @Test
    void reacquireAdoptsNewApiAfterProviderChange() {
        AceLibBridge bridge = new AceLibBridge();
        assertTrue(bridge.acquire(resolverReturning(() -> READY)));
        assertSame(READY, bridge.getApi());

        AceLibApi ready2 = AceLibApi.ready("1.3.0", Platform.UNKNOWN, () -> true, () -> {});
        assertNotSame(READY, ready2);
        assertTrue(bridge.reacquire(resolverReturning(() -> ready2)));
        assertSame(ready2, bridge.getApi());
    }

    @Test
    void reloadReacquiresAndAdoptsNewApi() {
        AceLibBridge bridge = new AceLibBridge();
        AtomicReference<AceLibApi.AceLibProvider> current = new AtomicReference<>(() -> READY);
        AceLibBridge.ProviderResolver resolver = current::get;
        assertTrue(bridge.acquire(resolver));
        assertSame(READY, bridge.getApi());

        // After reload, the provider serves a fresh ready facade.
        AceLibApi ready2 = AceLibApi.ready("1.3.0", Platform.UNKNOWN, () -> true, () -> {});
        current.set(() -> ready2);
        bridge.reload(resolver);
        assertSame(ready2, bridge.getApi());
    }

    @Test
    void reloadWithoutAcquisitionIsSafeNoOp() {
        AceLibBridge bridge = new AceLibBridge();
        assertFalse(bridge.isAcquired());
        bridge.reload(resolverReturning(null)); // must not throw
        assertFalse(bridge.isAcquired());
    }

    @Test
    void releaseIsIdempotent() {
        AceLibBridge bridge = new AceLibBridge();
        bridge.acquire(resolverReturning(() -> READY));
        assertTrue(bridge.isAcquired());
        bridge.release();
        assertFalse(bridge.isAcquired());
        bridge.release(); // second call must be safe
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void resolverThrowsRuntimeExceptionIsNotAcquired() {
        AceLibBridge bridge = new AceLibBridge();
        boolean ok = bridge.acquire(() -> {
            throw new RuntimeException("services manager down");
        });
        assertFalse(ok);
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void providerApiThrowsRuntimeExceptionIsNotAcquired() {
        AceLibBridge bridge = new AceLibBridge();
        AceLibApi.AceLibProvider provider = () -> {
            throw new RuntimeException("provider api down");
        };
        boolean ok = bridge.acquire(resolverReturning(provider));
        assertFalse(ok);
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void isReadyThrowsRuntimeExceptionIsNotAcquired() {
        AceLibBridge bridge = new AceLibBridge();
        // A candidate whose isReady() throws (readyCheck blows up).
        AceLibApi candidate = AceLibApi.ready("1.3.0", Platform.UNKNOWN,
            () -> {
                throw new RuntimeException("ready check down");
            }, () -> {});
        boolean ok = bridge.acquire(resolverReturning(() -> candidate));
        assertFalse(ok);
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void reloadThrowsRuntimeExceptionClearsApi() {
        AceLibBridge bridge = new AceLibBridge();
        // A ready API whose reload() throws (onReload blows up).
        AceLibApi readyButExplosive = AceLibApi.ready("1.3.0", Platform.UNKNOWN,
            () -> true, () -> {
                throw new RuntimeException("reload down");
            });
        assertTrue(bridge.acquire(resolverReturning(() -> readyButExplosive)));
        assertTrue(bridge.isAcquired());

        bridge.reload(resolverReturning(() -> readyButExplosive));
        assertFalse(bridge.isAcquired(), "stale facade must not survive a failed reload");
        assertNull(bridge.getApi());
    }

    @Test
    void reacquireThrowsRuntimeExceptionClearsApi() {
        AceLibBridge bridge = new AceLibBridge();
        assertTrue(bridge.acquire(resolverReturning(() -> READY)));
        assertTrue(bridge.isAcquired());

        boolean ok = bridge.reacquire(() -> {
            throw new RuntimeException("reacquire down");
        });
        assertFalse(ok);
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void reloadNullWithExistingApiClearsState() {
        AceLibBridge bridge = new AceLibBridge();
        assertTrue(bridge.acquire(resolverReturning(() -> READY)));
        assertTrue(bridge.isAcquired());

        // An invalid (null) resolver must fail closed: no exception escapes and the
        // previously held facade is discarded rather than left stale.
        bridge.reload(null);
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void isReadyFalseClearsApi() {
        AceLibBridge bridge = new AceLibBridge();
        AtomicBoolean ready = new AtomicBoolean(true);
        AceLibApi api = AceLibApi.ready("1.3.0", Platform.UNKNOWN, ready::get, () -> {});
        assertTrue(bridge.acquire(resolverReturning(() -> api)));
        assertTrue(bridge.isAcquired());

        // The held facade becomes not-ready: isReady() must fail closed and discard it.
        ready.set(false);
        assertFalse(bridge.isReady());
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }

    @Test
    void isReadyThrowsRuntimeExceptionClearsApi() {
        AceLibBridge bridge = new AceLibBridge();
        AtomicBoolean blow = new AtomicBoolean(false);
        AceLibApi api = AceLibApi.ready("1.3.0", Platform.UNKNOWN,
            () -> {
                if (blow.get()) {
                    throw new RuntimeException("ready down");
                }
                return true;
            }, () -> {});
        assertTrue(bridge.acquire(resolverReturning(() -> api)));
        assertTrue(bridge.isAcquired());

        // The held facade's readiness check starts throwing: isReady() must fail closed
        // (no exception escapes) and discard the stale facade.
        blow.set(true);
        assertFalse(bridge.isReady());
        assertFalse(bridge.isAcquired());
        assertNull(bridge.getApi());
    }
}
