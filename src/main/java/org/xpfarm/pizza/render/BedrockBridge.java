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
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Whether a player is joining through Bedrock (Floodgate), and the seam {@link BedrockRenderer}
 * uses to ask.
 *
 * <p>Expressed purely in {@link UUID} / Bukkit / {@code org.xpfarm.pizza} terms — this interface
 * must never name a Geyser or Cumulus type in any signature, field, or annotation. That is what
 * lets the plugin load and enable on a server that does not have Floodgate installed at all:
 * {@link #create(Plugin)} is the only method in the whole plugin permitted to reference {@link
 * FloodgateBridge} by name, and it does so only after confirming Floodgate is present. JVM class
 * loading is lazy, so {@link FloodgateBridge} — which does import Geyser/Cumulus types — is
 * never resolved, verified, or loaded unless that branch actually runs.
 *
 * <p>CrossplatForms shipped the mistake this design avoids: an empty/no-op handler whose own
 * method signature named a Cumulus type, which forced the class to be loaded (and fail) even when
 * unused, and was worked around with a comment instead of a structural fix. Keeping every
 * Cumulus/Geyser reference inside {@link FloodgateBridge} and {@link BedrockRenderer} — and never
 * in this interface or {@link NoopBridge} — avoids that class of bug entirely.
 */
public interface BedrockBridge {

    /** Whether {@code player} is connected through Floodgate (a Bedrock client). */
    boolean isBedrock(UUID player);

    /** Whether Bedrock support is present on this server at all. */
    boolean isAvailable();

    /**
     * Shows {@code player} a two-button consent prompt ("Accept"/"Decline") with {@code title}
     * and {@code content}, if that player can be shown one at all.
     *
     * <p>Expressed purely in JDK/Bukkit/{@code org.xpfarm.pizza} terms — no Cumulus type may ever
     * appear in this signature, for the same reason the rest of this interface must stay
     * Geyser-free (see the class-level javadoc). {@code onAccept}, {@code onDecline}, and {@code
     * onClose} are plain {@link Runnable}s the implementation invokes from whatever the
     * underlying form technology's own response handlers are; the caller decides what each of
     * those means (typically settling a {@code PendingInvite}).
     *
     * @return {@code true} if a prompt was actually shown to {@code player} (in which case exactly
     *     one of the three callbacks is guaranteed to eventually run, though possibly after the
     *     player has disconnected); {@code false} if this player cannot be shown one — not a
     *     Bedrock player, or Bedrock support unavailable — in which case none of the callbacks are
     *     invoked and the caller must fall back to a Java-native path (chat plus a command).
     */
    boolean askConsent(UUID player, String title, String content,
            Runnable onAccept, Runnable onDecline, Runnable onClose);

    /**
     * Guarded factory: returns a real bridge only when the {@code floodgate} plugin is enabled,
     * otherwise {@link NoopBridge#INSTANCE}. This check — and only this check — decides whether
     * {@link FloodgateBridge}'s Geyser/Cumulus imports are ever touched.
     */
    static BedrockBridge create(Plugin plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            return NoopBridge.INSTANCE;
        }
        return new FloodgateBridge();
    }
}
