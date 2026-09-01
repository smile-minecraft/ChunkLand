package com.smile.chunkland.persistence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryRedTest {
    @Test
    void repositoriesExistAndExecutorOnly() {
        // This test will fail to compile until 5 repositories exist
        // Intentionally references expected types
        assertNotNull(LandRepository.class);
        assertNotNull(ChunkRepository.class);
        assertNotNull(SubLandRepository.class);
        assertNotNull(AuditRepository.class);
        assertNotNull(LedgerRepository.class);
    }
}
