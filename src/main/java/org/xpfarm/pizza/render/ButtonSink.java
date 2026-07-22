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
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.Menu;

/**
 * Receives a resolved button press from any {@link MenuRenderer}, regardless of which platform
 * rendered it.
 *
 * <p>A renderer's only job is turning a click/response back into a {@code (player, menu, button)}
 * triple and calling {@link #activate}; permission re-checks, cooldowns, and running the button's
 * action all live on the implementation of this interface, not in the renderer. Like {@link
 * MenuRenderer}, this interface must never name a Geyser or Cumulus type.
 */
public interface ButtonSink {

    void activate(Player player, Menu menu, Button button);
}
