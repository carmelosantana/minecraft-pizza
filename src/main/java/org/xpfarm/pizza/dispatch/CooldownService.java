/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player, per-button cooldown expiry entirely in memory. Takes a {@link Clock} so
 * expiry can be tested by advancing a fake clock instead of sleeping; production wiring passes
 * {@link Clock#systemUTC()}.
 */
public final class CooldownService {

    private final Clock clock;
    private final Map<UUID, Map<String, Instant>> readyAt = new ConcurrentHashMap<>();

    public CooldownService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isReady(UUID player, String buttonId) {
        Instant expiry = expiryOf(player, buttonId);
        return expiry == null || !clock.instant().isBefore(expiry);
    }

    public Duration remaining(UUID player, String buttonId) {
        Instant expiry = expiryOf(player, buttonId);
        if (expiry == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), expiry);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public void mark(UUID player, String buttonId, Duration cooldown) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(buttonId, "buttonId");
        Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        readyAt.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                .put(buttonId, clock.instant().plus(cooldown));
    }

    /** Called on quit so a departed player's cooldowns do not linger forever in memory. */
    public void forget(UUID player) {
        readyAt.remove(player);
    }

    private Instant expiryOf(UUID player, String buttonId) {
        Map<String, Instant> perPlayer = readyAt.get(player);
        return perPlayer == null ? null : perPlayer.get(buttonId);
    }
}
