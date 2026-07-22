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

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CooldownServiceTest {

    private final UUID player = UUID.randomUUID();

    private static final class TickingClock extends Clock {
        private Instant now = Instant.parse("2026-07-22T12:00:00Z");
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void isReadyBeforeAnyUse() {
        assertTrue(new CooldownService(new TickingClock()).isReady(player, "main.0"));
    }

    @Test
    void blocksUntilTheCooldownElapses() {
        TickingClock clock = new TickingClock();
        CooldownService service = new CooldownService(clock);

        service.mark(player, "main.0", Duration.ofHours(1));
        assertFalse(service.isReady(player, "main.0"));

        clock.advance(Duration.ofMinutes(59));
        assertFalse(service.isReady(player, "main.0"));

        clock.advance(Duration.ofMinutes(2));
        assertTrue(service.isReady(player, "main.0"));
    }

    @Test
    void reportsRemainingTime() {
        TickingClock clock = new TickingClock();
        CooldownService service = new CooldownService(clock);

        service.mark(player, "main.0", Duration.ofHours(1));
        clock.advance(Duration.ofMinutes(20));

        assertEquals(Duration.ofMinutes(40), service.remaining(player, "main.0"));
    }

    @Test
    void tracksButtonsAndPlayersIndependently() {
        CooldownService service = new CooldownService(new TickingClock());
        service.mark(player, "main.0", Duration.ofHours(1));

        assertTrue(service.isReady(player, "main.1"), "a different button is unaffected");
        assertTrue(service.isReady(UUID.randomUUID(), "main.0"), "a different player is unaffected");
    }

    @Test
    void zeroCooldownNeverBlocks() {
        CooldownService service = new CooldownService(new TickingClock());
        service.mark(player, "main.0", Duration.ZERO);
        assertTrue(service.isReady(player, "main.0"));
    }

    @Test
    void forgetClearsAPlayersCooldowns() {
        CooldownService service = new CooldownService(new TickingClock());
        service.mark(player, "main.0", Duration.ofHours(1));

        service.forget(player);

        assertTrue(service.isReady(player, "main.0"));
    }
}
