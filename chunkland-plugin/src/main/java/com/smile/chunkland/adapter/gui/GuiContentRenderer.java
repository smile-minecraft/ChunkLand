package com.smile.chunkland.adapter.gui;

import com.smile.acelib.gui.GuiAsyncRequest;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Bukkit bridge for the supported AceLib async-update channel.
 *
 * <p>The custom {@link GuiPage} remains platform-free. This adapter captures
 * the online player before beginning an update, gives AceLib the update page,
 * and lets AceLib invoke the renderer in the player's region context. The
 * renderer only writes display items; it never registers an interaction or
 * mutates domain state.</p>
 *
 * <p>All update failures are intentionally best-effort. A rejected request,
 * a scheduler refusal, a closed inventory, an out-of-range slot, or a Bukkit
 * exception leaves navigation and click handling untouched.</p>
 */
public final class GuiContentRenderer {

    private static final int PAGE_INDEX = 0;
    private static final int TOTAL_PAGES = 1;

    private final GuiService guiService;
    private final Function<UUID, Player> playerResolver;
    private final Function<Material, ItemStack> itemStackFactory;

    public GuiContentRenderer(GuiService guiService,
            Function<UUID, Player> playerResolver) {
        this(guiService, playerResolver, material -> new ItemStack(material));
    }

    public GuiContentRenderer(GuiService guiService,
            Function<UUID, Player> playerResolver,
            Function<Material, ItemStack> itemStackFactory) {
        this.guiService = Objects.requireNonNull(guiService, "guiService");
        this.playerResolver = Objects.requireNonNull(playerResolver, "playerResolver");
        this.itemStackFactory = Objects.requireNonNull(itemStackFactory, "itemStackFactory");
    }

    /**
     * Render one custom page through the AceLib async-update contract.
     *
     * @return {@code true} only when both upstream operations report
     *     {@link GuiResult#isSuccess()}; every other outcome is silent
     */
    public boolean render(UUID playerUuid, long sessionGeneration, com.smile.chunkland.gui.GuiPage page) {
        if (playerUuid == null || page == null) {
            return false;
        }
        Player player;
        try {
            player = playerResolver.apply(playerUuid);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (player == null) {
            return false;
        }
        try {
            GuiResult begun = guiService.beginAsyncUpdate(playerUuid, sessionGeneration,
                    PAGE_INDEX);
            if (begun == null || !begun.isSuccess()) {
                return false;
            }
            GuiAsyncRequest request = begun.asyncRequest();
            if (request == null) {
                return false;
            }
            GuiPage<com.smile.chunkland.gui.GuiPage.RenderItem> update = updatePage(page);
            GuiResult applied = guiService.applyAsyncUpdate(request, update,
                    () -> paint(player, page));
            return applied != null && applied.isSuccess();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static GuiPage<com.smile.chunkland.gui.GuiPage.RenderItem> updatePage(
            com.smile.chunkland.gui.GuiPage page) {
        List<com.smile.chunkland.gui.GuiPage.RenderItem> items = page.renderItems();
        return items.isEmpty()
                ? GuiPage.empty()
                : GuiPage.content(PAGE_INDEX, TOTAL_PAGES, items);
    }

    private void paint(Player player, com.smile.chunkland.gui.GuiPage page) {
        try {
            if (player == null || page == null || page.renderItems().isEmpty()) {
                return;
            }
            var view = player.getOpenInventory();
            Inventory top = view == null ? null : view.getTopInventory();
            if (top == null) {
                return;
            }
            int size = top.getSize();
            for (com.smile.chunkland.gui.GuiPage.RenderItem item : page.renderItems()) {
                if (item.slot() < 0 || item.slot() >= size) {
                    return;
                }
            }
            for (com.smile.chunkland.gui.GuiPage.RenderItem item : page.renderItems()) {
                ItemStack stack = itemStackFactory.apply(material(item.materialHint()));
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    meta.displayName(Component.text(item.name())
                            .decoration(TextDecoration.ITALIC, false));
                    if (!item.lore().isEmpty()) {
                        meta.lore(item.lore().stream()
                                .map(line -> Component.text(line)
                                        .decoration(TextDecoration.ITALIC, false))
                                .toList());
                    }
                    stack.setItemMeta(meta);
                }
                top.setItem(item.slot(), stack);
            }
        } catch (RuntimeException ignored) {
            // A view can close between validation and paint; content is best-effort.
        }
    }

    private static Material material(String hint) {
        return switch (hint) {
            case "deny" -> Material.RED_DYE;
            case "allow" -> Material.LIME_DYE;
            case "confirm" -> Material.GREEN_WOOL;
            case "cancel" -> Material.RED_WOOL;
            case "back" -> Material.ARROW;
            case "unavailable" -> Material.BARRIER;
            case "members", "member" -> Material.PLAYER_HEAD;
            case "bans" -> Material.IRON_BARS;
            case "banned" -> Material.SKELETON_SKULL;
            case "add" -> Material.EMERALD;
            case "previous", "next" -> Material.SPECTRAL_ARROW;
            case "empty" -> Material.PAPER;
            default -> Material.COMPASS;
        };
    }
}
