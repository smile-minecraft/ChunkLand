package com.smile.chunkland.gui;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

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

    /** Third-layer confirm page for one pending land-default toggle. */
    public static final String CONFIRM_PAGE_ID = "chunkland:land-manage-confirm";

    /** Slot of the first-layer entry button on the root page. */
    public static final int ENTRY_SLOT = 11;

    /** Slot of the back button on detail pages. */
    public static final int BACK_SLOT = 26;

    /** Slot of the confirm button on the confirm page. */
    public static final int CONFIRM_SLOT = 11;

    /** Slot of the cancel button on the confirm page. */
    public static final int CANCEL_SLOT = 15;

    /** Inventory sizes: root rows fit one chest row, details fit one page. */
    static final int ROOT_SIZE = 27;

    static final int DETAILS_SIZE = 27;

    static final int CONFIRM_SIZE = 27;

    /**
     * One pending land-default toggle plus its button callbacks.
     *
     * @param action toggled action; never {@code null}
     * @param current current land-default display name, or {@code null}
     *     when unset (renders the injected unset wording)
     * @param target target land-default display name; never {@code null}
     * @param confirm confirm-button callback; {@code null} reads as no-op
     * @param cancel cancel-button callback; {@code null} reads as no-op
     * @param back back-button callback; {@code null} reads as no-op
     * @param failed whether this render follows a failed attempt, in
     *     which case the injected failure line is shown as well
     */
    public record ConfirmRequest(ProtectionActionType action, String current,
            String target, GuiAction confirm, GuiAction cancel, GuiAction back,
            boolean failed) {
        public ConfirmRequest {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(target);
        }
    }

    private ManagementGuiPages() {
    }

    /**
     * First-layer entry page: one button routes to the second layer.
     * A {@code null} seam reads as fail-closed (no-op buttons). All
     * visible copy comes from {@code texts}; the page never embeds prose.
     */
    public static GuiPage rootPage(ManagementGuiActions actions, ManagementGuiTexts texts) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        Objects.requireNonNull(texts, "texts");
        GuiButton entry = new GuiButton(ENTRY_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.openDetails(click);
        });
        return GuiPage.of(ROOT_PAGE_ID, texts.rootTitle(), ROOT_SIZE, List.of(entry),
                texts.entryLines(),
                List.of(new GuiPage.RenderItem(ENTRY_SLOT,
                        texts.entryName(), List.of(), "entry")));
    }

    /**
     * Second-layer detail page for a confirmed model. A {@code null} or
     * unavailable model yields the fail-closed unavailable page.
     *
     * <p>The page title carries the structured effect summary and the page
     * lines carry every row head plus the conflict remedies, so the final
     * effects, source layers and conflict resolutions live in the GUI
     * render data itself. Row heads, the conflict marker, the lore bullet
     * and the remedy sentences all arrive through {@code texts}; only the
     * action, outcome and layer display names are interpolated in.
     */
    public static GuiPage detailsPage(ManagementGuiModel model, ManagementGuiActions actions,
            ManagementGuiTexts texts) {
        return detailsPage(model, actions, texts, null);
    }

    /**
     * Second-layer detail page with one land-default lore line per row.
     *
     * <p>When {@code landDefaults} is present, every row item gains the
     * injected default lore line for its action ({@code INHERIT} or a
     * lookup failure renders the unset wording); a {@code null} lookup
     * keeps the legacy composition without the default line.
     */
    public static GuiPage detailsPage(ManagementGuiModel model, ManagementGuiActions actions,
            ManagementGuiTexts texts, Function<ProtectionActionType, PermissionState> landDefaults) {
        return detailsPage(model, actions, texts, landDefaults, null);
    }

    /**
     * Second-layer detail page with toggleable and view-only rows.
     *
     * <p>When {@code toggleable} is present, rows it rejects keep their
     * display item (plus the injected read-only marker) but carry a no-op
     * button: the slot stays protected so the painted item cannot be
     * picked up, yet the click routes no change request and reaches no
     * write seam. A {@code null} predicate preserves the legacy
     * all-toggleable composition.
     */
    public static GuiPage detailsPage(ManagementGuiModel model, ManagementGuiActions actions,
            ManagementGuiTexts texts, Function<ProtectionActionType, PermissionState> landDefaults,
            Predicate<ProtectionActionType> toggleable) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        Objects.requireNonNull(texts, "texts");
        if (model == null || !model.available() || model.rows().isEmpty()) {
            return unavailablePage(seam, texts);
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
            if (isToggleable(toggleable, action)) {
                buttons.add(new GuiButton(index, click -> {
                    Objects.requireNonNull(click, "click");
                    seam.requestChange(click, action);
                }));
            } else {
                buttons.add(new GuiButton(index, click -> {
                    Objects.requireNonNull(click, "click");
                }));
            }
            if (row.outcome() == PermissionState.DENY) {
                deny++;
            } else {
                allow++;
            }
            String head = texts.rowHead().head(row.action().name(), row.outcome().name(),
                    row.layer().name(), row.conflict());
            lines.add(head);
            List<String> itemLore = row.conflict()
                    ? List.copyOf(texts.rowRemedies().lore(
                            row.layer().name(), row.outcome().name(), true))
                    : List.of();
            if (landDefaults != null) {
                List<String> withDefault = new ArrayList<>(itemLore);
                withDefault.add(texts.defaultLore().line(row.action().name(),
                        defaultDisplayName(landDefaults, row.action())));
                withDefault.add(isToggleable(toggleable, action)
                        ? texts.toggleLine()
                        : texts.readOnlyLine());
                itemLore = List.copyOf(withDefault);
            } else if (!isToggleable(toggleable, action)) {
                List<String> readOnly = new ArrayList<>(itemLore);
                readOnly.add(texts.readOnlyLine());
                itemLore = List.copyOf(readOnly);
            }
            renderItems.add(new GuiPage.RenderItem(index, head, itemLore,
                    row.outcome() == PermissionState.DENY ? "deny" : "allow"));
            lines.addAll(itemLore);
        }
        buttons.add(new GuiButton(BACK_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.back(click);
        }));
        renderItems.add(new GuiPage.RenderItem(BACK_SLOT, texts.backName(), List.of(), "back"));
        String title = texts.detailsTitle().title(deny, allow);
        return GuiPage.of(DETAILS_PAGE_ID, title, DETAILS_SIZE, buttons, lines, renderItems);
    }

    /**
     * Whether one row may open the confirm toggle. A {@code null}
     * predicate preserves the legacy all-toggleable composition; a
     * throwing predicate reads as view-only (fail-closed).
     */
    private static boolean isToggleable(Predicate<ProtectionActionType> toggleable,
            ProtectionActionType action) {
        if (toggleable == null) {
            return true;
        }
        try {
            return toggleable.test(action);
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    /**
     * Display name for one action's land default, or {@code null} when
     * unset so the injected texts render the unset wording. A throwing
     * or {@code null}-returning lookup reads as unset (fail-closed).
     */
    private static String defaultDisplayName(
            Function<ProtectionActionType, PermissionState> landDefaults,
            ProtectionActionType action) {
        PermissionState state;
        try {
            state = landDefaults.apply(action);
        } catch (RuntimeException unresolved) {
            return null;
        }
        if (state == null || state == PermissionState.INHERIT) {
            return null;
        }
        return state.name();
    }

    /**
     * Third-layer confirm page for one pending toggle. The title names
     * the action; the confirm button lore carries the current land
     * default line plus the target-state line so the pending transition
     * stays visible, and a failed attempt appends the injected failure
     * line. The page never writes anything itself: every button only
     * reaches its injected callback.
     */
    public static GuiPage confirmPage(ConfirmRequest request, ManagementGuiTexts texts) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(texts, "texts");
        ManagementGuiTexts.ConfirmTexts confirmTexts = texts.confirmTexts();
        GuiAction onConfirm = request.confirm() == null ? click -> {
        } : request.confirm();
        GuiAction onCancel = request.cancel() == null ? click -> {
        } : request.cancel();
        GuiAction onBack = request.back() == null ? click -> {
        } : request.back();
        String actionName = request.action().name();
        String currentLine = texts.defaultLore().line(actionName, request.current());
        String targetLine = confirmTexts.targetLine().line(request.target());
        List<String> lore = request.failed()
                ? List.of(currentLine, targetLine, confirmTexts.failedLine())
                : List.of(currentLine, targetLine);
        List<String> lines = List.copyOf(lore);
        List<GuiButton> buttons = List.of(
                new GuiButton(CONFIRM_SLOT, onConfirm),
                new GuiButton(CANCEL_SLOT, onCancel),
                new GuiButton(BACK_SLOT, onBack));
        List<GuiPage.RenderItem> renderItems = List.of(
                new GuiPage.RenderItem(CONFIRM_SLOT, confirmTexts.confirmName(),
                        lore, "confirm"),
                new GuiPage.RenderItem(CANCEL_SLOT, confirmTexts.cancelName(),
                        List.of(), "cancel"),
                new GuiPage.RenderItem(BACK_SLOT, confirmTexts.backName(),
                        List.of(), "back"));
        return GuiPage.of(CONFIRM_PAGE_ID, confirmTexts.title().title(actionName),
                CONFIRM_SIZE, buttons, lines, renderItems);
    }

    /**
     * Fail-closed page: no rows, no unconfirmed details, only a back button.
     * The lines render the generic fail-closed notice visibly and never leak
     * row data. Title, lines and the back label arrive through {@code texts}.
     */
    public static GuiPage unavailablePage(ManagementGuiActions actions, ManagementGuiTexts texts) {
        ManagementGuiActions seam = actions == null ? ManagementGuiActions.noop() : actions;
        Objects.requireNonNull(texts, "texts");
        GuiButton back = new GuiButton(BACK_SLOT, click -> {
            Objects.requireNonNull(click, "click");
            seam.back(click);
        });
        List<String> lines = texts.unavailableLines();
        return GuiPage.of(UNAVAILABLE_PAGE_ID,
                texts.unavailableTitle(), DETAILS_SIZE, List.of(back),
                lines, List.of(new GuiPage.RenderItem(BACK_SLOT, texts.backName(), lines,
                        "unavailable")));
    }
}
