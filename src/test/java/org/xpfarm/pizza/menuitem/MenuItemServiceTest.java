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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

final class MenuItemServiceTest {

    @Test
    void resolvesAKnownItemMaterial() {
        assertEquals(Optional.of(Material.PUMPKIN_PIE), MenuItemService.resolveMaterial("pumpkin_pie"));
    }

    @Test
    void resolveIsCaseAndFormatInsensitiveLikeMatchMaterial() {
        assertEquals(Optional.of(Material.PUMPKIN_PIE), MenuItemService.resolveMaterial("PUMPKIN_PIE"));
    }

    @Test
    void unknownMaterialResolvesEmpty() {
        assertEquals(Optional.empty(), MenuItemService.resolveMaterial("not_a_real_material"));
    }

    @Test
    void blankMaterialResolvesEmpty() {
        assertEquals(Optional.empty(), MenuItemService.resolveMaterial("  "));
        assertEquals(Optional.empty(), MenuItemService.resolveMaterial(null));
    }
}
