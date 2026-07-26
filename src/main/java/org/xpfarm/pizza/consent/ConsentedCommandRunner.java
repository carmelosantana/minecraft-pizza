/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.consent;

import org.bukkit.entity.Player;

/**
 * Runs a {@link ConsentAction.RunCommand}'s command for a consenting invitee. The implementation
 * (wired in {@code PizzaPlugin} from {@code ActionDispatcher}) substitutes {@code %target%} with the
 * invitee's name and dispatches as console through the fail-closed allowlist. Returns whether the
 * command actually ran.
 */
@FunctionalInterface
public interface ConsentedCommandRunner {
    boolean run(Player invitee, String command);
}
