/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.menuitem;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.xpfarm.pizza.MenuService;
import org.xpfarm.pizza.config.MenuItemSpec;

/**
 * The four self-healing hooks for the physical menu item. Reads its live {@link MenuItemSpec} from
 * {@code menuService.config().menuItem()} on every event, so a {@code /pizza reload} that changes
 * the material, name, slot, or enabled flag takes effect on the next join or interaction with no
 * restart.
 *
 * <ul>
 *   <li><b>Give on join</b> — if enabled, the player has {@code pizza.use}, and they hold no tagged
 *       item, give one: into the configured hotbar slot if it is empty, else the first free slot.
 *   <li><b>Right-click to open</b> — a right-click (air or block) with the tagged item in the main
 *       hand cancels the interaction (so the pie is not eaten) and opens the {@code main} menu.
 *   <li><b>Can't drop</b> — dropping a tagged item is cancelled.
 *   <li><b>Survives death</b> — tagged items are stripped from death drops, and one is restored on
 *       respawn (one tick later, after the respawn inventory settles).
 * </ul>
 */
public final class MenuItemListener implements Listener {

    private static final String USE_PERMISSION = "pizza.use";
    private static final String MAIN_MENU = "main";

    private final Plugin plugin;
    private final MenuService menuService;
    private final MenuItemService items;

    public MenuItemListener(Plugin plugin, MenuService menuService, MenuItemService items) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.items = Objects.requireNonNull(items, "items");
    }

    private MenuItemSpec spec() {
        return menuService.config().menuItem();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveIfMissing(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // Respawn inventory is not settled inside the event; heal one tick later.
        plugin.getServer().getScheduler().runTask(plugin, () -> giveIfMissing(player));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // ignore the off-hand fire of the same right-click
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!items.isMenuItem(event.getItem())) {
            return;
        }
        event.setCancelled(true); // prevents eating the pumpkin pie / placing a block
        menuService.open(event.getPlayer(), MAIN_MENU);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (items.isMenuItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(items::isMenuItem);
    }

    private void giveIfMissing(Player player) {
        MenuItemSpec spec = spec();
        if (!spec.enabled() || !player.hasPermission(USE_PERMISSION) || items.hasMenuItem(player)) {
            return;
        }
        ItemStack item = items.create(spec);
        if (item == null) {
            return; // material did not resolve; feature unavailable
        }
        int slot = spec.slot();
        ItemStack existing = player.getInventory().getItem(slot);
        if (existing == null || existing.getType().isAir()) {
            player.getInventory().setItem(slot, item);
        } else {
            player.getInventory().addItem(item);
        }
    }
}
