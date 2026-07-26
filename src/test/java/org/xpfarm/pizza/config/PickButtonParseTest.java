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

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.Action;
import org.xpfarm.pizza.menu.Button;

final class PickButtonParseTest {

    private final List<String> warnings = new ArrayList<>();

    private PizzaConfig parse(Map<String, Object> button) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> menus = new LinkedHashMap<>();
        menus.put("main", Map.of("title", "T", "content", "C", "buttons", List.of(button)));
        root.put("menus", menus);
        root.put("command-allowlist", List.of("curse"));
        return ConfigParser.parse(root, warnings::add);
    }

    @Test
    void parsesAConsentPickButton() {
        PizzaConfig cfg = parse(Map.of(
                "label", "Curse a friend",
                "pick", "online-players",
                "consent", true,
                "consent-prompt", "%player% wants to curse you!",
                "command", "curse trigger ZP25 %target%",
                "cooldown", "1h"));
        Button b = cfg.menus().get("main").buttons().get(0);
        Action.Pick pick = assertInstanceOf(Action.Pick.class, b.action());
        assertEquals("curse trigger ZP25 %target%", pick.command());
        assertTrue(pick.consent());
        assertEquals("%player% wants to curse you!", pick.consentPrompt());
        assertEquals(Duration.ofHours(1), b.cooldown());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void consentDefaultsFalse() {
        PizzaConfig cfg = parse(Map.of(
                "label", "Curse a player", "pick", "online-players",
                "command", "curse trigger ZP25 %target%"));
        Action.Pick pick = (Action.Pick) cfg.menus().get("main").buttons().get(0).action();
        assertFalse(pick.consent());
    }

    @Test
    void refusesAPickWhoseCommandRootIsNotAllowlisted() {
        PizzaConfig cfg = parse(Map.of(
                "label", "Nope", "pick", "online-players", "command", "op %target%"));
        assertTrue(cfg.menus().get("main").buttons().isEmpty(), "unlisted root must be refused");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("op")));
    }

    @Test
    void refusesAPickWithNoCommand() {
        PizzaConfig cfg = parse(Map.of("label", "Nope", "pick", "online-players"));
        assertTrue(cfg.menus().get("main").buttons().isEmpty());
    }
}
