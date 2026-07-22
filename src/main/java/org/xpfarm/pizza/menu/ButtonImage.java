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

/**
 * An image reference attached to a button. {@code type} names the source (e.g. an item texture or
 * a URL), {@code data} is the source-specific payload.
 */
public record ButtonImage(String type, String data) {}
