/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.config;

/**
 * Validated, Bukkit-free configuration for the physical hotbar menu item. {@code material} is the
 * raw configured string, deliberately NOT resolved to a {@code org.bukkit.Material} here — that
 * resolution needs Bukkit and happens in {@code MenuItemService}, which disables itself if the
 * string does not resolve to a real item. This record only carries the rules that need no server:
 * the enabled flag, the hotbar slot (0–8), and a non-blank display name.
 */
public record MenuItemSpec(boolean enabled, String material, String name, int slot) {

    /** The spec used when {@code menu-item} is absent or unusable: the feature is simply off. */
    public static MenuItemSpec disabled() {
        return new MenuItemSpec(false, "", "Pizza Menu", 8);
    }
}
