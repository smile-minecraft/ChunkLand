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
 * Immutable render data for one GUI screen: identity, title, inventory size,
 * visible text lines and the slot button bindings. A page carries no session
 * state; generations live in the navigator's per-player stack and always come
 * from the upstream session returned at open time.
 *
 * <p>The text lines are framework-owned visible render data: one entry per
 * described row (for example {@code "BLOCK_BREAK: DENY @ LAND_BINDING"}).
 * The public upstream open argument only transports title, size and
 * protected slots, so the lines travel with the page value itself for
 * callers that render them through another channel.
 */
public final class GuiPage {

    private final String id;
    private final String title;
    private final int size;
    private final Map<Integer, GuiButton> buttons;
    private final List<String> lines;
    private final List<RenderItem> renderItems;

    private GuiPage(String id, String title, int size, Map<Integer, GuiButton> buttons,
            List<String> lines, List<RenderItem> renderItems) {
        this.id = id;
        this.title = title;
        this.size = size;
        this.buttons = buttons;
        this.lines = lines;
        this.renderItems = renderItems;
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
        return of(id, title, size, buttons, List.of(), List.of());
    }

    /**
     * Build an immutable page with visible text lines.
     *
     * @param id page identity used in click contexts; never {@code null} or empty
     * @param title inventory title; never {@code null}
     * @param size inventory size; must be positive (the upstream service
     *     validates chest-row shapes at open time and the navigator treats a
     *     rejection as fail-closed)
     * @param buttons slot bindings; never {@code null} (may be empty)
     * @param lines visible text lines in display order; never {@code null}
     *     (may be empty), entries never {@code null}
     */
    public static GuiPage of(String id, String title, int size, List<GuiButton> buttons,
            List<String> lines) {
        return of(id, title, size, buttons, lines, List.of());
    }

    /**
     * Build an immutable page with visible text lines and Bukkit-free render
     * item bindings. Render items carry only the slot, text, and a material
     * hint; the Bukkit adapter owns material lookup and inventory mutation.
     *
     * @param renderItems display items in slot order; never {@code null}
     */
    public static GuiPage of(String id, String title, int size, List<GuiButton> buttons,
            List<String> lines, List<RenderItem> renderItems) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(buttons, "buttons");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(renderItems, "renderItems");
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
        Set<Integer> itemSlots = new java.util.HashSet<>();
        for (RenderItem item : renderItems) {
            Objects.requireNonNull(item, "renderItem");
            if (item.slot() >= size) {
                throw new IllegalArgumentException(
                    "render slot " + item.slot() + " is outside page size " + size);
            }
            if (!itemSlots.add(item.slot())) {
                throw new IllegalArgumentException("duplicate render slot: " + item.slot());
            }
        }
        List<String> owned = List.copyOf(lines);
        return new GuiPage(id, title, size, Collections.unmodifiableMap(copy), owned,
                List.copyOf(renderItems));
    }

    /**
     * Bukkit-free description of one visible inventory item.
     *
     * @param slot zero-based inventory slot
     * @param name visible item name
     * @param lore visible lore lines in display order
     * @param materialHint stable semantic hint resolved by the Bukkit adapter
     */
    public record RenderItem(int slot, String name, List<String> lore, String materialHint) {

        public RenderItem {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative: " + slot);
            }
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(lore, "lore");
            Objects.requireNonNull(materialHint, "materialHint");
            lore = List.copyOf(lore);
        }
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

    /** @return visible text lines in display order; unmodifiable, may be empty. */
    public List<String> lines() {
        return lines;
    }

    /** @return visible item bindings in display order; unmodifiable, may be empty. */
    public List<RenderItem> renderItems() {
        return renderItems;
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
