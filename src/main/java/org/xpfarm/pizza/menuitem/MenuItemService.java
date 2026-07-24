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
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.xpfarm.pizza.config.MenuItemSpec;
import org.xpfarm.pizza.render.MenuText;

/**
 * Builds the physical hotbar menu item, stamps it with a persistent tag, and detects that tag.
 *
 * <p>Every identity check keys off the PDC tag {@code pizza:menu_item} — never the material or the
 * display name — so a renamed item, or an unrelated pumpkin pie, can neither break detection nor
 * forge a second opener. The material string from config is resolved once via {@link
 * #resolveMaterial}; an unresolvable material makes {@link #create} return {@code null}, and the
 * caller (the listener) treats a null item as "feature unavailable" and gives nothing.
 *
 * <p>Only {@link #resolveMaterial} is unit-tested (it needs no server). The {@code ItemStack}/PDC
 * paths are verified at runtime, like the renderers.
 */
public final class MenuItemService {

    private static final byte TAG_VALUE = (byte) 1;

    private final NamespacedKey key;

    public MenuItemService(Plugin plugin) {
        this.key = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "menu_item");
    }

    public NamespacedKey key() {
        return key;
    }

    /**
     * Resolves a configured material string to an {@link Material}; empty if unknown or air.
     *
     * <p>Deliberately checks the air variants by enum identity rather than calling {@link
     * Material#isAir()}: on this Paper API, {@code isAir()} resolves a block-type registry entry
     * lazily, which throws outside a running server. Comparing against {@code AIR}, {@code
     * CAVE_AIR}, and {@code VOID_AIR} keeps this method pure enum lookup, so it stays server-free
     * and unit-testable.
     */
    public static Optional<Material> resolveMaterial(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Material material = Material.matchMaterial(name.trim());
        if (material == null
                || material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR) {
            return Optional.empty();
        }
        return Optional.of(material);
    }

    /**
     * A 1-count stack of the spec's material, named per the spec, carrying the PDC tag. Returns
     * {@code null} if the material does not resolve — the feature is unavailable rather than
     * crashing.
     */
    public ItemStack create(MenuItemSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Optional<Material> material = resolveMaterial(spec.material());
        if (material.isEmpty()) {
            return null;
        }
        ItemStack item = new ItemStack(material.get(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        meta.displayName(MenuText.java(spec.name()));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, TAG_VALUE);
        item.setItemMeta(meta);
        return item;
    }

    /** True iff {@code item} carries the menu-item PDC tag. */
    public boolean isMenuItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(key, PersistentDataType.BYTE);
    }

    /**
     * True iff any slot of {@code player}'s inventory holds a tagged menu item.
     *
     * <p>Checks {@code getContents()} (main + hotbar) plus the off-hand slot explicitly, since
     * {@code getContents()} does not include it. Armor slots are not checked: this item type can
     * never be equipped there, so they are not an additional duplication vector.
     */
    public boolean hasMenuItem(Player player) {
        Objects.requireNonNull(player, "player");
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMenuItem(item)) {
                return true;
            }
        }
        return isMenuItem(player.getInventory().getItemInOffHand());
    }
}
