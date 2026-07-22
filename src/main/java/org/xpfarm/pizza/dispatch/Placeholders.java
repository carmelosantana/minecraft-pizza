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

/**
 * Substitutes {@code %name%} placeholders in a command template with values supplied by the
 * caller. Unknown placeholders are left untouched rather than dropped, so a config typo is visible
 * in the resulting command instead of silently vanishing.
 *
 * <p>Substitution is a single left-to-right pass over the template: a value that itself contains
 * {@code %...%} text is inserted as-is and never re-scanned. This matters because a value can carry
 * attacker-influenced text (a player's display name, for instance) — re-scanning it would let that
 * text define a second placeholder and reach values it was never meant to see.
 */
public final class Placeholders {

    private Placeholders() {}

    public static String apply(String template, Map<String, String> values) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(values, "values");

        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int length = template.length();
        while (i < length) {
            char c = template.charAt(i);
            if (c == '%') {
                int end = template.indexOf('%', i + 1);
                if (end != -1) {
                    String key = template.substring(i + 1, end);
                    String value = values.get(key);
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
