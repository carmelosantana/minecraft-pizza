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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.xpfarm.pizza.dispatch.CommandAllowlist;
import org.xpfarm.pizza.menu.Action;
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.ButtonImage;
import org.xpfarm.pizza.menu.Menu;
import org.xpfarm.pizza.menu.RunAs;

/**
 * Turns a raw, untrusted {@code Map<String, Object>} (as loaded from {@code config.yml}) into a
 * validated, immutable {@link PizzaConfig}.
 *
 * <p>Deliberately free of any Bukkit/Paper dependency so the entire validation surface is
 * unit-testable without a running server — it takes a plain map, not a {@code
 * ConfigurationSection}.
 *
 * <p>Validation is fail-closed and per-button: a malformed button is logged through the {@code
 * warn} callback and omitted, never thrown, and never takes the rest of the config down with it.
 * The one exception is the command allowlist itself — a missing or empty {@code
 * command-allowlist} is a config-level failure that is logged loudly, on top of (not instead of)
 * the ordinary per-button refusal every command button then receives.
 */
public final class ConfigParser {

    /** See {@link #parseInviteTimeout(Object, Consumer)} for why this, not {@link Duration#ZERO}. */
    private static final Duration DEFAULT_INVITE_TIMEOUT = Duration.ofSeconds(60);

    private ConfigParser() {}

    public static PizzaConfig parse(Map<String, Object> raw, Consumer<String> warn) {
        Objects.requireNonNull(warn, "warn");
        Map<String, Object> root = raw == null ? Map.of() : raw;

        Set<String> commandAllowlist = readAllowlist(root, warn);

        // Normalized the same way CommandAllowlist.rootOf() (and now CommandAllowlist's own
        // constructor) extract a root — leading '/' stripped, lowercased — so a config author
        // writing "StarterPack" or "/starterpack" here and "starterpack ..." on a button (or vice
        // versa) is not silently refused at parse time only to be permitted at dispatch time, or
        // the reverse (M1). This is purely a widening of what parse-time recognizes as matching;
        // an unlisted root is still refused, so the allowlist stays fail-closed.
        Set<String> allowlistRoots =
                commandAllowlist.stream().map(CommandAllowlist::rootOf).collect(Collectors.toUnmodifiableSet());

        // Pass 1: every menu id must be known before any button is validated, so an `open`
        // target that is defined later in the file is not wrongly rejected as missing.
        Map<String, Object> rawMenus = asMap(root.get("menus"));
        Set<String> menuIds = rawMenus == null ? Set.of() : Set.copyOf(rawMenus.keySet());

        // Pass 2: parse each menu's buttons now that menuIds is complete.
        Map<String, Menu> menus = new LinkedHashMap<>();
        if (rawMenus != null) {
            for (Map.Entry<String, Object> entry : rawMenus.entrySet()) {
                String menuId = entry.getKey();
                menus.put(menuId, parseMenu(menuId, asMap(entry.getValue()), menuIds, allowlistRoots, warn));
            }
        }

        List<String> allowedWorlds = asStringList(root.get("allowed-worlds"));
        Duration inviteTimeout = parseInviteTimeout(root.get("invite-timeout"), warn);
        Map<String, String> messages = asStringMap(root.get("messages"));
        MenuItemSpec menuItem = parseMenuItem(root.get("menu-item"), warn);

        return new PizzaConfig(menus, allowedWorlds, commandAllowlist, inviteTimeout, messages, menuItem);
    }

    /**
     * A missing or empty allowlist keeps the fail-closed guarantee true even for an empty file:
     * every command button is refused, not permitted, when there is nothing to check against.
     */
    private static Set<String> readAllowlist(Map<String, Object> root, Consumer<String> warn) {
        List<String> list = asStringList(root.get("command-allowlist"));
        if (list.isEmpty()) {
            warn.accept("command-allowlist is missing or empty; every command button will be "
                    + "refused (fail closed)");
            return Set.of();
        }
        return Set.copyOf(list);
    }

    private static Menu parseMenu(
            String menuId,
            Map<String, Object> rawMenu,
            Set<String> menuIds,
            Set<String> allowlistRoots,
            Consumer<String> warn) {
        if (rawMenu == null) {
            warn.accept("menu '" + menuId + "' is not a map; treating it as empty");
            return new Menu(menuId, "", "", List.of());
        }

        String title = asString(rawMenu.get("title"), "");
        String content = asString(rawMenu.get("content"), "");
        List<Object> rawButtons = asList(rawMenu.get("buttons"));

        List<Button> buttons = new ArrayList<>();
        for (int index = 0; index < rawButtons.size(); index++) {
            // The id is assigned from the button's position BEFORE validation runs, so it stays
            // stable (and the cooldown key it backs stays stable) even when a sibling is rejected.
            String buttonId = menuId + "." + index;
            Map<String, Object> rawButton = asMap(rawButtons.get(index));
            if (rawButton == null) {
                warn.accept("button " + buttonId + " is not a map; refusing");
                continue;
            }
            parseButton(buttonId, rawButton, menuIds, allowlistRoots, warn).ifPresent(buttons::add);
        }

        return new Menu(menuId, title, content, buttons);
    }

