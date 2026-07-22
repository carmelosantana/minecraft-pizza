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
