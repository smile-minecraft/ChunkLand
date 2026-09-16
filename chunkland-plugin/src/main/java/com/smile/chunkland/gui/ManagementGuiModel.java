package com.smile.chunkland.gui;

import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainLayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Read-only, fail-closed model behind the second management layer.
 *
 * <p>Rows are built from the same immutable snapshot path the enforcement
 * reads: one {@link PermissionExplain} per action (resolved through the
 * shared resolver), plus the contexts that produced them for conflict
 * detection. A conflict means the deciding binding layer held both
 * {@code ALLOW} and {@code DENY} for the action; the final effect is then
 * always {@code DENY} under flat DENY-first precedence.
 *
 * <p>Missing data, unknown actions and resolver failures yield
 * {@link #unavailable()}: no rows, no remedies with unconfirmed details, and
 * no mutation. Every remedy list carries exactly three entries and never
 * names an {@code EVERYONE} entry, a player or a binding — there is no
 * {@code EVERYONE} binding in the model, only the default layers.
 */
public final class ManagementGuiModel {

    private static final ManagementGuiModel UNAVAILABLE =
            new ManagementGuiModel(false, List.of(), Map.of());

    private final boolean available;
    private final List<ManagementPermissionRow> rows;
    private final Map<ProtectionActionType, PermissionContext> contexts;

    private ManagementGuiModel(boolean available,
            List<ManagementPermissionRow> rows,
            Map<ProtectionActionType, PermissionContext> contexts) {
        this.available = available;
        this.rows = rows;
        this.contexts = contexts;
    }

    /**
     * Precedence rank for display sorting: {@code DENY} first, then
     * {@code ALLOW}, then {@code INHERIT} (fall-through).
     */
    public static int rank(PermissionState state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case DENY -> 0;
            case ALLOW -> 1;
            case INHERIT -> 2;
        };
    }

    /** Fail-closed model: no rows, generic remedies only. */
    public static ManagementGuiModel unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Build rows from one explain per action.
     *
     * @param explains structured outcomes; {@code null} or empty fails closed
     * @param contexts the contexts that produced them (for conflict
     *                 detection); {@code null} reads as empty
     */
    public static ManagementGuiModel fromExplains(List<PermissionExplain> explains,
            Map<ProtectionActionType, PermissionContext> contexts) {
        if (explains == null || explains.isEmpty()) {
            return unavailable();
        }
        Map<ProtectionActionType, PermissionContext> owned = copyContexts(contexts);
        List<ManagementPermissionRow> rows = new ArrayList<>();
        for (PermissionExplain explain : explains) {
            if (explain == null || explain.action() == null
                    || explain.outcome() == null || explain.layer() == null) {
                continue;
            }
            if (explain.outcome() == PermissionState.INHERIT) {
                continue;
            }
            boolean conflict = hasConflict(explain, owned.get(explain.action()));
            rows.add(new ManagementPermissionRow(
                    explain.action(), explain.outcome(), explain.layer(), conflict));
        }
        if (rows.isEmpty()) {
            return unavailable();
        }
        rows.sort(Comparator.comparingInt(
                (ManagementPermissionRow row) -> rank(row.outcome()))
                .thenComparing(row -> row.action().name()));
        return new ManagementGuiModel(true, List.copyOf(rows), owned);
    }

    /**
     * Build rows by explaining each context through {@code explainer}.
     * A throwing or {@code null}-returning explainer call skips that action
     * fail-closed; when nothing survives the model is unavailable.
     */
    public static ManagementGuiModel fromContexts(
            Map<ProtectionActionType, PermissionContext> contexts,
            BiFunction<PermissionContext, SubLandId, PermissionExplain> explainer) {
        if (contexts == null || contexts.isEmpty() || explainer == null) {
            return unavailable();
        }
        List<PermissionExplain> explains = new ArrayList<>();
        for (Map.Entry<ProtectionActionType, PermissionContext> entry : contexts.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                PermissionExplain explained = explainer.apply(entry.getValue(), null);
                if (explained != null) {
                    explains.add(explained);
                }
            } catch (RuntimeException failure) {
                // One failing action never poisons the rest; it is skipped
                // fail-closed and the model below drops to unavailable when
                // nothing survives.
            }
        }
        return fromExplains(explains, contexts);
    }

    /** Whether the model carries confirmed rows. */
    public boolean available() {
        return available;
    }

    /** Confirmed rows in display order; empty when unavailable. */
    public List<ManagementPermissionRow> rows() {
        return rows;
    }

    /**
     * Exactly three resolutions for {@code row}: the final effect stays on
     * the row itself, these are the ways to change it. Unknown or untracked
     * rows get the generic fail-closed triple. Never names an
     * {@code EVERYONE} entry, a player or a binding.
     */
    public List<String> remediesFor(ManagementPermissionRow row) {
        if (row == null || !available || !rows.contains(row)) {
            return List.of(
                    "Outcome unavailable: data missing or the resolver failed (fail-closed).",
                    "Re-open the view after the snapshot reloads.",
                    "Mutations stay gated; no change was applied.");
        }
        String layer = row.layer().name();
        String outcome = row.outcome().name();
        if (row.conflict()) {
            return List.of(
                    "Remove the DENY binding on " + layer
                            + " through the existing binding flow (still gated).",
                    "Keep the DENY and remove the conflicting ALLOW on " + layer
                            + " instead.",
                    "Move the intended ALLOW to a higher-precedence layer: "
                            + "SubLand binding ahead of Land binding, binding ahead of default.");
        }
        return List.of(
                "No conflict: " + layer + " decides " + outcome + ".",
                "To change it, update " + layer
                        + " through the existing management flows (still gated).",
                "Leave defaults INHERIT to inherit; missing values deny fail-closed.");
    }

    private static Map<ProtectionActionType, PermissionContext> copyContexts(
            Map<ProtectionActionType, PermissionContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return Map.of();
        }
        Map<ProtectionActionType, PermissionContext> copy =
                new EnumMap<>(ProtectionActionType.class);
        for (Map.Entry<ProtectionActionType, PermissionContext> entry : contexts.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Whether the deciding binding layer held both {@code ALLOW} and
     * {@code DENY} for the explained action. Only binding layers aggregate,
     * so every other layer answers {@code false}. Any failure answers
     * {@code false} (fail-closed: an unconfirmed conflict is not a conflict).
     */
    private static boolean hasConflict(PermissionExplain explain, PermissionContext ctx) {
        try {
            if (explain == null || ctx == null) {
                return false;
            }
            if (explain.outcome() != PermissionState.DENY) {
                return false;
            }
            List<PermissionBinding> bindings = switch (explain.layer()) {
                case SUBLAND_BINDING -> ctx.subLandBindings();
                case LAND_BINDING -> ctx.landBindings();
                default -> null;
            };
            if (bindings == null || bindings.isEmpty()) {
                return false;
            }
            boolean seenAllow = false;
            boolean seenDeny = false;
            for (PermissionBinding binding : bindings) {
                if (binding == null || binding.permission() == null) {
                    continue;
                }
                if (binding.permission().action() != explain.action()) {
                    continue;
                }
                if (binding.permission().state() == PermissionState.DENY) {
                    seenDeny = true;
                } else if (binding.permission().state() == PermissionState.ALLOW) {
                    seenAllow = true;
                }
            }
            return seenAllow && seenDeny;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "ManagementGuiModel(available=" + available + ", rows=" + rows.size() + ")";
    }
}