    private static Optional<Button> parseButton(
            String id,
            Map<String, Object> raw,
            Set<String> menuIds,
            Set<String> allowlistRoots,
            Consumer<String> warn) {
        String label = asString(raw.get("label"), "");

        boolean hasOpen = raw.get("open") != null;
        boolean hasCommand = raw.get("command") != null && raw.get("pick") == null;
        boolean hasInvite = truthy(raw.get("invite"));
        boolean hasPick = raw.get("pick") != null;

        int actionCount = (hasOpen ? 1 : 0) + (hasCommand ? 1 : 0) + (hasInvite ? 1 : 0) + (hasPick ? 1 : 0);
        if (actionCount != 1) {
            warn.accept("button " + id + " must declare exactly one of open/command/invite/pick, found "
                    + actionCount + "; refusing");
            return Optional.empty();
        }

        Action action;
        if (hasOpen) {
            String target = asString(raw.get("open"), "");
            if (!menuIds.contains(target)) {
                warn.accept("button " + id + " opens unknown menu '" + target + "'; refusing");
                return Optional.empty();
            }
            action = new Action.OpenMenu(target);
        } else if (hasCommand) {
            String command = asString(raw.get("command"), "").trim();
            // Same extraction dispatch time uses (CommandAllowlist.rootOf: strip a leading '/',
            // lowercase), so a config author writing "/starterpack ..." or "StarterPack" in the
            // allowlist is not refused here only to be permitted later at dispatch, or vice versa
            // (M1) — see the class-level javadoc.
            String root = CommandAllowlist.rootOf(command);
            if (root.isEmpty() || !allowlistRoots.contains(root)) {
                warn.accept("button " + id + " command root '" + root
                        + "' is not in the command-allowlist; refusing");
                return Optional.empty();
            }
            action = new Action.RunCommand(command);
        } else if (hasPick) {
            String pickType = asString(raw.get("pick"), "");
            if (!"online-players".equals(pickType)) {
                warn.accept("button " + id + " has unsupported pick '" + pickType
                        + "' (only 'online-players'); refusing");
                return Optional.empty();
            }
            String command = asString(raw.get("command"), "").trim();
            String root = CommandAllowlist.rootOf(command);
            if (root.isEmpty() || !allowlistRoots.contains(root)) {
                warn.accept("button " + id + " pick command root '" + root
                        + "' is not in the command-allowlist; refusing");
                return Optional.empty();
            }
            boolean consent = truthy(raw.get("consent"));
            String consentPrompt = asString(raw.get("consent-prompt"), "%player% wants to do that to you.");
            action = new Action.Pick(command, consent, consentPrompt);
        } else {
            action = new Action.Invite();
        }

        Optional<RunAs> parsedRunAs = parseRunAs(raw.get("run-as"), id, warn);
        if (parsedRunAs.isEmpty()) {
            return Optional.empty();
        }
        RunAs runAs = parsedRunAs.get();
        List<String> grant = asStringList(raw.get("grant"));

        if (runAs == RunAs.PLAYER_ELEVATED && grant.isEmpty()) {
            warn.accept("button " + id + " is run-as player-elevated but has no grant; refusing");
            return Optional.empty();
        }

        boolean eachOnline = truthy(raw.get("each-online"));
        if (eachOnline && runAs == RunAs.PLAYER_ELEVATED) {
            warn.accept("button " + id + " combines each-online with player-elevated; refusing");
            return Optional.empty();
        }

        boolean worlds = truthy(raw.get("worlds"));
        boolean isCommand = action instanceof Action.RunCommand;
        if ((worlds || eachOnline) && !isCommand) {
            warn.accept("button " + id + " sets worlds/each-online on a non-command button; refusing");
            return Optional.empty();
        }

        Duration cooldown;
        Object rawCooldown = raw.get("cooldown");
        if (rawCooldown == null) {
            cooldown = Duration.ZERO;
        } else {
            try {
                cooldown = DurationParser.parse(String.valueOf(rawCooldown));
            } catch (IllegalArgumentException e) {
                warn.accept("button " + id + " has an unparseable cooldown '" + rawCooldown
                        + "'; refusing");
                return Optional.empty();
            }
        }

        ButtonImage image = parseImage(raw.get("image"));
        String permission = raw.get("permission") == null ? null : asString(raw.get("permission"), null);

        return Optional.of(
                new Button(id, label, image, permission, action, runAs, grant, cooldown, worlds, eachOnline));
    }

