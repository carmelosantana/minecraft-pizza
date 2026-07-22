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

import java.util.Set;
import org.junit.jupiter.api.Test;

final class CommandAllowlistTest {

    private final CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack", "worldcrud"));

    @Test
    void permitsAListedRoot() {
        assertTrue(allowlist.permits("starterpack give Carmelo"));
    }

    @Test
    void refusesAnUnlistedRoot() {
        assertFalse(allowlist.permits("op Carmelo"));
        assertFalse(allowlist.permits("stop"));
    }

    @Test
    void refusesEverythingWhenEmpty() {
        assertFalse(new CommandAllowlist(Set.of()).permits("starterpack give Carmelo"),
                "an empty allowlist fails closed");
    }

    @Test
    void isNotFooledByLeadingSlashOrWhitespace() {
        assertTrue(allowlist.permits("  /starterpack give Carmelo"));
        assertFalse(allowlist.permits("  /op Carmelo"));
    }

    @Test
    void isNotFooledByCommandChaining() {
        assertFalse(allowlist.permits("starterpack give X; op X"));
        assertFalse(allowlist.permits("starterpack give X && op X"));
    }

    @Test
    void isCaseInsensitiveOnTheRoot() {
        assertTrue(allowlist.permits("StarterPack give Carmelo"));
    }
}
