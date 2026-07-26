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
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
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
 *   <li>{@link CommandRunner#run} is always called through {@link #runSafely}, which catches any
 *       {@link RuntimeException} the underlying {@code Bukkit.dispatchCommand} (or a stubbed
 *       runner in a test) throws, logs it, and returns {@code false} instead of letting it unwind
 *       into the caller. {@code dispatch} is reachable directly from an inventory-click or Cumulus
 *       form-response handler, where an unchecked exception would otherwise surface as a console
 *       stack trace and abandon the interaction mid-click for whichever child pressed the button.
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

    /**
     * Runs a raw allowlisted command as console, applying the same placeholder-value validation and
     * post-substitution allowlist re-check as {@link #dispatch}. Used by the consent-accept path and
     * the staff (no-consent) picker path, neither of which has a {@link Button}. Returns whether the
     * command actually reached the runner.
     */
    public boolean dispatchConsoleCommand(String commandTemplate, Map<String, String> placeholders, String context) {
        Objects.requireNonNull(commandTemplate, "commandTemplate");
        Objects.requireNonNull(placeholders, "placeholders");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (!isPermittedPlaceholderValue(entry.getValue())) {
                plugin.getLogger().warning("refusing console command for " + context + ": placeholder '"
                        + entry.getKey() + "' has whitespace or a control character");
                return false;
            }
        }
        String resolved = Placeholders.apply(commandTemplate, placeholders);
        if (!allowlist.permits(resolved)) {
            plugin.getLogger().warning("refusing '" + resolved + "' for " + context
                    + ": not in command-allowlist after substitution");
            return false;
        }
        try {
            // Resolve the console sender only when a server is actually present. In production a
            // server is always set, so this is byte-identical to calling Bukkit.getConsoleSender()
            // directly; it is what lets the console-dispatch path be unit-tested (with a fake runner
            // that ignores the sender) without standing up a live Bukkit singleton — the same reason
            // CommandRunner is a seam in the first place.
            CommandSender console = Bukkit.getServer() != null ? Bukkit.getConsoleSender() : null;
            return runner.run(console, resolved);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "console command '" + resolved + "' threw for " + context, e);
            return false;
        }
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
            case CONSOLE -> runSafely(Bukkit.getConsoleSender(), resolved, button, actor);
            case PLAYER -> runSafely(actor, resolved, button, actor);
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
            return runSafely(actor, resolved, button, actor);
        } finally {
            actor.removeAttachment(attachment);
            actor.recalculatePermissions();
        }
    }

    /**
     * Runs {@code command} through {@link #runner}, catching and logging any {@link
     * RuntimeException} it throws (M3) instead of letting it propagate into whatever called {@link
     * #dispatch} — an inventory click or Cumulus form-response handler, where an unchecked
     * exception would otherwise print a console stack trace and abandon the interaction mid-click.
     * The {@link RunAs#PLAYER_ELEVATED} permission-attachment cleanup in {@link
     * #dispatchElevated}'s own {@code finally} block is unaffected: it still runs regardless of
     * whether this method returns normally or this catch swallows an exception.
     */
    private boolean runSafely(CommandSender sender, String command, Button button, Player actor) {
        try {
            return runner.run(sender, command);
        } catch (RuntimeException e) {
            plugin.getLogger()
                    .log(Level.WARNING,
                            "command '" + command + "' threw while running for " + actor.getName()
                                    + " (button " + button.id() + "); treating it as a failed "
                                    + "dispatch rather than letting it propagate into the click "
                                    + "handler",
                            e);
            return false;
        }
    }
}
