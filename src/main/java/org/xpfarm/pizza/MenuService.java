/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.xpfarm.pizza.config.PizzaConfig;
import org.xpfarm.pizza.consent.ConsentService;
import org.xpfarm.pizza.dispatch.ActionDispatcher;
import org.xpfarm.pizza.dispatch.CommandAllowlist;
import org.xpfarm.pizza.dispatch.CooldownService;
import org.xpfarm.pizza.dispatch.Placeholders;
import org.xpfarm.pizza.menu.Action;
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.Menu;
import org.xpfarm.pizza.menu.RunAs;
import org.xpfarm.pizza.render.BedrockBridge;
import org.xpfarm.pizza.render.BedrockRenderer;
import org.xpfarm.pizza.render.ButtonSink;
import org.xpfarm.pizza.render.ChestRenderer;
import org.xpfarm.pizza.render.MenuRenderer;

/**
 * Ties every other Task 1-5 component into one working menu: picks the right {@link MenuRenderer}
 * for a player, is the single source of the permission-visibility filter both renderers use, and
 * is the {@link ButtonSink} that turns a resolved button press into a permission check, a cooldown
 * check, and finally the button's action.
 *
 * <h2>Renderer wiring</h2>
 *
 * <p>{@link ChestRenderer} and {@link BedrockRenderer} each need this service as their {@link
 * ButtonSink}, and this service needs both renderers constructed before it can route to either —
 * a constructor cycle. {@link #setRenderers} breaks it: this service is built first (without
 * renderers), each renderer is built with a reference to it, and {@link #setRenderers} wires the
 * two renderers in immediately afterward, before any player can interact with either.
 *
 * <h2>Activation order</h2>
 *
 * <p>{@link #activate} is fixed: permission check, then cooldown check, then the action. Both
 * checks are re-run here even though {@link #visibleTo} already hid an unpermitted button from the
 * menu the player is looking at — the rendered list and the live config can diverge (a reload
 * landed between open and click, or a stale Bedrock form is answered after permissions changed),
 * so nothing about what was shown is trusted at activation time.
 *
 * <h2>Reload</h2>
 *
 * <p>{@link #reload} swaps in a freshly parsed {@link PizzaConfig} — including rebuilding the
 * {@link CommandAllowlist} and {@link ActionDispatcher} from its {@code command-allowlist}, so a
 * root added or removed by a reload takes effect immediately rather than only on the next server
 * restart. {@link CooldownService} and {@link ConsentService} are untouched by a reload: a config
 * edit must not wipe an in-progress cooldown or a pending travel invite.
 */
public final class MenuService implements ButtonSink, Listener {

    /**
     * Upper bound on how many online-player buttons the invite picker will ever render. A chest
     * (six rows, 54 slots) or a Cumulus form could technically go higher, but an unbounded list is
     * exactly the failure this cap exists to prevent — see {@link #openInvitePicker}.
     */
    private static final int MAX_INVITE_CANDIDATES = 45;

    private final Plugin plugin;
    private final BedrockBridge bridge;
    private final CooldownService cooldowns;
    private final ConsentService consent;

    private volatile PizzaConfig config;
    private volatile ActionDispatcher dispatcher;

    private MenuRenderer chestRenderer;
    private MenuRenderer bedrockRenderer;

