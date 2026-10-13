package com.smile.chunkland.gui;

import com.smile.chunkland.rule.LandRuleView;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only, fail-closed model behind the rule management layer.
 * Skeleton: always unavailable until the view mapping lands.
 */
public final class ManagementRuleModel {

    private static final ManagementRuleModel UNAVAILABLE =
            new ManagementRuleModel(false, List.of());

    private final boolean available;
    private final List<LandRuleView> rows;

    private ManagementRuleModel(boolean available, List<LandRuleView> rows) {
        this.available = available;
        this.rows = rows;
    }

    /** Fail-closed model: no rows. */
    public static ManagementRuleModel unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Build rows from one display triple per rule; {@code null} or empty
     * fails closed. Rows keep the {@link LandRuleType} declaration order so
     * row slots stay stable.
     */
    public static ManagementRuleModel fromViews(List<LandRuleView> views) {
        if (views == null || views.isEmpty()) {
            return unavailable();
        }
        List<LandRuleView> rows = new ArrayList<>(views);
        rows.sort(java.util.Comparator.comparingInt(
                view -> view == null || view.rule() == null
                        ? Integer.MAX_VALUE : view.rule().ordinal()));
        rows.removeIf(java.util.Objects::isNull);
        if (rows.isEmpty()) {
            return unavailable();
        }
        return new ManagementRuleModel(true, List.copyOf(rows));
    }

    /** Whether the model carries confirmed rows. */
    public boolean available() {
        return available;
    }

    /** Confirmed rows in rule order; empty when unavailable. */
    public List<LandRuleView> rows() {
        return rows;
    }
}
