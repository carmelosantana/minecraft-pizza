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

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

final class MenuTextTest {

    private static String asSection(net.kyori.adventure.text.Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c);
    }

    @Test
    void javaTranslatesAmpersandCodesToAColouredComponent() {
        assertEquals("§6Pizza Menu", asSection(MenuText.java("&6Pizza Menu")));
    }

    @Test
    void javaLeavesCodeFreeTextUnchanged() {
        assertEquals("Pizza Menu", asSection(MenuText.java("Pizza Menu")));
    }

    @Test
    void javaMapsNullAndBlankToEmpty() {
        assertEquals("", asSection(MenuText.java(null)));
        assertEquals("", asSection(MenuText.java("   ")));
    }

    @Test
    void bedrockTranslatesAmpersandToSection() {
        assertEquals("§6Pizza Menu", MenuText.bedrock("&6Pizza Menu"));
    }

    @Test
    void bedrockLeavesCodeFreeTextUnchanged() {
        assertEquals("Cool Items", MenuText.bedrock("Cool Items"));
    }

    @Test
    void bedrockMapsNullAndBlankToEmptyString() {
        assertEquals("", MenuText.bedrock(null));
        assertEquals("", MenuText.bedrock("  "));
    }
}