    /**
     * An absent {@code run-as} defaults to {@link RunAs#CONSOLE}, but an unrecognized value is
     * refused rather than defaulted — {@code CONSOLE} is the most privileged mode (it bypasses
     * every permission check), so silently mapping a typo like {@code "playr"} to it would
     * silently escalate a button the author clearly intended to run as the least privileged mode.
     * That is exactly the inversion the fail-closed constraint exists to prevent, so it is treated
     * as an eighth rejection alongside the other seven validation rules.
     */
    private static Optional<RunAs> parseRunAs(Object raw, String id, Consumer<String> warn) {
        if (raw == null) {
            return Optional.of(RunAs.CONSOLE);
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "console" -> Optional.of(RunAs.CONSOLE);
            case "player" -> Optional.of(RunAs.PLAYER);
            case "player-elevated" -> Optional.of(RunAs.PLAYER_ELEVATED);
            default -> {
                warn.accept("button " + id + " has unrecognized run-as '" + raw + "'; refusing");
                yield Optional.empty();
            }
        };
    }

    /**
     * A missing or unparseable {@code invite-timeout} defaults to {@link #DEFAULT_INVITE_TIMEOUT}
     * (60s), not {@link Duration#ZERO} (M2). {@code ConsentService.invite} computes {@code
     * delayTicks = max(1, timeout.toMillis() / 50)}, so a zero timeout expired an invite about one
     * tick (~50ms) after it was sent — fails safe (nobody gets moved), but confusingly so, since
     * the invite is answerable for less time than it takes to read the prompt. Only the
     * present-but-unparseable case warns; a simply absent key is an ordinary default, not a config
     * mistake.
     */
    private static Duration parseInviteTimeout(Object raw, Consumer<String> warn) {
        if (raw == null) {
            return DEFAULT_INVITE_TIMEOUT;
        }
        try {
            return DurationParser.parse(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            warn.accept("invite-timeout '" + raw + "' is unparseable; defaulting to "
                    + DEFAULT_INVITE_TIMEOUT.toSeconds() + "s");
            return DEFAULT_INVITE_TIMEOUT;
        }
    }

    private static final int DEFAULT_MENU_ITEM_SLOT = 8;
    private static final String DEFAULT_MENU_ITEM_NAME = "Pizza Menu";

    /**
     * An absent {@code menu-item} section leaves the feature off ({@link MenuItemSpec#disabled()}).
     * A present section defaults {@code enabled} to true. {@code material} validity is not checked
     * here — only that it is non-blank; a blank material is unusable, so the feature is disabled and
     * a warning logged (fail closed, like the rest of the parser). Slot must be 0–8 (the hotbar);
     * anything else warns and defaults to {@value #DEFAULT_MENU_ITEM_SLOT}.
     */
    private static MenuItemSpec parseMenuItem(Object raw, Consumer<String> warn) {
        Map<String, Object> map = asMap(raw);
        if (map == null) {
            return MenuItemSpec.disabled();
        }

        boolean enabled = map.get("enabled") == null || truthy(map.get("enabled"));

        String material = asString(map.get("material"), "").trim();
        if (enabled && material.isBlank()) {
            warn.accept("menu-item is enabled but has a blank material; disabling the menu item");
            enabled = false;
        }

        String name = asString(map.get("name"), "").trim();
        if (name.isBlank()) {
            name = DEFAULT_MENU_ITEM_NAME;
        }

        int slot = parseMenuItemSlot(map.get("slot"), warn);

        return new MenuItemSpec(enabled, material, name, slot);
    }

    private static int parseMenuItemSlot(Object raw, Consumer<String> warn) {
        if (raw == null) {
            return DEFAULT_MENU_ITEM_SLOT;
        }
        int slot;
        try {
            slot = Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            warn.accept("menu-item slot '" + raw + "' is not a number; defaulting to "
                    + DEFAULT_MENU_ITEM_SLOT);
            return DEFAULT_MENU_ITEM_SLOT;
        }
        if (slot < 0 || slot > 8) {
            warn.accept("menu-item slot " + slot + " is outside the hotbar (0-8); defaulting to "
                    + DEFAULT_MENU_ITEM_SLOT);
            return DEFAULT_MENU_ITEM_SLOT;
        }
        return slot;
    }

    private static ButtonImage parseImage(Object raw) {
        Map<String, Object> map = asMap(raw);
        if (map == null) {
            return null;
        }
        String type = asString(map.get("type"), null);
        String data = asString(map.get("data"), null);
        if (type == null && data == null) {
            return null;
        }
        return new ButtonImage(type, data);
    }

    private static boolean truthy(Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private static Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static List<Object> asList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return new ArrayList<>(list);
    }

    private static String asString(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static List<String> asStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Map<String, String> asStringMap(Object raw) {
        Map<String, Object> map = asMap(raw);
        if (map == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }
}
