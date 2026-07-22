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

import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.xpfarm.pizza.menu.Action;
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.RunAs;

/**
 * Resolves and runs a {@link Button}'s {@link Action.RunCommand}, safely.
 *
 * <p>Two guarantees carry this class:
 *
 * <ul>
 *   <li>The command allowlist is re-checked on the <em>resolved</em> command string, after
 *       placeholder substitution — never on the unresolved template alone. A placeholder value
 *       that is not fully under the server operator's control (a player's display name, for
 *       instance) must not be able to smuggle a second command past a check that only ever
 *       inspected the template.
 *   <li>{@link RunAs#PLAYER_ELEVATED} grants its permission nodes through a temporary {@link
 *       PermissionAttachment} that is always removed in a {@code finally} block. A command that
 *       throws must not leave the player holding an elevated permission for the rest of the
 *       session.
 * </ul>
 *
 * <p>Only buttons whose action is {@link Action.RunCommand} are dispatchable here; {@code
 * open}/{@code invite} buttons are handled by the menu layer itself and are never passed to this
 * class.
 */
public final class ActionDispatcher {

    private final Plugin plugin;
    private final CommandAllowlist allowlist;

    public ActionDispatcher(Plugin plugin, CommandAllowlist allowlist) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
    }

    public void dispatch(Player actor, Button button, Map<String, String> placeholders) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(button, "button");
        Objects.requireNonNull(placeholders, "placeholders");

        if (!(button.action() instanceof Action.RunCommand runCommand)) {
            throw new IllegalArgumentException(
                    "button " + button.id() + " has no runnable command (action is "
                            + button.action().getClass().getSimpleName() + ")");
        }

        String resolved = Placeholders.apply(runCommand.command(), placeholders);
        if (!allowlist.permits(resolved)) {
            plugin.getLogger()
                    .warning("refusing to run '" + resolved + "' for " + actor.getName()
                            + " (button " + button.id()
                            + "): not in command-allowlist after placeholder substitution");
            return;
        }

        switch (button.runAs()) {
            case CONSOLE -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            case PLAYER -> Bukkit.dispatchCommand(actor, resolved);
            case PLAYER_ELEVATED -> dispatchElevated(actor, button, resolved);
        }
    }

    private void dispatchElevated(Player actor, Button button, String resolved) {
        PermissionAttachment attachment = actor.addAttachment(plugin);
        try {
            button.grant().forEach(node -> attachment.setPermission(node, true));
            actor.recalculatePermissions();
            Bukkit.dispatchCommand(actor, resolved);
        } finally {
            actor.removeAttachment(attachment);
            actor.recalculatePermissions();
        }
    }
}
