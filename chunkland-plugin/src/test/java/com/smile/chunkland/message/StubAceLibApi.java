package com.smile.chunkland.message;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.platform.Platform;

/**
 * Test-only factory for {@link AceLibApi} stand-ins. {@code AceLibApi} is final, so we use
 * the upstream static factories rather than subclassing. A ready AceLib API always exposes a
 * non-null {@code BedrockService}, so the only
 * reachable "not usable" states are null or uninitialized; {@link #readyWithBedrock()} returns
 * a ready API whose bedrock service is present.
 */
@SuppressWarnings("deprecation")
final class StubAceLibApi {

    private StubAceLibApi() {
    }

    static AceLibApi unready() {
        return AceLibApi.uninitialized();
    }

    static AceLibApi readyWithBedrock() {
        return AceLibApi.ready("1.3.0", Platform.UNKNOWN, () -> true, () -> {
        });
    }
}
