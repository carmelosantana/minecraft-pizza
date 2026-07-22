/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.consent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.render.BedrockBridge;

/**
 * The invite trigger must funnel through the same single resolution path Task 5 guards, whether it
 * arrives from a Bedrock form or a Java command. These tests pin the routing decision — Bedrock
 * form vs. Java fallback — without a live server.
 */
final class InviteWiringTest {

    /** A BedrockBridge whose askConsent outcome is scripted per test. */
    private static BedrockBridge bridge(boolean shows, Runnable capture) {
        return new BedrockBridge() {
            @Override public boolean isBedrock(UUID player) { return shows; }
            @Override public boolean isAvailable() { return true; }
            @Override public boolean askConsent(UUID player, String title, String content,
                                                Runnable onAccept, Runnable onDecline, Runnable onClose) {
                if (shows) { capture.run(); }
                return shows;
            }
        };
    }

    @Test
    void aBedrockInviteeIsPromptedByFormAndNotByChatFallback() {
        AtomicBoolean formShown = new AtomicBoolean(false);
        BedrockBridge bridge = bridge(true, () -> formShown.set(true));

        assertTrue(bridge.askConsent(UUID.randomUUID(), "t", "c", () -> {}, () -> {}, () -> {}));
        assertTrue(formShown.get(), "a Bedrock invitee must be shown the form");
    }

    @Test
    void aJavaInviteeFallsBackWhenTheFormCannotBeShown() {
        BedrockBridge bridge = bridge(false, () -> {});

        assertFalse(bridge.askConsent(UUID.randomUUID(), "t", "c", () -> {}, () -> {}, () -> {}),
                "askConsent must report false so the caller uses the Java chat/command path");
    }
}
