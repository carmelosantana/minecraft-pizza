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

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Guards which command roots a button is allowed to run. This is an allowlist, not a denylist, and
 * it must fail closed: an empty root set refuses every command, never permits one.
 *
 * <p>{@link #permits(String)} must be re-run on the fully resolved command string, not only on the
 * unresolved template — placeholder substitution happens between config-parse time and dispatch
 * time, and a value that is not fully under the server operator's control (a player's display
 * name, for instance) must not be able to smuggle a second command past a check that only ever
 * looked at the template.
 *
 * <p>The chaining-character check inside {@code permits} ({@code ;}, {@code &&}, {@code ||}, a
 * newline) is a second line of defence, not the primary guard: it is a small blocklist, and a
 * blocklist inside a fail-closed allowlist layer can never enumerate every dangerous byte. The
 * primary guard against a hostile placeholder value is upstream, in {@code ActionDispatcher},
 * which refuses any placeholder value containing whitespace or a control character before
 * substitution ever runs — that check is broad by character class rather than by an enumerated
 * list of "dangerous" strings. This class's chaining check stays in place as belt-and-braces on
 * the assembled string, but nothing here should be relied on as the sole defence.
 */
public final class CommandAllowlist {

    private final Set<String> roots;

    public CommandAllowlist(Set<String> roots) {
        Objects.requireNonNull(roots, "roots");
        Set<String> normalized = new HashSet<>();
        for (String root : roots) {
            if (root != null) {
                // Same normalization rootOf() applies to a dispatched command's root, so a
                // configured entry like "/StarterPack" and a dispatched "starterpack ..." agree —
                // see ConfigParser.parseButton, which now goes through rootOf() too (M1).
                normalized.add(rootOf(root));
            }
        }
        this.roots = Set.copyOf(normalized);
    }

    /**
     * @return {@code false} when the allowlist is empty, when {@code command} chains a second
     *     command via {@code ;}, {@code &&}, {@code ||}, or a newline, or when its root is not in
     *     the allowlist; {@code true} otherwise
     */
    public boolean permits(String command) {
        if (roots.isEmpty() || command == null) {
            return false;
        }
        if (containsChaining(command)) {
            return false;
        }
        String root = rootOf(command);
        return !root.isEmpty() && roots.contains(root);
    }

    /** Strips leading whitespace and a leading {@code /}, then lowercases the first token. */
    public static String rootOf(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.strip();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int spaceIndex = trimmed.indexOf(' ');
        String root = spaceIndex == -1 ? trimmed : trimmed.substring(0, spaceIndex);
        return root.toLowerCase(Locale.ROOT);
    }

    private static boolean containsChaining(String command) {
        return command.contains(";")
                || command.contains("&&")
                || command.contains("||")
                || command.contains("\n")
                || command.contains("\r");
    }
}
