package com.smile.chunkland.runtime.mutation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe ordered recorder of stage names for tests.
 * The recorded list is immutable when returned via {@link #snapshot()}.
 */
public final class StageRecorder {

    private final List<String> stages = Collections.synchronizedList(new ArrayList<>());

    public void record(String stage) {
        stages.add(stage);
    }

    public List<String> snapshot() {
        synchronized (stages) {
            return List.copyOf(new ArrayList<>(stages));
        }
    }

    public void clear() {
        synchronized (stages) {
            stages.clear();
        }
    }
}
