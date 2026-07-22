/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ChestLayoutTest {

    @Test
    void sizesTheChestToTheButtonCount() {
        assertEquals(1, ChestRenderer.rowsFor(1));
        assertEquals(1, ChestRenderer.rowsFor(9));
        assertEquals(2, ChestRenderer.rowsFor(10));
        assertEquals(6, ChestRenderer.rowsFor(54));
    }

    @Test
    void clampsToSixRows() {
        assertEquals(6, ChestRenderer.rowsFor(55), "a chest cannot exceed six rows");
        assertEquals(6, ChestRenderer.rowsFor(500));
    }

    @Test
    void alwaysUsesAtLeastOneRow() {
        assertEquals(1, ChestRenderer.rowsFor(0));
    }
}
