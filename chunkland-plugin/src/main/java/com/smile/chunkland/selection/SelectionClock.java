package com.smile.chunkland.selection;

import java.time.Instant;

/** Clock seam used by timeout and activity tests without sleeping. */
@FunctionalInterface
public interface SelectionClock {
    Instant now();
}
