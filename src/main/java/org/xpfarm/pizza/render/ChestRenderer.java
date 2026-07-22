/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.render;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.xpfarm.pizza.MenuService;
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.ButtonImage;
import org.xpfarm.pizza.menu.Menu;

/**
 * Renders a {@link Menu} as a chest-style inventory for a Java player.
 *
 * <p>This is the only {@link MenuRenderer} unit tests can reach a running server: {@link
 * #rowsFor(int)} is a pure static extracted specifically so the slot-arithmetic it drives is
 * testable without one. Everything else here — inventory creation, click routing, state cleanup —
 * is exercised on a real server, not by a unit test.
 *
 * <h2>Permission filtering</h2>
 *
 * <p>A button whose permission the player lacks is omitted from the rendered inventory entirely,
 * never shown greyed out, and the relative order of the surviving buttons is preserved — that
 * filtering is {@link MenuService#visibleTo}, the single source both this class and {@link
 * BedrockRenderer} call, so the two can never disagree about what a player sees. The filtered list
 * built in {@link #open} is captured once per open and is the same list a click is resolved
 * against, so a button can never move between build and click.
 *
 * <h2>State tracking</h2>
 *
 * <p>Each player's currently open Pizza menu is tracked in {@link #openMenus}, keyed by {@link
 * UUID} so a stale {@link Player} reference is never held. The tracked {@link Menu} and its
 * filtered {@link Button} list are the exact objects passed to {@link #open}; both are immutable
 * records, so a config reload that swaps the live {@code PizzaConfig} underneath cannot corrupt an
 * already-open menu. The entry is removed on {@link InventoryCloseEvent} and on {@link
 * PlayerQuitEvent} — nothing else ever clears it, so leaving either handler out would leak one
 * entry per menu open for the life of the server.
 *
 * <h2>Click handling</h2>
 *
 * <p>A click is cancelled before anything else runs, once it is established that the event
 * belongs to this player's tracked menu — otherwise a child can drag the button item out of the
 * chest and it becomes a real item in their inventory. A click in the player's own inventory
 * (bottom half of the view) or outside the inventory entirely (raw slot {@code -999}, surfaced by
 * Bukkit as a {@code null} {@link InventoryClickEvent#getClickedInventory()}) is cancelled the
 * same way but never resolved to a button.
 */
public final class ChestRenderer implements MenuRenderer, Listener {

    private final Plugin plugin;
    private final ButtonSink sink;
    private final Map<UUID, OpenMenu> openMenus = new HashMap<>();

    public ChestRenderer(Plugin plugin, ButtonSink sink) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void open(Player player, Menu menu) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");

        List<Button> visible = MenuService.visibleTo(menu, player::hasPermission);
        int rows = rowsFor(visible.size());
        int capacity = rows * 9;

        Inventory inventory = Bukkit.createInventory(null, capacity, titleOf(menu));
        int shown = Math.min(visible.size(), capacity);
        for (int slot = 0; slot < shown; slot++) {
            inventory.setItem(slot, itemFor(visible.get(slot)));
        }
        if (visible.size() > capacity) {
            plugin.getLogger()
                    .warning("menu '" + menu.id() + "' has " + visible.size()
                            + " visible buttons but a chest only fits " + capacity
                            + "; the rest are not shown");
        }

        openMenus.put(player.getUniqueId(), new OpenMenu(menu, visible, inventory));
        player.openInventory(inventory);
    }

    /**
     * How many chest rows (1-6, 9 slots each) fit {@code buttonCount} buttons: at least one row,
     * never more than the six a chest inventory supports, otherwise the smallest number of full
     * rows that holds every button. Kept a pure static, with no Bukkit dependency, specifically so
     * this arithmetic is unit-testable without a running server.
     */
    public static int rowsFor(int buttonCount) {
        int rows = (buttonCount + 8) / 9;
        return Math.min(6, Math.max(1, rows));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OpenMenu state = openMenus.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        // Guards against a stale tracking entry: if the player's top inventory is not the one we
        // handed out, this event belongs to something else entirely and must not be touched.
        if (!state.inventory().equals(event.getView().getTopInventory())) {
            return;
        }

        // Cancelled before anything else runs: without this a child can shift-click or drag the
        // button item out of the chest and it becomes a real item in their inventory.
        event.setCancelled(true);

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(state.inventory())) {
            // null: the click landed outside the inventory entirely (raw slot -999).
            // otherwise: the click landed in the player's own inventory, not the menu.
            return;
        }

        int slot = event.getSlot();
        List<Button> visible = state.buttons();
        if (slot < 0 || slot >= visible.size()) {
            return;
        }

        sink.activate(player, state.menu(), visible.get(slot));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        OpenMenu state = openMenus.get(id);
        if (state != null && state.inventory().equals(event.getInventory())) {
            openMenus.remove(id);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    private static Component titleOf(Menu menu) {
        String title = menu.title();
        return Component.text(title == null || title.isBlank() ? menu.id() : title);
    }

    private ItemStack itemFor(Button button) {
        ItemStack item = new ItemStack(materialFor(button));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(button.label()));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Resolves a button's configured {@link ButtonImage} to a chest icon. Only the {@code
     * "material"} image type maps onto an {@link ItemStack} directly; anything else (a texture
     * path meant for the Bedrock renderer, a missing image, an unrecognised material name) falls
     * back to a plain icon rather than throwing — a bad icon is a cosmetic problem, not a reason to
     * refuse to render the menu.
     */
    private Material materialFor(Button button) {
        ButtonImage image = button.image();
        if (image != null && "material".equalsIgnoreCase(image.type()) && image.data() != null) {
            try {
                return Material.valueOf(image.data().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger()
                        .warning("button " + button.id() + " has an unrecognised material '"
                                + image.data() + "'; using a default icon");
            }
        }
        return Material.PAPER;
    }

    /** One player's currently open Pizza menu: the menu itself, its permission-filtered button
     * list in display order, and the exact inventory instance handed to the player — used to tell
     * a stale tracking entry apart from the inventory that is actually on screen. */
    private record OpenMenu(Menu menu, List<Button> buttons, Inventory inventory) {}
}
