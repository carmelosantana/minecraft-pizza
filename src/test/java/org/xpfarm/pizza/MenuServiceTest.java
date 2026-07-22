/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.*;

/**
 * Covers the ordering guarantees that do not need a running server. Anything requiring a live
 * Player is exercised at gate 7a over RCON instead.
 */
final class MenuServiceTest {

    private static Button button(String id, Action action, String permission, Duration cooldown) {
        return new Button(id, "L", null, permission, action, RunAs.CONSOLE,
                List.of(), cooldown, false, false);
    }

    @Test
    void hidesButtonsWhosePermissionThePlayerLacks() {
        Menu menu = new Menu("main", "T", "C", List.of(
                button("main.0", new Action.RunCommand("starterpack give %player%"), null, Duration.ZERO),
                button("main.1", new Action.RunCommand("starterpack give %player%"), "pizza.staff", Duration.ZERO)));

        List<Button> visible = MenuService.visibleTo(menu, permission -> false);

        assertEquals(1, visible.size(), "a button the player cannot use must be omitted, not disabled");
        assertEquals("main.0", visible.get(0).id());
    }

    @Test
    void showsEveryButtonToAPlayerWithAllPermissions() {
        Menu menu = new Menu("main", "T", "C", List.of(
                button("main.0", new Action.RunCommand("starterpack give %player%"), null, Duration.ZERO),
                button("main.1", new Action.RunCommand("starterpack give %player%"), "pizza.staff", Duration.ZERO)));

        assertEquals(2, MenuService.visibleTo(menu, permission -> true).size());
    }

    @Test
    void visibilityFilteringIsStableSoFormIndicesStayAligned() {
        // Cumulus 1.1.2 has no per-button callbacks; responses come back as an index into the
        // list the form was built from. Filtering must preserve order, or a child taps one
        // button and triggers another.
        Menu menu = new Menu("main", "T", "C", List.of(
                button("main.0", new Action.RunCommand("starterpack give %player%"), "a", Duration.ZERO),
                button("main.1", new Action.RunCommand("starterpack give %player%"), null, Duration.ZERO),
                button("main.2", new Action.RunCommand("starterpack give %player%"), "a", Duration.ZERO)));

        List<Button> visible = MenuService.visibleTo(menu, "a"::equals);

        assertEquals(List.of("main.0", "main.1", "main.2"),
                visible.stream().map(Button::id).toList());
    }
}