    public MenuService(
            Plugin plugin,
            PizzaConfig config,
            BedrockBridge bridge,
            CooldownService cooldowns,
            ActionDispatcher dispatcher,
            ConsentService consent) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.consent = Objects.requireNonNull(consent, "consent");
    }

    /**
     * Wires the two renderers in after construction — see the class-level note on why this cannot
     * happen in the constructor. Must be called exactly once, before {@link #open} or {@link
     * #activate} is reachable from any registered listener or command.
     */
    public void setRenderers(MenuRenderer chestRenderer, MenuRenderer bedrockRenderer) {
        this.chestRenderer = Objects.requireNonNull(chestRenderer, "chestRenderer");
        this.bedrockRenderer = Objects.requireNonNull(bedrockRenderer, "bedrockRenderer");
    }

    /**
     * Swaps in a freshly parsed config — e.g. from {@code /pizza reload} — including a rebuilt
     * {@link CommandAllowlist} and {@link ActionDispatcher}. An already-open menu is unaffected: the
     * renderers captured their own immutable {@link Menu}/{@link Button} objects at open time (see
     * {@link ChestRenderer}'s state-tracking notes), so this swap can never corrupt one in progress.
     */
    public void reload(PizzaConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig");
        this.config = newConfig;
        this.dispatcher = new ActionDispatcher(plugin, new CommandAllowlist(newConfig.commandAllowlist()));
    }

    public PizzaConfig config() {
        return config;
    }

    /**
     * The buttons of {@code menu} that {@code hasPermission} allows, in {@code menu.buttons()}
     * order. Static and free of any {@link Player} dependency so it is unit-testable without a
     * running server, and the single source of this filter for both {@link MenuRenderer}
     * implementations — they must never diverge, since the Bedrock renderer resolves a form
     * response by index into this exact list; a divergence would mean a child taps one button and
     * triggers another.
     */
    public static List<Button> visibleTo(Menu menu, Predicate<String> hasPermission) {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(hasPermission, "hasPermission");
        List<Button> result = new ArrayList<>();
        for (Button button : menu.buttons()) {
            String permission = button.permission();
            if (permission == null || permission.isBlank() || hasPermission.test(permission)) {
                result.add(button);
            }
        }
        return List.copyOf(result);
    }

    /** A Bedrock player is never routed to the chest renderer; every other player is. */
    public MenuRenderer rendererFor(Player player) {
        Objects.requireNonNull(player, "player");
        if (bridge.isAvailable() && bridge.isBedrock(player.getUniqueId())) {
            return bedrockRenderer;
        }
        return chestRenderer;
    }

    /**
     * Opens {@code menuId} for {@code player}, expanding any {@code worlds: true} button into one
     * button per {@code allowed-worlds} entry first. An unknown menu id is logged and otherwise
     * ignored — reachable only from a stale {@code open} action left behind by a reload that
     * dropped a menu.
     */
    public void open(Player player, String menuId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menuId, "menuId");

        Menu menu = config.menus().get(menuId);
        if (menu == null) {
            plugin.getLogger()
                    .warning("player " + player.getName() + " tried to open unknown menu '" + menuId + "'");
            return;
        }
        rendererFor(player).open(player, expandWorlds(menu));
    }

    @Override
    public void activate(Player player, Menu menu, Button button) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(button, "button");

        if (!isPermitted(player, button)) {
            sendMessage(player, "no-permission", Map.of(), "&cThat button isn't for you.");
            return;
        }

        if (!cooldowns.isReady(player.getUniqueId(), button.id())) {
            String remaining = formatDuration(cooldowns.remaining(player.getUniqueId(), button.id()));
            sendMessage(player, "cooldown", Map.of("time", remaining), "&eYou can do that again in %time%.");
            return;
        }

        switch (button.action()) {
            case Action.OpenMenu openMenu -> open(player, openMenu.menuId());
            case Action.Invite ignored -> openInvitePicker(player);
            case Action.InvitePlayer invitePlayer -> activateInvitePlayer(player, invitePlayer);
            case Action.RunCommand ignored -> activateRunCommand(player, button);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.forget(id);
        consent.forget(id);
        // ChestRenderer clears its own open-menu tracking for this player from its own
        // PlayerQuitEvent handler — it is registered as a Listener in its own right, so nothing
        // here needs to reach into it.
    }

    private void activateRunCommand(Player player, Button button) {
        if (button.eachOnline() && button.runAs() == RunAs.PLAYER_ELEVATED) {
            // The config parser already refuses this combination at load time; asserted again
            // here, defence in depth, in case a button ever reaches activation by a path that
            // did not go through ConfigParser (a future in-memory-built menu, for instance).
            plugin.getLogger()
                    .severe("button " + button.id() + " combines each-online with player-elevated; "
                            + "refusing to dispatch");
            return;
        }

        boolean dispatched = button.eachOnline()
                ? dispatchEachOnline(player, button)
                : dispatcher.dispatch(player, button, Map.of("player", player.getName()));

        if (dispatched) {
            cooldowns.mark(player.getUniqueId(), button.id(), button.cooldown());
        }
    }

    /**
     * Runs {@code button} once per currently online player, substituting {@code %target%} with
     * each one's name (in addition to {@code %player%}, the button's presser). The shared cooldown
     * for this press is marked if at least one of those dispatches actually ran — a fan-out that is
     * refused for every target must not start a cooldown any more than a single refused dispatch
     * would.
     */
    private boolean dispatchEachOnline(Player actor, Button button) {
        boolean anySucceeded = false;
        for (Player target : Bukkit.getOnlinePlayers()) {
            Map<String, String> placeholders = Map.of("player", actor.getName(), "target", target.getName());
            if (dispatcher.dispatch(actor, button, placeholders)) {
                anySucceeded = true;
            }
        }
        return anySucceeded;
    }

    /**
     * Expands every {@code worlds: true} button in {@code menu} into one button per {@code
     * allowed-worlds} entry, substituting {@code %world%} into both the label and the command.
     * Returns {@code menu} unchanged when it has no such button, so a menu with none pays no
     * allocation cost.
     */
    private Menu expandWorlds(Menu menu) {
        if (menu.buttons().stream().noneMatch(Button::worlds)) {
            return menu;
        }

        List<Button> expanded = new ArrayList<>();
        for (Button button : menu.buttons()) {
            if (!button.worlds()) {
                expanded.add(button);
                continue;
            }
            if (!(button.action() instanceof Action.RunCommand runCommand)) {
                // The parser already refuses `worlds: true` on a non-command button; unreachable
                // in practice, skipped rather than thrown so a render never dies mid-menu.
                continue;
            }
            for (String world : config.allowedWorlds()) {
                Map<String, String> vars = Map.of("world", world);
                expanded.add(new Button(
                        button.id() + "@" + world,
                        Placeholders.apply(button.label(), vars),
                        button.image(),
                        button.permission(),
                        new Action.RunCommand(Placeholders.apply(runCommand.command(), vars)),
                        button.runAs(),
                        button.grant(),
                        button.cooldown(),
                        false,
                        button.eachOnline()));
            }
        }
        return new Menu(menu.id(), menu.title(), menu.content(), expanded);
    }

    private static boolean isPermitted(Player player, Button button) {
        String permission = button.permission();
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    /**
     * Builds a {@link Menu} of every online player except {@code presser} — one {@link
     * Action.InvitePlayer} button per candidate — and renders it through {@link #rendererFor},
     * exactly like any other menu: a Bedrock presser gets a Cumulus form, a Java presser gets a
     * chest. The world every candidate button invites to is fixed at build time to {@code
     * presser}'s current world, per this task's own decision on where "the world" comes from when
     * the invite button carries none itself.
     *
     * <p>Capped at {@link #MAX_INVITE_CANDIDATES}: never render an unbounded form. A server with
     * more online players than the cap logs a truncation warning rather than silently dropping
     * players with no explanation.
     */
    private void openInvitePicker(Player presser) {
        String world = presser.getWorld().getName();

        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(presser.getUniqueId())) {
                candidates.add(online);
            }
        }

        if (candidates.isEmpty()) {
            sendMessage(presser, "invite-no-players", Map.of(), "&eNobody else is online right now.");
            return;
        }

        if (candidates.size() > MAX_INVITE_CANDIDATES) {
            plugin.getLogger()
                    .warning("invite picker for " + presser.getName() + " truncated from "
                            + candidates.size() + " to " + MAX_INVITE_CANDIDATES
                            + " online players");
            candidates = candidates.subList(0, MAX_INVITE_CANDIDATES);
        }

        List<Button> buttons = new ArrayList<>();
        for (Player candidate : candidates) {
            buttons.add(new Button(
                    "invite-picker." + candidate.getUniqueId(),
                    candidate.getName(),
                    null,
                    null,
                    new Action.InvitePlayer(candidate.getUniqueId(), world),
                    RunAs.CONSOLE,
                    List.of(),
                    Duration.ZERO,
                    false,
                    false));
        }

        Menu picker = new Menu("invite-picker", "Invite a friend", "Who do you want to invite?", buttons);
        rendererFor(presser).open(presser, picker);
    }

    /**
     * A candidate was selected from {@link #openInvitePicker}'s menu. The candidate may have gone
     * offline between the picker being rendered and this click resolving — {@link
     * Bukkit#getPlayer(UUID)} is null-checked rather than assumed still connected.
     */
    private void activateInvitePlayer(Player presser, Action.InvitePlayer invitePlayer) {
        Player target = Bukkit.getPlayer(invitePlayer.target());
        if (target == null || !target.isOnline()) {
            sendMessage(presser, "invite-target-offline", Map.of(),
                    "&eThat player is no longer online.");
            return;
        }
        consent.invite(presser, target, invitePlayer.world());
    }

    private void sendMessage(Player player, String key, Map<String, String> vars, String fallback) {
        String template = config.messages().getOrDefault(key, fallback);
        String resolved = Placeholders.apply(template, vars);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(resolved));
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        if (totalSeconds >= 86400) {
            return (totalSeconds / 86400) + "d";
        }
        if (totalSeconds >= 3600) {
            return (totalSeconds / 3600) + "h";
        }
        if (totalSeconds >= 60) {
            return (totalSeconds / 60) + "m";
        }
        return totalSeconds + "s";
    }
}
