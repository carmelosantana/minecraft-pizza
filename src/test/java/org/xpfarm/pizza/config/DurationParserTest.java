/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
// src/test/java/org/xpfarm/pizza/config/DurationParserTest.java
package org.xpfarm.pizza.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class DurationParserTest {

    @Test
    void parsesSecondsMinutesHoursAndDays() {
        assertEquals(Duration.ofSeconds(60), DurationParser.parse("60s"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5m"));
        assertEquals(Duration.ofHours(24), DurationParser.parse("24h"));
        assertEquals(Duration.ofDays(2), DurationParser.parse("2d"));
    }

    @Test
    void treatsBareNumbersAsSeconds() {
        assertEquals(Duration.ofSeconds(30), DurationParser.parse("30"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("-5m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(""));
    }
}
