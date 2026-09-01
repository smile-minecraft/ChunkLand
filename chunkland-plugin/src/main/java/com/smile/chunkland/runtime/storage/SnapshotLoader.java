package com.smile.chunkland.runtime.storage;

import com.smile.chunkland.api.land.LandSnapshot;
import java.util.List;

/**
 * Injectable seam for the storage bootstrap load. Implementations may throw
 * {@link java.sql.SQLException}, {@link com.smile.chunkland.persistence.PersistenceException},
 * or any runtime exception to signal DB corruption, schema mismatch, malformed rows,
 * or connection failures. Callers must treat any exception as fail-closed.
 */
@FunctionalInterface
public interface SnapshotLoader {

    List<LandSnapshot> load() throws Exception;
}
