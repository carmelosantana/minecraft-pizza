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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xpfarm.pizza.menu.Menu;

/**
 * The fully validated, immutable result of parsing a raw config map. Everything reachable from
 * here has already passed the fail-closed validation rules in {@link ConfigParser} — there is no
 * further checking to do downstream.
 */
public record PizzaConfig(
        Map<String, Menu> menus,
        List<String> allowedWorlds,
        Set<String> commandAllowlist,
        Duration inviteTimeout,
        Map<String, String> messages) {

    public PizzaConfig {
        menus = menus == null ? Map.of() : Map.copyOf(menus);
        allowedWorlds = allowedWorlds == null ? List.of() : List.copyOf(allowedWorlds);
        commandAllowlist = commandAllowlist == null ? Set.of() : Set.copyOf(commandAllowlist);
        inviteTimeout = inviteTimeout == null ? Duration.ZERO : inviteTimeout;
        messages = messages == null ? Map.of() : Map.copyOf(messages);
    }
}
