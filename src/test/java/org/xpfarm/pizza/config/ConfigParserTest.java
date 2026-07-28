/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
// src/test/java/org/xpfarm/pizza/config/ConfigParserTest.java
package org.xpfarm.pizza.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.*;

final class ConfigParserTest {

    private final List<String> warnings = new ArrayList<>();

    private PizzaConfig parse(Map<String, Object> raw) {
        return ConfigParser.parse(raw, warnings::add);
    }

    private static Map<String, Object> config(Object... menuEntries) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> menus = new LinkedHashMap<>();
        for (int i = 0; i < menuEntries.length; i += 2) {
            menus.put((String) menuEntries[i], menuEntries[i + 1]);
        }
        root.put("menus", menus);
        root.put("command-allowlist", List.of("starterpack", "worldcrud"));
        root.put("allowed-worlds", List.of("world", "creative"));
        root.put("invite-timeout", "60s");
        return root;
    }

    private static Map<String, Object> menu(Object... buttons) {
        return Map.of("title", "T", "content", "C", "buttons", List.of(buttons));
    }

    @Test
    void parsesACommandButton() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Kit", "command", "starterpack give %player%", "cooldown", "24h"))));

        Button button = cfg.menus().get("main").buttons().get(0);
        assertEquals("Kit", button.label());
        assertInstanceOf(Action.RunCommand.class, button.action());
        assertEquals(RunAs.CONSOLE, button.runAs(), "console is the default run-as");
        assertEquals(Duration.ofHours(24), button.cooldown());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void refusesACommandRootOutsideTheAllowlist() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Nope", "command", "op %player%"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty(), "button must be omitted");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("op")), "refusal must be logged");
    }

    @Test
    void refusesEveryCommandButtonWhenTheAllowlistIsMissing() {
        Map<String, Object> raw = config("main", menu(
                Map.of("label", "Kit", "command", "starterpack give %player%")));
        raw.remove("command-allowlist");

        PizzaConfig cfg = parse(raw);

        assertTrue(cfg.menus().get("main").buttons().isEmpty(),
                "an absent allowlist must fail closed, not open");
    }

    @Test
    void refusesElevatedButtonWithoutGrant() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Go", "command", "worldcrud teleport %world%",
                       "run-as", "player-elevated"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty());
    }

    @Test
    void refusesEachOnlineCombinedWithElevation() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "All", "command", "worldcrud teleport %world%",
                       "run-as", "player-elevated", "grant", List.of("worldcrud.teleport"),
                       "each-online", true))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty(),
                "fanning a temporary permission grant across the server is refused");
    }

    @Test
    void refusesUnrecognisedRunAs() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Typo", "command", "starterpack give %player%", "run-as", "playr"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty(),
                "an unrecognized run-as must not silently escalate to console");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("playr")), "refusal must be logged");
    }

    @Test
    void refusesButtonWithTwoActions() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Both", "command", "starterpack give %player%", "open", "other"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty());
    }

    @Test
    void refusesOpenPointingAtAMissingMenu() {
        PizzaConfig cfg = parse(config("main", menu(Map.of("label", "Go", "open", "ghost"))));

        assertTrue(cfg.menus().get("main").buttons().isEmpty());
    }

    @Test
    void assignsStableButtonIdsForCooldownKeying() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "A", "command", "starterpack give %player%"),
                Map.of("label", "B", "command", "starterpack equip %player%"))));

        List<Button> buttons = cfg.menus().get("main").buttons();
        assertEquals("main.0", buttons.get(0).id());
        assertEquals("main.1", buttons.get(1).id());
    }

    @Test
    void oneBadButtonDoesNotDiscardItsSiblings() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Bad", "command", "stop"),
                Map.of("label", "Good", "command", "starterpack give %player%"))));

        List<Button> buttons = cfg.menus().get("main").buttons();
        assertEquals(1, buttons.size());
        assertEquals("Good", buttons.get(0).label());
        assertEquals("main.1", buttons.get(0).id(),
                "rejecting button 0 must not renumber the survivor to main.0 — the id is the cooldown key");
    }

    // --- M1: parse-time allowlist root matching must agree with dispatch-time CommandAllowlist ---

    @Test
    void permitsACommandWithALeadingSlashAndDifferentCaseAgainstABareAllowlistEntry() {
        PizzaConfig cfg = parse(config("main", menu(
                Map.of("label", "Kit", "command", "/StarterPack give %player%"))));

        assertEquals(1, cfg.menus().get("main").buttons().size(),
                "a leading slash and different case on the button's own command must not be "
                        + "refused when the allowlist entry itself has neither (M1)");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void permitsACommandAgainstAnAllowlistEntryThatItselfHasALeadingSlash() {
        Map<String, Object> raw = config("main", menu(
                Map.of("label", "Kit", "command", "starterpack give %player%")));
        raw.put("command-allowlist", List.of("/StarterPack", "worldcrud"));

        PizzaConfig cfg = parse(raw);

        assertEquals(1, cfg.menus().get("main").buttons().size(),
                "a leading slash/different case on the config allowlist entry itself must not "
                        + "prevent it from matching a bare, lowercase command root (M1)");
        assertTrue(warnings.isEmpty());
    }

    // --- M2: invite-timeout defaults to 60s, not ~1 tick, when missing or unparseable ---

    @Test
    void inviteTimeoutDefaultsTo60SecondsWhenAbsent() {
        Map<String, Object> raw = config("main", menu());
        raw.remove("invite-timeout");

        PizzaConfig cfg = parse(raw);

        assertEquals(Duration.ofSeconds(60), cfg.inviteTimeout());
        assertTrue(warnings.isEmpty(), "a simply-absent invite-timeout is an ordinary default, not a config mistake");
    }

    @Test
    void inviteTimeoutDefaultsTo60SecondsAndWarnsWhenPresentButUnparseable() {
        Map<String, Object> raw = config("main", menu());
        raw.put("invite-timeout", "not-a-duration");

        PizzaConfig cfg = parse(raw);

        assertEquals(Duration.ofSeconds(60), cfg.inviteTimeout());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("invite-timeout")),
                "a present-but-unparseable invite-timeout must be logged, unlike a simply-absent one");
    }

    @Test
    void parsesTheOptionalMaterialImageField() {
        Map<String, Object> button = new java.util.LinkedHashMap<>();
        button.put("label", "Cool Items");
        button.put("image", Map.of("type", "path", "data", "textures/items/diamond", "material", "DIAMOND"));
        button.put("command", "starterpack give %player%");
        Map<String, Object> root = Map.of(
                "menus", Map.of("main", Map.of("buttons", List.of(button))),
                "command-allowlist", List.of("starterpack"));

        org.xpfarm.pizza.menu.ButtonImage img =
                org.xpfarm.pizza.config.ConfigParser.parse(root, w -> {})
                        .menus().get("main").buttons().get(0).image();

        assertEquals("path", img.type());
        assertEquals("textures/items/diamond", img.data());
        assertEquals("DIAMOND", img.material());
    }
}
