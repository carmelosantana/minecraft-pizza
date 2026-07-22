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

import java.util.Objects;
import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * The real {@link BedrockBridge}, backed by the Floodgate API.
 *
 * <p>One of exactly two files in the plugin permitted to import {@code org.geysermc.*} (the
 * other is {@link BedrockRenderer}). This class is only ever constructed from {@link
 * BedrockBridge#create}, after that method has confirmed the {@code floodgate} plugin is enabled
 * — never anywhere else, and never unconditionally, so that JVM class loading never touches this
 * class (or its Geyser imports) on a server without Floodgate.
 *
 * <p>{@link #isBedrock} uses {@link FloodgateApi#isFloodgatePlayer(UUID)}, never the UUID-prefix
 * test ({@code isFloodgateId}/{@code getMostSignificantBits() == 0}): that test misdetects a
 * Bedrock player who has linked a Java account, since a linked player's Floodgate UUID no longer
 * has the all-zero prefix. {@code isFloodgatePlayer} is Floodgate's own source of truth for
 * "is this UUID currently a connected Bedrock player."
 */
final class FloodgateBridge implements BedrockBridge {

    private final FloodgateApi api;

    FloodgateBridge() {
        this(FloodgateApi.getInstance());
    }

    FloodgateBridge(FloodgateApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public boolean isBedrock(UUID player) {
        return api.isFloodgatePlayer(player);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
