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

import java.util.UUID;

/**
 * The {@link BedrockBridge} used on a server without Floodgate installed: nobody is ever a
 * Bedrock player, and Bedrock support is never available. A single shared instance is enough
 * since this holds no state.
 *
 * <p>Like {@link BedrockBridge}, this class must never name a Geyser or Cumulus type in any
 * signature, field, or annotation — doing so would force the JVM to resolve those types on every
 * server, including ones without Floodgate present, which is exactly the failure this design
 * exists to avoid.
 */
public final class NoopBridge implements BedrockBridge {

    public static final NoopBridge INSTANCE = new NoopBridge();

    private NoopBridge() {}

    @Override
    public boolean isBedrock(UUID player) {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean askConsent(UUID player, String title, String content,
            Runnable onAccept, Runnable onDecline, Runnable onClose) {
        return false;
    }
}
