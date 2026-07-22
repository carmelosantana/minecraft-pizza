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

import java.util.List;
import java.util.Objects;

/** A single screen of buttons, identified by the key it was declared under in {@code menus}. */
public record Menu(String id, String title, String content, List<Button> buttons) {

    public Menu {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }
}
