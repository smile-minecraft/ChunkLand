package com.smile.chunkland.gui;

/**
 * Single slot-button callback for the Java GUI framework.
 *
 * <p>A {@code GuiAction} never mutates domain state directly. It emits an
 * abstract handling step (for example a command request or a UI transition);
 * any future management wiring built on top must resolve its inputs and pass
 * the shared management permission gate before reaching a mutation pipeline.
 * The framework itself stays Bukkit-free so Folia region work always stays
 * with the scheduler seam owned by the caller, never inside GUI callbacks.</p>
 */
@FunctionalInterface
public interface GuiAction {

    /**
     * Handle a validated click. Never throws out of the framework: the
     * navigator absorbs a {@link RuntimeException} so one bad button cannot
     * break dispatch for other players.
     *
     * @param click validated click context; never {@code null}
     */
    void handle(GuiClickContext click);
}
