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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.xpfarm.pizza.consent.ConsentService;

/**
 * Handles {@code /pizza}, {@code /pizza reload}, and the Java fallback for consent responses,
 * {@code /pizza accept} and {@code /pizza decline}.
 *
 * <p>Bare {@code /pizza} opens the {@code main} menu for the calling player and requires {@code
 * pizza.use}; {@code /pizza reload} re-parses {@code config.yml} and swaps the live model in
 * {@link MenuService} without a server restart, and requires {@code pizza.reload}.
 *
 * <p>{@code /pizza accept} and {@code /pizza decline} resolve the caller's own pending travel
 * invite via {@link ConsentService#accept(java.util.UUID)}/{@link
 * ConsentService#decline(java.util.UUID)} — the path a Java player (who cannot receive a Cumulus
 * form) uses instead of tapping a form button. Both require {@code pizza.invite} and reply with a
 * friendly message, never an error or a stack trace, when the caller has no pending invite.
 */
final class PizzaCommand implements CommandExecutor, TabCompleter {

    private final PizzaPlugin plugin;
    private final MenuService menuService;
    private final ConsentService consentService;

    PizzaCommand(PizzaPlugin plugin, MenuService menuService, ConsentService consentService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.consentService = Objects.requireNonNull(consentService, "consentService");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload":
                    return handleReload(sender);
                case "accept":
                    return handleConsentResponse(sender, true);
                case "decline":
                    return handleConsentResponse(sender, false);
                case "config":
                    return handleConfig(sender, args);
                default:
                    break;
            }
        }
        return handleOpen(sender);
    }

    private boolean handleOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can open the Pizza menu.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("pizza.use")) {
            player.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        menuService.open(player, "main");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("pizza.reload")) {
            sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        plugin.reloadPizzaConfig();
        sender.sendMessage(Component.text("Pizza configuration reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleConfig(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pizza.reload")) {
            sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("refresh")) {
            sender.sendMessage(Component.text("Usage: /pizza config refresh", NamedTextColor.GRAY));
            return true;
        }
        try {
            java.nio.file.Path backup = plugin.refreshConfigToDefault();
            sender.sendMessage(Component.text(
                    "Config reset to the default. Your old file is at " + backup.getFileName() + ".",
                    NamedTextColor.GREEN));
        } catch (java.io.IOException e) {
            sender.sendMessage(Component.text(
                    "Could not refresh the config: " + e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    /**
     * The Java-native fallback for a consent form's Accept/Decline buttons. {@link
     * ConsentService#accept}/{@link ConsentService#decline} report whether a pending invite
     * actually existed, so a stray {@code /pizza accept} with nothing pending — including one that
     * arrives after the invite already resolved some other way — gets a friendly reply instead of
     * silence or an error.
     */
    private boolean handleConsentResponse(CommandSender sender, boolean accept) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can respond to a Pizza invite.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("pizza.invite")) {
            player.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        boolean resolved = accept
                ? consentService.accept(player.getUniqueId())
                : consentService.decline(player.getUniqueId());
        if (!resolved) {
            player.sendMessage(Component.text("You have no pending invite.", NamedTextColor.GRAY));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("pizza.reload") && "reload".startsWith(partial)) {
                options.add("reload");
            }
            if (sender.hasPermission("pizza.reload") && "config".startsWith(partial)) {
                options.add("config");
            }
            if (sender.hasPermission("pizza.invite")) {
                if ("accept".startsWith(partial)) {
                    options.add("accept");
                }
                if ("decline".startsWith(partial)) {
                    options.add("decline");
                }
            }
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("config")
                && sender.hasPermission("pizza.reload")
                && "refresh".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            return List.of("refresh");
        }
        return List.of();
    }
}
