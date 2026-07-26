/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class ConfigHashesTest {

    @Test
    void loadsAtLeastTheShippedDefaults() {
        assertTrue(ConfigHashes.knownHashes().size() >= 1, "expected the shipped default hashes");
    }

    @Test
    void currentDefaultHashIsListed() {
        // The whole auto-refresh scheme breaks if the CURRENT shipped default's hash is not in the
        // file: an up-to-date server would look 'customized'. This guards the per-release obligation.
        String currentHash = ConfigRefreshDecision.sha256Hex(ConfigHashes.currentDefaultBytes());
        assertTrue(ConfigHashes.knownHashes().contains(currentHash),
                "config-hashes.txt must contain the current shipped config.yml hash " + currentHash);
    }

    @Test
    void currentDefaultBytesAreTheBundledConfig() throws Exception {
        try (InputStream in = ConfigHashes.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            assertArrayEquals(in.readAllBytes(), ConfigHashes.currentDefaultBytes());
        }
    }
}
