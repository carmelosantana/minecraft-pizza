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
import org.geysermc.cumulus.form.ModalForm;
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

    /**
     * Builds a Cumulus {@code ModalForm} (two buttons: "Accept"/"Decline") and sends it to {@code
     * player}, routing the response back through the supplied callbacks.
     *
     * <p>Cumulus 1.1.2's {@link org.geysermc.cumulus.response.ModalFormResponse#clickedFirst()}
     * reports which of the two buttons was pressed; {@code clickedFirst() == true} is "Accept"
     * (button1), otherwise "Decline" (button2). {@code closedResultHandler} maps straight onto
     * {@code onClose} — Floodgate fires it both for an explicit dismissal and, per confirmed
     * Floodgate/Geyser behaviour, from {@code PlayerQuitEvent} if the player disconnects with the
     * form still open. None of the three callbacks dereference a {@link
     * org.bukkit.entity.Player} directly here; they are opaque {@link Runnable}s supplied by the
     * caller (see {@code ConsentService}), which is itself careful to only touch a player by
     * {@link UUID} lookup with a null-check.
     *
     * <p>{@link FloodgateApi#isFloodgatePlayer(UUID)} is re-checked immediately before {@code
     * sendForm}, exactly like {@link BedrockRenderer#open}: {@code FloodgateApi#sendForm} silently
     * returns {@code true} for a Java player's UUID, so an unchecked send would look successful
     * while doing nothing and the invitee would never see a prompt of any kind.
     */
    @Override
    public boolean askConsent(UUID player, String title, String content,
            Runnable onAccept, Runnable onDecline, Runnable onClose) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(onAccept, "onAccept");
        Objects.requireNonNull(onDecline, "onDecline");
        Objects.requireNonNull(onClose, "onClose");

        ModalForm form = ModalForm.builder()
                .title(title)
                .content(content)
                .button1("Accept")
                .button2("Decline")
                .validResultHandler(response -> {
                    if (response.clickedFirst()) {
                        onAccept.run();
                    } else {
                        onDecline.run();
                    }
                })
                .closedResultHandler(onClose)
                .build();

        // Checked immediately before sendForm, per class: FloodgateApi#sendForm silently returns
        // true for a Java player's UUID, so an unchecked send would look successful while doing
        // nothing.
        if (!isBedrock(player)) {
            return false;
        }
        return api.sendForm(player, form);
    }
}
