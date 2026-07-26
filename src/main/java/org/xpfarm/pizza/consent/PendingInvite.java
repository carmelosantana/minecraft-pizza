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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One outstanding "come travel with me" invite from {@code inviter} to {@code invitee}, and the
 * single-winner race that decides how it ends.
 *
 * <p>Five different callers can race to resolve the same invite: the invitee tapping accept or
 * decline, the invite being superseded by a newer one, {@code ConsentService}'s timeout task, and
 * (per confirmed Floodgate/Geyser behaviour) a form-close handler that timeout itself triggers.
 * Exactly one of those may win. Resolution is guarded by a single {@link AtomicBoolean}, flipped
 * with {@link AtomicBoolean#compareAndSet(boolean, boolean)} rather than a get-then-set, so the
 * "did I win" decision is itself atomic and safe to call from any thread, including a Bukkit
 * scheduler thread and a network thread at the same instant.
 *
 * <p>This class is intentionally free of any Bukkit dependency — it is pure state, which is what
 * makes the race fully unit-testable without a running server.
 */
public final class PendingInvite {

    private final UUID inviter;
    private final UUID invitee;
    private final ConsentAction action;

    private final AtomicBoolean resolved = new AtomicBoolean(false);

    // Written exactly once, by whichever thread wins the compareAndSet below, strictly after that
    // CAS succeeds. Declared volatile so that win is safely published to every other thread that
    // later observes resolved == true through any means, including a caller that reads outcome()
    // without going through resolve() itself.
    private volatile InviteOutcome outcome;

    public PendingInvite(UUID inviter, UUID invitee, ConsentAction action) {
        this.inviter = Objects.requireNonNull(inviter, "inviter");
        this.invitee = Objects.requireNonNull(invitee, "invitee");
        this.action = Objects.requireNonNull(action, "action");
    }

    /**
     * Attempts to settle this invite on {@code outcome}. The first caller to reach this method
     * wins and returns {@code true}; every subsequent call, from any thread, for any outcome
     * (including the same one) is a no-op that returns {@code false}. Callers must treat a
     * {@code false} return as "someone else already decided this invite" and take no further
     * player-facing action.
     */
    public boolean resolve(InviteOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (resolved.compareAndSet(false, true)) {
            this.outcome = outcome;
            return true;
        }
        return false;
    }

    /** The settled outcome, or {@code null} if nobody has resolved this invite yet. */
    public InviteOutcome outcome() {
        return outcome;
    }

    public UUID inviter() {
        return inviter;
    }

    public UUID invitee() {
        return invitee;
    }

    public ConsentAction action() {
        return action;
    }
}
