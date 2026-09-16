package com.smile.chunkland.gui;

import com.smile.chunkland.api.permission.ProtectionActionType;
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
