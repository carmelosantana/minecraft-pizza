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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.menu.Action;
import org.xpfarm.pizza.menu.Button;
import org.xpfarm.pizza.menu.RunAs;

/**
 * Exercises {@link ActionDispatcher} against stand-ins for {@link Player} and {@link Plugin}
 * rather than a real Paper server. A real server cannot be stood up here: {@code Bukkit.setServer}
 * can only be called once per JVM (surefire reuses one JVM across every test class in this
 * module by default) and, on this API version, pulls in {@code ServerBuildInfo} state that a bare
 * {@code paper-api} dependency does not provide at all. {@link Player} and {@link Plugin} are
 * plain interfaces, so a {@link Proxy} implementing only the handful of methods {@link
 * ActionDispatcher} actually calls is enough, and {@link CommandRunner} is the seam that replaces
 * {@code Bukkit.dispatchCommand} so no static Bukkit state needs touching at all.
 */
final class ActionDispatcherTest {

    private static Plugin fakePlugin() {
        return (Plugin)
                Proxy.newProxyInstance(
                        ActionDispatcherTest.class.getClassLoader(),
                        new Class<?>[] {Plugin.class},
                        (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "getLogger" -> Logger.getLogger("action-dispatcher-test");
                                case "isEnabled" -> true;
                                case "toString" -> "fake-plugin";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> throw new UnsupportedOperationException(
                                        "fake plugin does not implement " + method.getName());
                            };
                        });
    }

    private static Button commandButton(String id, String command, RunAs runAs, List<String> grant) {
        return new Button(id, "label", null, null, new Action.RunCommand(command), runAs, grant, Duration.ZERO,
                false, false);
    }

    /** Records every permission-attachment lifecycle call and every command the runner receives. */
    private static final class FakePlayer {
        final Player proxy;
        final String name;
        final Deque<PermissionAttachment> active = new ArrayDeque<>();
        final List<PermissionAttachment> removed = new ArrayList<>();

        FakePlayer(String name) {
            this.name = name;
            this.proxy = (Player)
                    Proxy.newProxyInstance(
                            ActionDispatcherTest.class.getClassLoader(), new Class<?>[] {Player.class}, this::handle);
        }

        private Object handle(Object proxyObj, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getName" -> name;
                case "addAttachment" -> {
                    if (args.length == 1 && args[0] instanceof Plugin plugin) {
                        PermissionAttachment attachment = new PermissionAttachment(plugin, (Permissible) proxyObj);
                        active.push(attachment);
                        yield attachment;
                    }
                    throw new UnsupportedOperationException("unexpected addAttachment overload");
                }
                case "removeAttachment" -> {
                    PermissionAttachment attachment = (PermissionAttachment) args[0];
                    active.remove(attachment);
                    removed.add(attachment);
                    yield null;
                }
                case "recalculatePermissions" -> null;
                case "toString" -> "fake-player:" + name;
                case "hashCode" -> System.identityHashCode(proxyObj);
                case "equals" -> proxyObj == args[0];
                default -> throw new UnsupportedOperationException("fake player does not implement " + method.getName());
            };
        }
    }

    /** Stands in for {@link CommandRunner#BUKKIT}: records every command it is asked to run. */
    private static final class RecordingRunner implements CommandRunner {
        final List<String> ran = new ArrayList<>();
        RuntimeException toThrow;

        @Override
        public boolean run(CommandSender sender, String command) {
            ran.add(command);
            if (toThrow != null) {
                throw toThrow;
            }
            return true;
        }
    }

    @Test
    void nonPermittedResolvedCommandIsRefused() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer("Steve");
        Button button = commandButton("main.0", "op %player%", RunAs.PLAYER, List.of());

        dispatcher.dispatch(player.proxy, button, Map.of("player", "Steve"));

        assertTrue(runner.ran.isEmpty(), "an unpermitted resolved command must never reach the command runner");
    }

    /**
     * Pins the {@code dispatch} return-value contract that {@code MenuService}'s cooldown safety
     * now depends on: a refused dispatch must return {@code false} so a refused button press never
     * starts a cooldown. This is checked directly on the return value, not just inferred from
     * {@code runner.ran} staying empty — the two are different assertions, and the return value is
     * the one another class actually reads.
     */
    @Test
    void refusedByAllowlistReturnsFalse() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer("Steve");
        Button button = commandButton("main.0", "op %player%", RunAs.PLAYER, List.of());

        boolean dispatched = dispatcher.dispatch(player.proxy, button, Map.of("player", "Steve"));

        assertFalse(dispatched, "a command whose root is not in the allowlist must return false");
        assertTrue(runner.ran.isEmpty());
    }

    /**
     * Same contract, exercised via the other refusal path: a placeholder value the value-check
     * rejects (here, whitespace in a gamertag) must also return {@code false}, never run anything.
     */
    @Test
    void refusedByDisallowedPlaceholderValueReturnsFalse() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer(".Some Gamertag");
        Button button = commandButton("main.0", "starterpack give %player%", RunAs.PLAYER, List.of());

        boolean dispatched =
                dispatcher.dispatch(player.proxy, button, Map.of("player", ".Some Gamertag"));

        assertFalse(dispatched,
                "a placeholder value containing whitespace must return false — this is the case "
                        + "MenuService relies on to never start a cooldown for a refused dispatch");
        assertTrue(runner.ran.isEmpty());
    }

    /** The other half of the contract: a dispatch that actually reaches the runner returns {@code true}. */
    @Test
    void successfulDispatchReturnsTrue() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer("Steve");
        Button button = commandButton("main.0", "starterpack give %player%", RunAs.PLAYER, List.of());

        boolean dispatched = dispatcher.dispatch(player.proxy, button, Map.of("player", "Steve"));

        assertTrue(dispatched, "a command that actually reaches the runner must return true");
        assertEquals(List.of("starterpack give Steve"), runner.ran);
    }

    /**
     * A Bedrock gamertag can legitimately contain a space (if the operator turns off Floodgate's
     * default space-replacement). That must fail closed: refuse the dispatch, never substitute the
     * raw value into the command line, never crash.
     */
    @Test
    void playerNameContainingASpaceIsRefused() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer(".Some Gamertag");
        Button button = commandButton("main.0", "starterpack give %player%", RunAs.PLAYER, List.of());

        assertDoesNotThrow(
                () -> dispatcher.dispatch(player.proxy, button, Map.of("player", ".Some Gamertag")));

        assertTrue(
                runner.ran.isEmpty(),
                "a name containing a space must refuse the whole dispatch, not run a mangled command");
    }

    /**
     * Guards against re-tightening the placeholder-value check into an ASCII allowlist: a
     * non-ASCII gamertag (Cyrillic, Japanese, accented Latin, ...) is a legitimate Bedrock player
     * and must be substituted normally, not refused.
     */
    @Test
    void nonAsciiPlayerNameIsAcceptedAndSubstituted() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer(".Ñoño");
        Button button = commandButton("main.0", "starterpack give %player%", RunAs.PLAYER, List.of());

        dispatcher.dispatch(player.proxy, button, Map.of("player", ".Ñoño"));

        assertEquals(
                List.of("starterpack give .Ñoño"),
                runner.ran,
                "non-ASCII gamertags are legitimate Bedrock players and must be substituted normally");
    }

    @Test
    void elevatedGrantIsRemovedEvenWhenTheDispatchedCommandThrows() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("worldcrud"));
        RecordingRunner runner = new RecordingRunner();
        runner.toThrow = new IllegalStateException("boom");
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer("Steve");
        Button button = commandButton(
                "main.0", "worldcrud tp %player% spawn", RunAs.PLAYER_ELEVATED, List.of("worldcrud.teleport"));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> dispatcher.dispatch(player.proxy, button, Map.of("player", "Steve")));
        assertEquals("boom", thrown.getMessage());

        assertTrue(
                player.active.isEmpty(),
                "the elevated attachment must be removed even though the dispatched command threw");
        assertEquals(1, player.removed.size(), "removeAttachment must be called exactly once");
    }

    @Test
    void elevatedGrantIsRemovedAfterASuccessfulCommand() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("worldcrud"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer("Steve");
        Button button = commandButton(
                "main.0", "worldcrud tp %player% spawn", RunAs.PLAYER_ELEVATED, List.of("worldcrud.teleport"));

        dispatcher.dispatch(player.proxy, button, Map.of("player", "Steve"));

        assertEquals(List.of("worldcrud tp Steve spawn"), runner.ran);
        assertTrue(player.active.isEmpty(), "the elevated attachment must not outlive a successful command");
        assertEquals(1, player.removed.size());
    }

    @Test
    void nonRunCommandActionIsLoggedAndDoesNotThrow() {
        CommandAllowlist allowlist = new CommandAllowlist(Set.of("starterpack"));
        RecordingRunner runner = new RecordingRunner();
        ActionDispatcher dispatcher = new ActionDispatcher(fakePlugin(), allowlist, runner);
        FakePlayer player = new FakePlayer("Steve");
        Button button = new Button(
                "main.0", "Open", null, null, new Action.OpenMenu("other"), RunAs.PLAYER, List.of(), Duration.ZERO,
                false, false);

        assertDoesNotThrow(() -> dispatcher.dispatch(player.proxy, button, Map.of()));

        assertTrue(runner.ran.isEmpty());
    }
}
