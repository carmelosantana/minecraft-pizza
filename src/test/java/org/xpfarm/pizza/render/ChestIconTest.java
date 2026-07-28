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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.ButtonImage;

final class ChestIconTest {

    @Test
    void prefersTheDedicatedMaterialField() {
        assertEquals("DIAMOND",
                ChestRenderer.iconNameFor(new ButtonImage("path", "textures/items/diamond", "DIAMOND")));
    }

    @Test
    void readsAMaterialOnlyImage() {
        assertEquals("BREAD", ChestRenderer.iconNameFor(new ButtonImage(null, null, "BREAD")));
    }

    @Test
    void aBedrockOnlyPathHasNoJavaIcon() {
        assertNull(ChestRenderer.iconNameFor(new ButtonImage("path", "textures/items/diamond", null)),
                "a Bedrock texture path alone must not become a Java icon; the chest falls back to PAPER");
    }

    @Test
    void stillHonoursTheLegacyMaterialTypeImage() {
        assertEquals("DIAMOND", ChestRenderer.iconNameFor(new ButtonImage("material", "DIAMOND", null)));
    }

    @Test
    void nullImageHasNoIcon() {
        assertNull(ChestRenderer.iconNameFor(null));
    }
}
