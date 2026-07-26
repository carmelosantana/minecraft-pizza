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

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.config.ConfigRefreshDecision.Result;

final class ConfigRefreshDecisionTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void identicalToCurrentDefaultIsUpToDate() {
        byte[] cur = b("menus: {}\n");
        assertEquals(Result.UP_TO_DATE, ConfigRefreshDecision.decide(cur, cur, Set.of()));
    }

    @Test
    void aKnownPriorDefaultIsRefresh() {
        byte[] onDisk = b("old default\n");
        byte[] current = b("new default\n");
        Set<String> known = Set.of(ConfigRefreshDecision.sha256Hex(onDisk));
        assertEquals(Result.REFRESH, ConfigRefreshDecision.decide(onDisk, current, known));
    }

    @Test
    void anUnknownBlobIsCustomized() {
        byte[] onDisk = b("hand edited by an admin\n");
        byte[] current = b("new default\n");
        Set<String> known = Set.of(ConfigRefreshDecision.sha256Hex(b("some other prior default\n")));
        assertEquals(Result.CUSTOMIZED, ConfigRefreshDecision.decide(onDisk, current, known));
    }

    @Test
    void currentTakesPrecedenceEvenIfAlsoInKnownSet() {
        byte[] cur = b("current\n");
        Set<String> known = Set.of(ConfigRefreshDecision.sha256Hex(cur));
        assertEquals(Result.UP_TO_DATE, ConfigRefreshDecision.decide(cur, cur, known));
    }

    @Test
    void sha256HexIsStableAndLowercase() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ConfigRefreshDecision.sha256Hex(new byte[0]));
    }
}
