package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionVisualizationBudgetTest {
    @Test
    void defaultsAreConservative() {
        SelectionVisualizationBudget budget = SelectionVisualizationBudget.defaults();

        assertEquals(256, budget.maxSegments());
        assertEquals(256, budget.maxParticlesPerTick());
        assertEquals(64, budget.renderDistanceBlocks());
        assertEquals(10, budget.refreshIntervalTicks());
    }

    @Test
    void rejectsNonPositiveAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(0, 128, 48, 10));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(256, 0, 48, 10));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(256, 128, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(256, 128, 48, 0));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(-1, 128, 48, 10));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(4097, 128, 48, 10));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(256, 1025, 48, 10));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(256, 128, 129, 10));
        assertThrows(IllegalArgumentException.class, () -> new SelectionVisualizationBudget(256, 128, 48, 201));
    }

    @Test
    void acceptsBoundaryValues() {
        SelectionVisualizationBudget budget = new SelectionVisualizationBudget(4096, 1024, 128, 200);

        assertEquals(4096, budget.maxSegments());
        assertEquals(1024, budget.maxParticlesPerTick());
        assertEquals(128, budget.renderDistanceBlocks());
        assertEquals(200, budget.refreshIntervalTicks());
    }

    @Test
    void budgetsWithSameValuesAreEqual() {
        assertEquals(
                new SelectionVisualizationBudget(256, 256, 64, 10),
                SelectionVisualizationBudget.defaults());
        assertFalse(
                new SelectionVisualizationBudget(255, 256, 64, 10).equals(SelectionVisualizationBudget.defaults()));
        assertEquals(
                List.of(new SelectionVisualizationBudget(256, 256, 64, 10)).get(0),
                SelectionVisualizationBudget.defaults());
    }
}
