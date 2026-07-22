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

import org.bukkit.entity.Player;
import org.xpfarm.pizza.menu.Menu;

/**
 * Renders a {@link Menu} to a player, however that platform expresses "a menu" — a chest
 * inventory for a Java player, a Cumulus form for a Bedrock one.
 *
 * <p>Deliberately expressed in terms of Bukkit and {@code org.xpfarm.pizza} types only. This is
 * the seam a Bedrock renderer implements alongside the Java chest renderer, so neither this
 * interface nor {@link ButtonSink} may ever name a Geyser or Cumulus type — doing so would make
 * the plugin fail to load on a server without Floodgate present.
 */
public interface MenuRenderer {

    void open(Player player, Menu menu);
}
