package com.smile.chunkland.gui;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * First-layer entry and second-layer permission detail over the shared
 * {@link GuiNavigator}.
 *
 * <p>The first layer is the land-management entry: one button routes to the
 * second layer through the caller-owned {@link ManagementGuiActions} seam.
 * The second layer renders one button per confirmed
 * {@link ManagementPermissionRow}: the row shows the final effect and its
 * source layer, and clicking it routes a change request through the same
 * seam — the pages never mutate domain state and never mention an
 * {@code EVERYONE} entry. Missing data yields the fail-closed unavailable
 * page with no rows and no unconfirmed details.
 *
 * <p>All pages are Bukkit-free immutable values; navigation generations stay
 * with the upstream sessions owned by the navigator.
 */
public final class ManagementGuiPages {

    /** First-layer land-management entry. */
    public static final String ROOT_PAGE_ID = "chunkland:land-manage-root";

    /** Second-layer confirmed permission detail. */
    public static final String DETAILS_PAGE_ID = "chunkland:land-manage-permissions";

    /** Fail-closed page for missing data or resolver failure. */
    public static final String UNAVAILABLE_PAGE_ID = "chunkland:land-manage-unavailable";

    /** Slot of the first-layer entry button on the root page. */
    public static final int ENTRY_SLOT = 11;

    /** Slot of the back button on detail pages. */
    public static final int BACK_SLOT = 26;

    /** Inventory sizes: root rows fit one chest row, details fit one page. */
    static final int ROOT_SIZE = 27;

    static final int DETAILS_SIZE = 27;

    private ManagementGuiPages() {
    }

    /**
     * First-layer entry page: one button routes to the second layer.
     * A {@code null} seam reads as fail-closed (no-op buttons).
     */
    public static GuiPage rootPage(ManagementGuiActions actions) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        GuiButton entry = new GuiButton(ENTRY_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.openDetails(click);
        });
        return GuiPage.of(ROOT_PAGE_ID, "Land Management", ROOT_SIZE, List.of(entry),
                List.of("Open the permission detail view."),
                List.of(new GuiPage.RenderItem(ENTRY_SLOT,
                        "Open the permission detail view.", List.of(), "entry")));
    }

    /**
     * Second-layer detail page for a confirmed model. A {@code null} or
     * unavailable model yields the fail-closed unavailable page.
     *
     * <p>The page title carries the structured effect summary and the page
     * lines carry every row as {@code ACTION: OUTCOME @ LAYER} (conflict rows
     * suffixed with {@code !CONFLICT}, followed by their three remedies), so
     * the final effects, source layers and conflict resolutions live in the
     * GUI render data itself.
     */
    public static GuiPage detailsPage(ManagementGuiModel model, ManagementGuiActions actions) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        if (model == null || !model.available() || model.rows().isEmpty()) {
            return unavailablePage(seam);
        }
        List<ManagementPermissionRow> rows = model.rows();
        int capacity = DETAILS_SIZE - 1;
        List<GuiButton> buttons = new ArrayList<>(rows.size() + 1);
        List<String> lines = new ArrayList<>(rows.size() * 2 + 1);
        List<GuiPage.RenderItem> renderItems = new ArrayList<>(rows.size() + 1);
        int deny = 0;
        int allow = 0;
        for (int index = 0; index < rows.size() && index < capacity; index++) {
            ManagementPermissionRow row = rows.get(index);
            ProtectionActionType action = row.action();
            buttons.add(new GuiButton(index, click -> {
                Objects.requireNonNull(click, "click");
                seam.requestChange(click, action);
            }));
            if (row.outcome() == PermissionState.DENY) {
                deny++;
            } else {
                allow++;
            }
            String head = row.action().name() + ": " + row.outcome().name()
                    + " @ " + row.layer().name()
                    + (row.conflict() ? " !CONFLICT" : "");
            lines.add(head);
            List<String> itemLore = row.conflict()
                    ? model.remediesFor(row).stream().map(remedy -> "  - " + remedy).toList()
                    : List.of();
            renderItems.add(new GuiPage.RenderItem(index, head, itemLore,
                    row.outcome() == PermissionState.DENY ? "deny" : "allow"));
            lines.addAll(itemLore);
        }
        buttons.add(new GuiButton(BACK_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.back(click);
        }));
        renderItems.add(new GuiPage.RenderItem(BACK_SLOT, "Back", List.of(), "back"));
        String title = "Land Permissions (" + deny + " DENY, " + allow + " ALLOW)";
        return GuiPage.of(DETAILS_PAGE_ID, title, DETAILS_SIZE, buttons, lines, renderItems);
    }

    /**
     * Fail-closed page: no rows, no unconfirmed details, only a back button.
     * The lines render the generic fail-closed notice visibly and never leak
     * row data.
     */
    public static GuiPage unavailablePage(ManagementGuiActions actions) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        GuiButton back = new GuiButton(BACK_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.back(click);
        });
        List<String> lines = ManagementGuiModel.unavailable().remediesFor(null);
        return GuiPage.of(UNAVAILABLE_PAGE_ID,
                "Land Management (unavailable)", DETAILS_SIZE, List.of(back),
                lines, List.of(new GuiPage.RenderItem(BACK_SLOT, "Back", lines, "unavailable")));
    }
}
