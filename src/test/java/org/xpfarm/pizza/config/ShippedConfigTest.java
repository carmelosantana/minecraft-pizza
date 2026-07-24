/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
// src/test/java/org/xpfarm/pizza/config/ShippedConfigTest.java
package org.xpfarm.pizza.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.Action;
import org.xpfarm.pizza.menu.Button;
import org.yaml.snakeyaml.Yaml;

final class ShippedConfigTest {

    private final List<String> warnings = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private PizzaConfig parseShipped() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml must be on the classpath");
            Map<String, Object> raw = (Map<String, Object>) new Yaml().load(in);
            return ConfigParser.parse(raw, warnings::add);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void newCatalogButtonsAreAcceptedAndDispatchTheirCommands() {
        PizzaConfig cfg = parseShipped();
        List<String> commands = new ArrayList<>();
        for (Button b : cfg.menus().get("catalog").buttons()) {
            if (b.action() instanceof Action.RunCommand rc) {
                commands.add(rc.command());
            }
        }
        assertTrue(commands.contains("aguadeflorida give %player%"));
        assertTrue(commands.contains("electricfurnace give %player%"));
        assertTrue(commands.contains("llama give %player%"));
    }

    @Test
    void newRootsAreInTheAllowlist() {
        Set<String> allow = parseShipped().commandAllowlist();
        assertTrue(allow.containsAll(List.of("aguadeflorida", "electricfurnace", "llama")));
    }

    @Test
    void shippedMenuItemIsEnabled() {
        MenuItemSpec spec = parseShipped().menuItem();
        assertTrue(spec.enabled());
        assertEquals("pumpkin_pie", spec.material());
        assertEquals(8, spec.slot());
    }

    @Test
    void shippedConfigParsesWithoutWarnings() {
        parseShipped();
        assertTrue(warnings.isEmpty(), "shipped config must parse clean: " + warnings);
    }
}
