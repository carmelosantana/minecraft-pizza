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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Central translation of legacy {@code &} colour codes for rendered menu text. Chat messages
 * already deserialize {@code &} codes in {@code MenuService.sendMessage}; this is the equivalent
 * for the two renderers, whose titles, content, and button labels previously leaked the raw code
 * (a literal {@code &6} on a Java chest, a stray glyph in a Bedrock form).
 *
 * <p>Both methods are pure and null/blank-safe, and depend only on the Adventure library already
 * on the classpath — no Bukkit server is required, so they are unit-testable.
 */
public final class MenuText {

    private MenuText() {}

    /** Deserializes legacy {@code &} codes into a component; null/blank → {@link Component#empty()}. */
    public static Component java(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    /**
     * Translates legacy {@code &} codes to {@code §} (section) codes, which Cumulus form title,
     * content, and button fields honour on Bedrock. Implemented as a deserialize-then-reserialize
     * round-trip so it shares exactly the code table {@link #java} uses. Null/blank → {@code ""}.
     */
    public static String bedrock(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return "";
        }
        Component parsed = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        return LegacyComponentSerializer.legacySection().serialize(parsed);
    }
}
