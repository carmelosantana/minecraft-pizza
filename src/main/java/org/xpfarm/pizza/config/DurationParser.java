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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses short human-written durations such as {@code "24h"} or a bare number of seconds. Not a
 * general-purpose ISO-8601 parser — config authors are children's server admins, not programmers.
 */
public final class DurationParser {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([smhd]?)$");

    private DurationParser() {}

    /**
     * @param text a non-negative integer optionally suffixed with {@code s}, {@code m}, {@code h},
     *     or {@code d}; a bare number is treated as seconds
     * @throws IllegalArgumentException if {@code text} is null, blank, negative, or not in that
     *     shape
     */
    public static Duration parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("duration text must not be blank: " + text);
        }

        Matcher matcher = PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unparseable duration: " + text);
        }

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unparseable duration: " + text, e);
        }

        return switch (matcher.group(2)) {
            case "s", "" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("unparseable duration: " + text);
        };
    }
}
