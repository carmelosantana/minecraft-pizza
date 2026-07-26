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
 * What a resolved-ACCEPTED invite does. {@link Travel} is the original behaviour (teleport the
 * invitee to the inviter); {@link RunCommand} dispatches an allowlisted command as console with
 * {@code %target%} = the consenting invitee. Pure data — no Bukkit, so the consent race stays
 * unit-testable.
 */
public sealed interface ConsentAction {
    record Travel(String world) implements ConsentAction {}

    record RunCommand(String command) implements ConsentAction {}
}
