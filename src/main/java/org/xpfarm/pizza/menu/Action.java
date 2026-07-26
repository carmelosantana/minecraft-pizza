/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.menu;

import java.util.UUID;

/**
 * What a button does when pressed. Every button has exactly one action; the config parser rejects
 * buttons with zero or more than one.
 */
public sealed interface Action {
    record OpenMenu(String menuId) implements Action {}

    record RunCommand(String command) implements Action {}

    /** The {@code invite: true} button, pressed by the future inviter — opens the target picker. */
    record Invite() implements Action {}

    /**
     * One candidate in the target picker {@code Invite} opens: pressing it invites {@code target}
     * to {@code world}. Never produced by {@link org.xpfarm.pizza.config.ConfigParser} — a config
     * author cannot name a specific player in {@code config.yml} — only synthesised at press time
     * by {@code MenuService}'s picker, one instance per online candidate.
     */
    record InvitePlayer(UUID target, String world) implements Action {}

    /**
     * A config-authored picker button: pressing it opens a menu of online players, and selecting
     * one runs {@code command} (with {@code %target%} = the chosen player) — immediately when
     * {@code consent} is false, or after the target accepts a consent prompt when true.
     */
    record Pick(String command, boolean consent, String consentPrompt) implements Action {}

    /**
     * One candidate in the picker {@link Pick} opens. Synthesised by {@code MenuService}, never by
     * {@code ConfigParser}. Carries the origin picker button's id and cooldown so the initiator's
     * cooldown is keyed to the picker button (not this synthetic one) and marked when a request is
     * sent.
     */
    record PickTarget(UUID target, String command, boolean consent, String originButtonId,
            java.time.Duration cooldown, String promptContent) implements Action {}
}
