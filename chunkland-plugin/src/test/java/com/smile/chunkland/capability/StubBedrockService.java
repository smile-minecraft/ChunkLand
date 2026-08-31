package com.smile.chunkland.capability;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Test-only {@link BedrockService} stand-in. Defaults to "not a Bedrock player" with a no-op
 * FormService; {@link #forceBedrock(boolean)} controls the response of {@link #isBedrockPlayer}.
 * {@link #shutdown()} increments {@link #shutdownCount()} so tests can assert ChunkLand does
 * NOT call shutdown on shared AceLib services during {@link Capabilities#release()}.
 */
final class StubBedrockService implements BedrockService {

    private final AtomicBoolean bedrock = new AtomicBoolean(false);
    private final AtomicLong lookupCount = new AtomicLong();
    private final AtomicLong shutdownCount = new AtomicLong();
    private final FormService forms;
    private final String moduleStatus;

    StubBedrockService(FormService forms) {
        this(forms, "ready-stub");
    }

    StubBedrockService(FormService forms, String moduleStatus) {
        this.forms = forms;
        this.moduleStatus = moduleStatus;
    }

    void forceBedrock(boolean v) {
        bedrock.set(v);
    }

    long lookupCount() {
        return lookupCount.get();
    }

    long shutdownCount() {
        return shutdownCount.get();
    }

    @Override
    public boolean isBedrockPlayer(UUID uuid) {
        lookupCount.incrementAndGet();
        return bedrock.get();
    }

    @Override
    public Optional<BedrockPlayerInfo> getPlayerInfo(UUID uuid) {
        lookupCount.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public FormService forms() {
        return forms;
    }

    @Override
    public String getModuleStatus() {
        return moduleStatus;
    }

    @Override
    public void shutdown() {
        shutdownCount.incrementAndGet();
        // no-op semantics; only the counter matters for assertions.
    }

    /** Convenience consumer used by tests that want to capture a {@link com.smile.acelib.form.FormResponse}. */
    static Consumer<com.smile.acelib.form.FormResponse> capturingSink(java.util.List<com.smile.acelib.form.FormResponse> sink) {
        return sink::add;
    }
}
