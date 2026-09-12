package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The A×B guidance size must be derived only from the accepted chunk set's
 * min/max chunk coordinates: an empty set has nothing to announce and must
 * never produce a guessed size.
 */
class SelectionRectangleDimensionsTest {

    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD_ID, x, z);
    }

    @Test
    void emptyChunkSetHasNoDimensions() {
        assertTrue(SelectionRectangleDimensions.from(Set.of()).isEmpty(),
                "an empty selection must not announce a size");
        assertTrue(SelectionRectangleDimensions.from(null).isEmpty(),
                "a missing chunk set must not announce a size");
    }

    @Test
    void singleChunkIsOneByOne() {
        Optional<SelectionRectangleDimensions> dims = SelectionRectangleDimensions.from(Set.of(chunk(5, -7)));
        assertEquals(Optional.of(new SelectionRectangleDimensions(1, 1)), dims);
    }

    @Test
    void spanIsDerivedFromMinMaxChunkCoordinatesIncludingNegatives() {
        Optional<SelectionRectangleDimensions> dims =
                SelectionRectangleDimensions.from(Set.of(chunk(-2, 1), chunk(3, -1), chunk(0, 0)));
        assertEquals(Optional.of(new SelectionRectangleDimensions(6, 3)), dims,
                "width spans chunkX -2..3 (6), depth spans chunkZ -1..1 (3)");
    }

    @Test
    void messageVarsExposeWidthAndHeightForThePipeline() {
        Map<String, Object> vars = new SelectionRectangleDimensions(2, 3).messageVars();
        assertEquals(Map.of("width", 2, "height", 3), vars);
    }
}
