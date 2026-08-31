package com.smile.chunkland.capability;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Test-only {@link FormService} stand-in. Configurable to return either
 * {@link FormSendResult#SENT} or {@link FormSendResult#REJECTED} and to either invoke or skip
 * the response callback. Tracks every call for assertions.
 */
final class StubFormService implements FormService {

    private final AtomicReference<FormSendResult> nextResult = new AtomicReference<>(FormSendResult.SENT);
    private final AtomicLong sendCount = new AtomicLong();
    private final AtomicLong shutdownCount = new AtomicLong();
    private final List<UUID> seenUuids;
    private final List<FormSpec> seenSpecs;
    private final String moduleStatus;

    StubFormService(FormSendResult initialResult, String moduleStatus) {
        this.nextResult.set(initialResult);
        this.moduleStatus = moduleStatus;
        this.seenUuids = new java.util.concurrent.CopyOnWriteArrayList<>();
        this.seenSpecs = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    void setNextResult(FormSendResult r) {
        nextResult.set(r);
    }

    long sendCount() {
        return sendCount.get();
    }

    long shutdownCount() {
        return shutdownCount.get();
    }

    UUID lastUuid() {
        return seenUuids.isEmpty() ? null : seenUuids.get(seenUuids.size() - 1);
    }

    FormSpec lastSpec() {
        return seenSpecs.isEmpty() ? null : seenSpecs.get(seenSpecs.size() - 1);
    }

    @Override
    public FormSendResult sendForm(UUID player, FormSpec spec) {
        sendCount.incrementAndGet();
        seenUuids.add(player);
        seenSpecs.add(spec);
        return nextResult.get();
    }

    @Override
    public FormSendResult sendForm(UUID player, FormSpec spec, Consumer<FormResponse> consumer) {
        sendCount.incrementAndGet();
        seenUuids.add(player);
        seenSpecs.add(spec);
        if (consumer != null) {
            try {
                consumer.accept(new FormResponse(com.smile.acelib.form.FormResponseStatus.VALID, 0, List.of()));
            } catch (RuntimeException ignored) {
                // callback failures must not bubble back to sendForm.
            }
        }
        return nextResult.get();
    }

    @Override
    public String getModuleStatus() {
        return moduleStatus;
    }

    @Override
    public void shutdown() {
        shutdownCount.incrementAndGet();
    }
}
