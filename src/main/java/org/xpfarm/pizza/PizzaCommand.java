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

/**
 * Handles {@code /pizza} and {@code /pizza reload}.
 *
 * <p>Bare {@code /pizza} opens the {@code main} menu for the calling player and requires {@code
 * pizza.use}; {@code /pizza reload} re-parses {@code config.yml} and swaps the live model in
 * {@link MenuService} without a server restart, and requires {@code pizza.reload}.
 *
 * <p>The invite accept/decline subcommands and the invite button's target picker are Task 7's
 * job, not this class's — an {@code invite: true} button press is handled by {@link MenuService}
 * as a visible no-op until then.
 */
final class PizzaCommand implements CommandExecutor, TabCompleter {

    private final PizzaPlugin plugin;
    private final MenuService menuService;

    PizzaCommand(PizzaPlugin plugin, MenuService menuService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.menuService = Objects.requireNonNull(menuService, "menuService");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            return handleReload(sender);
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("pizza.reload")) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return "reload".startsWith(partial) ? List.of("reload") : List.of();
        }
        return List.of();
    }
}
