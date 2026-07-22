/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.dispatch;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * The single seam through which {@link ActionDispatcher} actually runs a resolved command line.
 *
 * <p>Production wiring uses {@link #BUKKIT}, which delegates to {@link
 * Bukkit#dispatchCommand(CommandSender, String)}. Package-private so tests can substitute a stub
 * instead of standing up a real {@code Server} singleton — {@code Bukkit.setServer(...)} can only
 * be called once per JVM and pulls in server-implementation state that a plain {@code paper-api}
 * dependency does not provide, so exercising the {@code player-elevated} cleanup contract needs
 * this seam rather than the real static dispatch path.
 */
@FunctionalInterface
interface CommandRunner {

    CommandRunner BUKKIT = Bukkit::dispatchCommand;

    boolean run(CommandSender sender, String command);
}
