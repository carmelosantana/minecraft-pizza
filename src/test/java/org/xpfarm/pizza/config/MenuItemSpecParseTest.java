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

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

final class MenuItemSpecParseTest {

    private final List<String> warnings = new ArrayList<>();

    private PizzaConfig parse(Map<String, Object> menuItem) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("command-allowlist", List.of("starterpack"));
        if (menuItem != null) {
            root.put("menu-item", menuItem);
        }
        return ConfigParser.parse(root, warnings::add);
    }

    @Test
    void parsesAFullySpecifiedMenuItem() {
        MenuItemSpec spec = parse(Map.of(
                "enabled", true, "material", "pumpkin_pie", "name", "&6Pizza Menu", "slot", 8)).menuItem();
        assertTrue(spec.enabled());
        assertEquals("pumpkin_pie", spec.material());
        assertEquals("&6Pizza Menu", spec.name());
        assertEquals(8, spec.slot());
    }

    @Test
    void defaultsWhenSectionAbsent() {
        MenuItemSpec spec = parse(null).menuItem();
        assertNotNull(spec, "menuItem() must never be null");
        assertFalse(spec.enabled(), "absent section → feature off");
    }

    @Test
    void enabledDefaultsTrueWhenSectionPresentWithoutFlag() {
        MenuItemSpec spec = parse(Map.of("material", "pumpkin_pie")).menuItem();
        assertTrue(spec.enabled(), "present section defaults enabled=true");
    }

    @Test
    void blankMaterialDisablesAndWarns() {
        MenuItemSpec spec = parse(Map.of("material", "  ")).menuItem();
        assertFalse(spec.enabled());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("material")));
    }

    @Test
    void slotOutOfRangeDefaultsToEightAndWarns() {
        MenuItemSpec spec = parse(Map.of("material", "pumpkin_pie", "slot", 42)).menuItem();
        assertEquals(8, spec.slot());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("slot")));
    }

    @Test
    void blankNameFallsBackToDefault() {
        MenuItemSpec spec = parse(Map.of("material", "pumpkin_pie", "name", "")).menuItem();
        assertEquals("Pizza Menu", spec.name());
    }
}
