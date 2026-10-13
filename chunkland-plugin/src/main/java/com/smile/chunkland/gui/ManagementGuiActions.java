package com.smile.chunkland.gui;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Objects;

/**
 * Caller-owned seam behind the management GUI buttons.
 *
 * <p>Button callbacks never touch Bukkit, SQL, the world or domain
 * collections directly. They only reach this seam; the production wiring
 * (owned by the caller, next to the scheduler) resolves snapshots, re-checks
 * the shared management gate and delegates mutations to the existing
 * handler/service path. Tests observe the seam instead of the domain.
 */
public interface ManagementGuiActions {

    /** First-layer entry was clicked: open the second-layer detail page. */
    void openDetails(GuiClickContext click);

    /** Back button was clicked: return to the previous page. */
    void back(GuiClickContext click);

    /**
     * A second-layer row was clicked.
     *
     * @param click validated click; never {@code null}
     * @param action the row's action; never {@code null}
     */
    void requestChange(GuiClickContext click, ProtectionActionType action);

    /**
     * The rule-list entry was clicked: open the rule list page. Default
     * no-op so existing seams keep compiling; production overrides it with
     * the gated rule-list navigation.
     */
    default void openRuleDetails(GuiClickContext click) {
        Objects.requireNonNull(click, "click");
    }

    /**
     * A rule-list row was clicked. Default no-op so existing seams keep
     * compiling; production overrides it with the gated rule confirm flow.
     *
     * @param click validated click; never {@code null}
     * @param rule the row's rule; never {@code null}
     */
    default void requestRuleChange(GuiClickContext click, LandRuleType rule) {
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(rule, "rule");
    }

    /** Fail-closed seam: every button is a no-op. */
    static ManagementGuiActions noop() {
        return new ManagementGuiActions() {
            @Override
            public void openDetails(GuiClickContext click) {
                Objects.requireNonNull(click, "click");
            }

            @Override
            public void back(GuiClickContext click) {
                Objects.requireNonNull(click, "click");
            }

            @Override
            public void requestChange(GuiClickContext click, ProtectionActionType action) {
                Objects.requireNonNull(click, "click");
                Objects.requireNonNull(action, "action");
            }

            @Override
            public String toString() {
                return "ManagementGuiActions.noop";
            }
        };
    }
}
