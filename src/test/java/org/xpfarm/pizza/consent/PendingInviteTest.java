/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.consent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PendingInviteTest {

    private PendingInvite invite() {
        return new PendingInvite(UUID.randomUUID(), UUID.randomUUID(), "creative");
    }

    @Test
    void startsUnresolved() {
        assertNull(invite().outcome());
    }

    @Test
    void theFirstResolutionWins() {
        PendingInvite invite = invite();

        assertTrue(invite.resolve(InviteOutcome.ACCEPTED));
        assertFalse(invite.resolve(InviteOutcome.TIMED_OUT), "a second resolution must not win");
        assertEquals(InviteOutcome.ACCEPTED, invite.outcome());
    }

    @Test
    void timeoutAndCloseRaceSafely() {
        // closeForm() on timeout itself fires the closed handler, so these always race.
        PendingInvite invite = invite();

        assertTrue(invite.resolve(InviteOutcome.TIMED_OUT));
        assertFalse(invite.resolve(InviteOutcome.CLOSED));
        assertEquals(InviteOutcome.TIMED_OUT, invite.outcome());
    }

    @Test
    void exactlyOneOfManyConcurrentResolversWins() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            PendingInvite invite = invite();
            AtomicInteger winners = new AtomicInteger();
            int racers = 5;
            CountDownLatch start = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
                List<InviteOutcome> outcomes = List.of(
                        InviteOutcome.ACCEPTED, InviteOutcome.DECLINED, InviteOutcome.CLOSED,
                        InviteOutcome.TIMED_OUT, InviteOutcome.SUPERSEDED);
                for (InviteOutcome outcome : outcomes) {
                    pool.submit(() -> {
                        start.await();
                        if (invite.resolve(outcome)) {
                            winners.incrementAndGet();
                        }
                        return null;
                    });
                }
                start.countDown();
            }

            assertEquals(1, winners.get(), "exactly one resolver must win");
            assertNotNull(invite.outcome());
        }
    }
}
