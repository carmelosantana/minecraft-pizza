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
 * Who a button's action is executed as.
 *
 * <p>{@link #PLAYER_ELEVATED} is the only mode that grants a temporary permission before running
 * the command; it exists so a child can run one specific privileged command without holding the
 * permission permanently.
 */
public enum RunAs {
    CONSOLE,
    PLAYER,
    PLAYER_ELEVATED
}
