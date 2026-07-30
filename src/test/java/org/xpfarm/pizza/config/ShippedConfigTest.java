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
        assertTrue(commands.contains("timberblast give %player%"));
        assertTrue(commands.contains("gfbread give sweet %player% 3"));
        assertTrue(commands.contains("copperkingdom give copper_sword %player%"));
    }

    @Test
    void newRootsAreInTheAllowlist() {
        Set<String> allow = parseShipped().commandAllowlist();
        assertTrue(allow.containsAll(List.of("aguadeflorida", "electricfurnace", "llama")));
        assertTrue(allow.containsAll(List.of("timberblast", "gfbread")));
        assertTrue(allow.containsAll(List.of("copperkingdom", "market", "daily")));
    }

    @Test
    void shopButtonOpensTheMarket() {
        PizzaConfig cfg = parseShipped();
        boolean shop = cfg.menus().values().stream()
                .flatMap(m -> m.buttons().stream())
                .anyMatch(b -> b.action() instanceof Action.RunCommand rc && rc.command().equals("market"));
        assertTrue(shop, "expected a button dispatching the market command");
    }

    @Test
    void dailyQuestsButtonOpensTheDailyHub() {
        Button daily = parseShipped().menus().get("main").buttons().stream()
                .filter(b -> b.label().equals("Daily Quests"))
                .findFirst().orElseThrow(() -> new AssertionError("expected a Daily Quests button on the main menu"));
        assertInstanceOf(Action.RunCommand.class, daily.action(), "Daily Quests must dispatch a command");
        assertEquals("daily", ((Action.RunCommand) daily.action()).command(),
                "Daily Quests must dispatch the daily command");
        assertEquals(org.xpfarm.pizza.menu.RunAs.PLAYER, daily.runAs(),
                "Daily Quests must dispatch as the player so DailyQ opens the player's own hub");
    }

    @Test
    void coolItemsButtonCarriesAJavaMaterialIcon() {
        PizzaConfig cfg = parseShipped();
        Button coolItems = cfg.menus().get("main").buttons().stream()
                .filter(b -> b.label().equals("Cool Items"))
                .findFirst().orElseThrow();
        assertNotNull(coolItems.image(), "Cool Items must carry an image");
        assertEquals("DIAMOND", coolItems.image().material(),
                "Cool Items must carry a Java chest material icon");
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

    @Test
    void curseButtonsAreParsedAndAllowlisted() {
        PizzaConfig cfg = parseShipped();
        assertTrue(cfg.commandAllowlist().contains("curse"), "curse must be allowlisted");

        boolean peerConsent = cfg.menus().values().stream()
                .flatMap(m -> m.buttons().stream())
                .anyMatch(b -> b.action() instanceof org.xpfarm.pizza.menu.Action.Pick p
                        && p.consent() && p.command().startsWith("curse trigger"));
        assertTrue(peerConsent, "expected a consent-gated peer curse pick button");

        boolean staffCleanse = cfg.menus().values().stream()
                .flatMap(m -> m.buttons().stream())
                .anyMatch(b -> b.action() instanceof org.xpfarm.pizza.menu.Action.Pick p
                        && !p.consent() && p.command().equals("curse stop %target%"));
        assertTrue(staffCleanse, "expected a staff cleanse pick button");
    }
}
