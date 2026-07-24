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

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;
import org.xpfarm.pizza.MenuService;
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.ButtonImage;
import org.xpfarm.pizza.menu.Menu;

/**
 * Renders a {@link Menu} as a Cumulus {@code SimpleForm} for a Bedrock (Floodgate) player.
 *
 * <p>One of exactly two files in the plugin permitted to import {@code org.geysermc.*} (the
 * other is {@link FloodgateBridge}). Never constructed or referenced except by code that already
 * knows Floodgate is present; {@link MenuRenderer} and {@link ButtonSink}, the interfaces this
 * class implements/consumes, are deliberately Geyser-free so a caller can hold a {@code
 * MenuRenderer} reference without forcing this class — or its imports — to load.
 *
 * <h2>Permission filtering</h2>
 *
 * <p>Uses {@link MenuService#visibleTo}, the same filter {@link ChestRenderer#open} calls: a
 * button whose permission the player lacks is dropped entirely, and the relative order of
 * survivors is preserved. The two renderers must agree on this list, since a config author only
 * ever sees one menu definition that both platforms render.
 *
 * <h2>Index-based response resolution</h2>
 *
 * <p>Cumulus 1.1.2 has no per-button callbacks (that lands in the unreleased 2.0); a form
 * response only reports {@link SimpleFormResponse#clickedButtonId()}, an index into the button
 * list as it was submitted. The filtered {@code visible} list built in {@link #open} is captured
 * once by the response handler's closure and never rebuilt, so an index can never resolve against
 * a different list than the one actually shown to the player.
 *
 * <h2>Bedrock detection</h2>
 *
 * <p>{@link BedrockBridge#isBedrock} — backed by {@code FloodgateApi.isFloodgatePlayer}, never
 * the UUID-prefix test — is checked immediately before every {@code sendForm} call, because
 * {@code FloodgateApi.sendForm} silently returns {@code true} for a Java player's UUID: an
 * unchecked send would appear to succeed while doing nothing.
 *
 * <h2>Offline players</h2>
 *
 * <p>Floodgate fires a form's {@code closedResultHandler} from {@code PlayerQuitEvent}, so a
 * handler can run after the player has already disconnected. Both handlers re-fetch the player
 * by {@link UUID} via {@link Bukkit#getPlayer(UUID)} and null-check before acting.
 */
public final class BedrockRenderer implements MenuRenderer {

    private final Plugin plugin;
    private final ButtonSink sink;
    private final BedrockBridge bridge;

    public BedrockRenderer(Plugin plugin, ButtonSink sink, BedrockBridge bridge) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public void open(Player player, Menu menu) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");

        UUID id = player.getUniqueId();
        List<Button> visible = MenuService.visibleTo(menu, player::hasPermission);

        SimpleForm.Builder builder =
                SimpleForm.builder().title(MenuText.bedrock(menu.title())).content(MenuText.bedrock(menu.content()));
        for (Button button : visible) {
            addButton(builder, button);
        }
        builder.validResultHandler(response -> onValid(id, menu, visible, response));
        builder.closedResultHandler(() -> onClosed(id));

        SimpleForm form = builder.build();

        // Checked immediately before sendForm, per class: FloodgateApi#sendForm silently
        // returns true for a Java player's UUID, so an unchecked send would look successful
        // while doing nothing.
        if (!bridge.isBedrock(id)) {
            return;
        }
        FloodgateApi.getInstance().sendForm(id, form);
    }

    private static void addButton(SimpleForm.Builder builder, Button button) {
        ButtonImage image = button.image();
        if (image != null && image.data() != null && !image.data().isBlank()) {
            // FormImage.Type.PATH, never URL: a URL image forces Geyser through a documented
            // ~1s render-delay workaround before the form can be shown.
            builder.button(MenuText.bedrock(button.label()), FormImage.Type.PATH, image.data());
        } else {
            builder.button(MenuText.bedrock(button.label()));
        }
    }

    private void onValid(UUID id, Menu menu, List<Button> visible, SimpleFormResponse response) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            // Floodgate can invoke this after the player has already disconnected.
            return;
        }
        int clicked = response.clickedButtonId();
        if (clicked < 0 || clicked >= visible.size()) {
            plugin.getLogger()
                    .warning("menu '" + menu.id() + "' got an out-of-range form response ("
                            + clicked + " of " + visible.size() + " buttons); ignoring");
            return;
        }
        sink.activate(player, menu, visible.get(clicked));
    }

    private void onClosed(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            // Floodgate fires closedResultHandler from PlayerQuitEvent, so this can run for a
            // player who has already left. Nothing to do once that is confirmed.
            return;
        }
        // Dismissal without a selection: no action required beyond confirming the player is
        // still connected.
    }
}
