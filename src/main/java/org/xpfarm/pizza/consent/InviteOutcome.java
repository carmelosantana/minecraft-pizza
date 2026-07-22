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

/**
 * The one outcome a {@link PendingInvite} can settle on. Exactly one of these ever wins for a
 * given invite — see {@link PendingInvite#resolve(InviteOutcome)}.
 */
public enum InviteOutcome {

    /** The invitee actively accepted; the only outcome that may ever result in a teleport. */
    ACCEPTED,

    /** The invitee actively declined. */
    DECLINED,

    /**
     * The invite was dismissed without an explicit accept/decline — today this only happens when
     * a party quits before responding (see {@link ConsentService#forget(java.util.UUID)}).
     * Deliberately distinct from {@link #DECLINED}: silence is not "no."
     */
    CLOSED,

    /** Nobody responded before {@code ConsentService}'s configured timeout elapsed. */
    TIMED_OUT,

    /**
     * A newer invite arrived for the same invitee before this one was answered. Deliberately
     * distinct from {@link #DECLINED} so the original inviter is not told "they said no" when
     * really a second invite simply took priority.
     */
    SUPERSEDED
}
