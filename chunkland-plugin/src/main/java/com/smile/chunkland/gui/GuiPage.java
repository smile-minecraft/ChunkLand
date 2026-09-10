package com.smile.chunkland.gui;

import com.smile.acelib.gui.GuiArgument;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable render data for one GUI screen: identity, title, inventory size
 * and the slot button bindings. A page carries no session state; generations
 * live in the navigator's per-player stack and always come from the upstream
 * session returned at open time.
 */
public final class GuiPage {

    private final String id;
    private final String title;
    private final int size;
    private final Map<Integer, GuiButton> buttons;

    private GuiPage(String id, String title, int size, Map<Integer, GuiButton> buttons) {
        this.id = id;
        this.title = title;
        this.size = size;
        this.buttons = buttons;
    }

    /**
     * Build an immutable page. Duplicate or out-of-range slots are rejected
     * immediately so a misbuilt page can never reach the upstream service.
     *
     * @param id page identity used in click contexts; never {@code null} or empty
     * @param title inventory title; never {@code null}
     * @param size inventory size; must be positive (the upstream service
     *     validates chest-row shapes at open time and the navigator treats a
     *     rejection as fail-closed)
     * @param buttons slot bindings; never {@code null} (may be empty)
     */
    public static GuiPage of(String id, String title, int size, List<GuiButton> buttons) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(buttons, "buttons");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        Map<Integer, GuiButton> copy = new LinkedHashMap<>();
        for (GuiButton button : buttons) {
            Objects.requireNonNull(button, "button");
            if (button.slot() >= size) {
                throw new IllegalArgumentException(
                    "slot " + button.slot() + " is outside page size " + size);
            }
            if (copy.containsKey(button.slot())) {
                throw new IllegalArgumentException("duplicate slot: " + button.slot());
            }
            copy.put(button.slot(), button);
        }
        return new GuiPage(id, title, size, Collections.unmodifiableMap(copy));
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public int size() {
        return size;
    }

    /** @return unmodifiable slot-to-button view. */
    public Map<Integer, GuiButton> buttons() {
        return buttons;
    }

    /** @return bound slots; unmodifiable. */
    public Set<Integer> slots() {
        return Collections.unmodifiableSet(buttons.keySet());
    }

    /** @return the binding for {@code slot}, or empty when the slot has none. */
    public Optional<GuiButton> buttonAt(int slot) {
        return Optional.ofNullable(buttons.get(slot));
    }

    /**
     * Render this page as an upstream open argument for {@code playerUuid}.
     * Button slots travel as protected slots so clicks on them stay routed
     * through upstream validation instead of falling through to the game.
     */
    public GuiArgument toArgument(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return GuiArgument.of(playerUuid, title, size, buttons.keySet());
    }
}
