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
 * <p>Three guarantees carry this class:
 *
 * <ul>
 *   <li>Every placeholder <em>value</em> supplied by the caller is checked with {@link
 *       #isPermittedPlaceholderValue(String)} before substitution runs, and the whole dispatch is
 *       refused if any value fails. The check refuses whitespace and control characters and
 *       permits everything else — it does <em>not</em> restrict values to ASCII or to an
 *       identifier-like shape. This plugin is Bedrock-first: a real gamertag can be Cyrillic,
 *       Japanese, or contain a comma, and none of that is dangerous to a command line that only
 *       tokenises on a literal space. A Bedrock gamertag can also legitimately contain a space
 *       (when the operator disables Floodgate's default space-replacement), and that is exactly
 *       what this check exists to catch: such a name fails the check and the whole dispatch is
 *       refused — never substituted, never silently mangled, never crashes.
 *   <li>The command allowlist is re-checked with {@link CommandAllowlist#permits(String)} on the
 *       <em>resolved</em> command string, after placeholder substitution, as a second line of
 *       defence on top of the value check above — never relied on alone, and never skipped.
 *   <li>{@link RunAs#PLAYER_ELEVATED} grants its permission nodes through a temporary {@link
 *       PermissionAttachment} that is always removed in a {@code finally} block. A command that
 *       throws must not leave the player holding an elevated permission for the rest of the
 *       session.
 * </ul>
 *
 * <p>Only buttons whose action is {@link Action.RunCommand} are dispatchable here. A button with a
 * different action is logged and skipped rather than thrown, matching how every other refusal in
 * this class behaves — this method is reachable from a menu-click handler, where an unchecked
 * exception would surface as a console stack trace in the middle of handling a click.
 *
 * <p>{@link #dispatch} returns {@code true} only when the command actually reached {@link
 * CommandRunner#run}, and the value that call itself returned. Every refusal above — a
 * non-{@code RunCommand} action, a disallowed placeholder value, an allowlist miss — returns
 * {@code false} without running anything. This is the signal {@code MenuService} uses to decide
 * whether a button's cooldown should start: a refused dispatch must never start one.
 */
public final class ActionDispatcher {

    /**
     * Non-breaking space variants that {@link Character#isWhitespace(int)} deliberately does
     * <em>not</em> treat as whitespace (per its own javadoc): U+00A0 (NO-BREAK SPACE), U+2007
     * (FIGURE SPACE), U+202F (NARROW NO-BREAK SPACE). Confirmed against the running JDK rather
     * than assumed — U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) already return
     * {@code true} from {@code isWhitespace} and do not need listing here, despite sometimes
     * being cited as an exception.
     */
    private static final int[] EXTRA_BLANK_CODE_POINTS = {0x00A0, 0x2007, 0x202F};

    private final Plugin plugin;
    private final CommandAllowlist allowlist;
    private final CommandRunner runner;

    public ActionDispatcher(Plugin plugin, CommandAllowlist allowlist) {
        this(plugin, allowlist, CommandRunner.BUKKIT);
    }

    /** Test seam: lets a stub stand in for {@link CommandRunner#BUKKIT}. */
    ActionDispatcher(Plugin plugin, CommandAllowlist allowlist, CommandRunner runner) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public boolean dispatch(Player actor, Button button, Map<String, String> placeholders) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(button, "button");
        Objects.requireNonNull(placeholders, "placeholders");

        if (!(button.action() instanceof Action.RunCommand runCommand)) {
            plugin.getLogger()
                    .warning("button " + button.id() + " has no runnable command (action is "
                            + button.action().getClass().getSimpleName() + "); refusing to dispatch");
            return false;
        }

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue();
            if (!isPermittedPlaceholderValue(value)) {
                plugin.getLogger()
                        .warning("refusing to run button " + button.id() + " for " + actor.getName()
                                + ": placeholder '" + entry.getKey() + "' has a value containing "
                                + "whitespace or a control character, which is not permitted; refusing "
                                + "the whole dispatch rather than substituting it");
                return false;
            }
        }

        String resolved = Placeholders.apply(runCommand.command(), placeholders);
        if (!allowlist.permits(resolved)) {
            plugin.getLogger()
                    .warning("refusing to run '" + resolved + "' for " + actor.getName()
                            + " (button " + button.id()
                            + "): not in command-allowlist after placeholder substitution");
            return false;
        }

        return switch (button.runAs()) {
            case CONSOLE -> runner.run(Bukkit.getConsoleSender(), resolved);
            case PLAYER -> runner.run(actor, resolved);
            case PLAYER_ELEVATED -> dispatchElevated(actor, button, resolved);
        };
    }

    /**
     * @return {@code false} for {@code null}, empty, or any value containing a Unicode whitespace
     *     character (including the non-breaking variants {@link Character#isWhitespace(int)}
     *     excludes — see {@link #EXTRA_BLANK_CODE_POINTS}) or an ISO control character; {@code
     *     true} for everything else, including non-ASCII letters, digits, punctuation, and
     *     symbols.
     */
    private static boolean isPermittedPlaceholderValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint) || isExtraBlank(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isExtraBlank(int codePoint) {
        for (int extra : EXTRA_BLANK_CODE_POINTS) {
            if (extra == codePoint) {
                return true;
            }
        }
        return false;
    }

    private boolean dispatchElevated(Player actor, Button button, String resolved) {
        PermissionAttachment attachment = actor.addAttachment(plugin);
        try {
            button.grant().forEach(node -> attachment.setPermission(node, true));
            actor.recalculatePermissions();
            return runner.run(actor, resolved);
        } finally {
            actor.removeAttachment(attachment);
            actor.recalculatePermissions();
        }
    }
}
