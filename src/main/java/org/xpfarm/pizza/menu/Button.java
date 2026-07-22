/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.menu;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One button in a {@link Menu}.
 *
 * @param id stable, derived as {@code "<menuId>.<index>"} — this is the cooldown key, so it must
 *     stay stable even when a sibling button in the same menu is rejected by validation.
 * @param image nullable
 * @param permission nullable
 * @param grant empty unless {@link RunAs#PLAYER_ELEVATED}
 * @param cooldown {@link Duration#ZERO} when absent from config
 */
public record Button(
        String id,
        String label,
        ButtonImage image,
        String permission,
        Action action,
        RunAs runAs,
        List<String> grant,
        Duration cooldown,
        boolean worlds,
        boolean eachOnline) {

    public Button {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(runAs, "runAs");
        grant = grant == null ? List.of() : List.copyOf(grant);
        cooldown = cooldown == null ? Duration.ZERO : cooldown;
    }
}
