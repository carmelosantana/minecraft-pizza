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

import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlaceholdersTest {

    @Test
    void substitutesKnownPlaceholders() {
        assertEquals("starterpack give Carmelo",
                Placeholders.apply("starterpack give %player%", Map.of("player", "Carmelo")));
    }

    @Test
    void leavesUnknownPlaceholdersAlone() {
        assertEquals("give %target%", Placeholders.apply("give %target%", Map.of("player", "X")));
    }

    @Test
    void doesNotRecursivelyExpandSubstitutedValues() {
        assertEquals("say %player%",
                Placeholders.apply("say %target%", Map.of("target", "%player%")),
                "a substituted value must not itself be re-scanned for placeholders");
    }
}
