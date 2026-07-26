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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.xpfarm.pizza.render.BedrockBridge;

/**
 * The invite trigger must funnel through the same single resolution path Task 5 guards, whether it
 * arrives from a Bedrock form or a Java command. These tests pin the routing decision — Bedrock
 * form vs. Java fallback — without a live server.
 *
 * <h2>Why two kinds of tests live here</h2>
 *
 * <p>{@link #aBedrockInviteeIsPromptedByFormAndNotByChatFallback} and {@link
 * #aJavaInviteeFallsBackWhenTheFormCannotBeShown} exercise the fake {@link BedrockBridge}
 * directly — useful as a pinned contract for what {@code askConsent}'s return value is supposed to
 * mean, but on their own they prove nothing about {@link ConsentService}: a fake returning what it
 * was scripted to return is not wiring coverage. {@link
 * #bedrockInviteeGetsOnlyTheFormNoJavaChatFallback} and {@link
 * #javaInviteeGetsAChatFallbackAndAResolvableInvite} close that gap by driving the real {@link
 * ConsentService#invite} — the actual method a Bedrock or Java invite goes through in production —
 * and observing its effect: whether the chat fallback message was sent, and whether the invite
 * ends up registered where {@code /pizza accept}/{@code /pizza decline} would find it.
 *
 * <h2>What is faked, and why</h2>
 *
 * <p>{@link ConsentService#invite} is Bukkit-coupled in two ways that have nothing to do with the
 * wiring decision under test: it calls {@code Bukkit.getScheduler().runTaskLater(...)}
 * unconditionally to schedule the timeout, before the {@code askConsent} call is ever reached, and
 * it operates on {@link Player} instances. Neither works outside a live server. Rather than build a
 * elaborate fake Bukkit server, the minimum needed is faked directly:
 *
 * <ul>
 *   <li>{@link ConsentService.TimeoutScheduler} — a test-only seam added to {@link ConsentService}
 *       for exactly this purpose (mirrors the existing {@code CommandRunner} seam on {@code
 *       ActionDispatcher}). The fake never actually schedules anything, which is fine: the timeout
 *       firing is not what these tests are about.
 *   <li>{@link Player} and {@link Plugin} — both are large Bukkit interfaces (100+ inherited
 *       abstract methods) that cannot practically be hand-implemented directly. A {@link
 *       Proxy}-backed fake is used instead: still hand-written (a single {@link InvocationHandler}
 *       visible in this file, no mocking library), but able to answer the handful of methods {@code
 *       invite()} actually calls (a {@link Player}'s {@code getUniqueId}/{@code getName}/{@code
 *       sendMessage}) while throwing on anything unexpected, which would surface as a test failure
 *       if {@code invite()} ever grows a new Bukkit touch point these fakes do not account for.
 * </ul>
 *
 * <h2>What remains gate-7a-only</h2>
 *
 * <p>{@link ConsentService#settle} — reached from {@link ConsentService#accept}, {@link
 * ConsentService#decline}, {@link ConsentService#forget}, a fired timeout, or a Bedrock form's own
 * handlers — routes through {@link ConsentService#onMainThread}, which calls {@code
 * Bukkit.isPrimaryThread()} unconditionally on every call, with no seam. That makes {@code settle}
 * (and therefore {@code accept}/{@code decline}'s full effect, {@code announce}'s teleport/message
 * side effects, and the timeout actually firing) impossible to drive in this test file without
 * either a live server or a second Bukkit seam this task does not add. {@link
 * #javaInviteeGetsAChatFallbackAndAResolvableInvite} therefore confirms the invite was registered
 * via {@link ConsentService#hasPendingInvite}, a plain map read, rather than by actually calling
 * {@code decline(uuid)} and observing the outcome — that remains a gate 7a / RCON check, alongside
 * the CLOSED-on-quit path, the ACCEPTED teleport, and every other {@code announce} branch, none of
 * which were unit-testable before this task either.
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

    /**
     * Drives the real {@link ConsentService#invite}, not the fake bridge in isolation: when {@code
     * askConsent} reports the invitee was shown a form, {@code invite()} must not also send the
     * Java chat fallback message — the form's own Accept/Decline buttons are the only mechanism,
     * not a subsequent {@code /pizza accept}.
     */
    @Test
    void bedrockInviteeGetsOnlyTheFormNoJavaChatFallback() {
        AtomicInteger inviterMessages = new AtomicInteger();
        AtomicInteger inviteeMessages = new AtomicInteger();
        AtomicBoolean askConsentCalled = new AtomicBoolean(false);

        UUID inviteeId = UUID.randomUUID();
        Player inviter = fakePlayer(UUID.randomUUID(), "Steve", inviterMessages);
        Player invitee = fakePlayer(inviteeId, "Alex", inviteeMessages);

        BedrockBridge bridge = new BedrockBridge() {
            @Override public boolean isBedrock(UUID player) { return true; }
            @Override public boolean isAvailable() { return true; }
            @Override public boolean askConsent(UUID player, String title, String content,
                    Runnable onAccept, Runnable onDecline, Runnable onClose) {
                askConsentCalled.set(true);
                return true;
            }
        };

        ConsentService service = new ConsentService(
                fakePlugin(), Duration.ofSeconds(60), bridge, noopRunner(), noopScheduler());
        service.invite(inviter, invitee, new ConsentAction.Travel("creative"), "Travel Invite", "come along");

        assertTrue(askConsentCalled.get(), "invite() must actually call BedrockBridge.askConsent");
        assertEquals(0, inviteeMessages.get(),
                "a Bedrock invitee shown a form must not also receive the Java chat fallback message");
        assertEquals(1, inviterMessages.get(), "the inviter still gets their own confirmation message");
        assertTrue(service.hasPendingInvite(inviteeId),
                "the invite is registered regardless of which platform delivers the prompt");
    }

    /**
     * Drives the real {@link ConsentService#invite}: when {@code askConsent} reports the invitee
     * cannot be shown a form, {@code invite()} must both send the Java chat fallback prompt and
     * leave the invite registered so a later {@code /pizza accept}/{@code /pizza decline} (routed
     * through {@link ConsentService#accept}/{@link ConsentService#decline}) has something to
     * resolve. Whether {@code accept}/{@code decline} themselves complete successfully is not
     * checked here — see the class-level javadoc's "what remains gate-7a-only" note.
     */
    @Test
    void javaInviteeGetsAChatFallbackAndAResolvableInvite() {
        AtomicInteger inviterMessages = new AtomicInteger();
        AtomicInteger inviteeMessages = new AtomicInteger();

        UUID inviteeId = UUID.randomUUID();
        Player inviter = fakePlayer(UUID.randomUUID(), "Steve", inviterMessages);
        Player invitee = fakePlayer(inviteeId, "Alex", inviteeMessages);

        BedrockBridge bridge = new BedrockBridge() {
            @Override public boolean isBedrock(UUID player) { return false; }
            @Override public boolean isAvailable() { return false; }
            @Override public boolean askConsent(UUID player, String title, String content,
                    Runnable onAccept, Runnable onDecline, Runnable onClose) {
                return false;
            }
        };

        ConsentService service = new ConsentService(
                fakePlugin(), Duration.ofSeconds(60), bridge, noopRunner(), noopScheduler());
        service.invite(inviter, invitee, new ConsentAction.Travel("creative"), "Travel Invite", "come along");

        assertEquals(1, inviteeMessages.get(), "a Java invitee must receive the chat fallback prompt");
        assertTrue(service.hasPendingInvite(inviteeId),
                "invite() must register the invite so a later /pizza accept or /pizza decline "
                        + "(ConsentService.accept/decline) has something to resolve");
    }

    /**
     * Drives the real {@link ConsentService#invite} with a {@link ConsentAction.RunCommand}: the new
     * action variant must register exactly like a {@link ConsentAction.Travel} invite does, so a
     * later {@code accept} has something to resolve and the injected {@link ConsentedCommandRunner}
     * is the thing that would fire.
     *
     * <p><b>Why the runner invocation itself is not asserted here.</b> The runner is only ever
     * called from {@link ConsentService#announce}'s {@code ACCEPTED -> RunCommand} branch, which runs
     * inside {@link ConsentService}'s {@code onMainThread(...)} — and {@code onMainThread} calls
     * {@code Bukkit.isPrimaryThread()} unconditionally, with no seam, which NPEs outside a live
     * server <em>before</em> the branch (and therefore the runner) is ever reached. This is the
     * exact same limitation the class-level javadoc documents for the ACCEPTED teleport and every
     * other {@code announce} branch: they remain a gate-7a / RCON check, not unit-testable in this
     * file without a live server or a second Bukkit seam this task does not add. This test therefore
     * mirrors {@link #javaInviteeGetsAChatFallbackAndAResolvableInvite} — it confirms the RunCommand
     * invite is registered and resolvable — rather than standing up a fragile fake {@code
     * Bukkit.server} (which, in surefire's single reused JVM, would leak into every other test
     * class). That the runner actually fires on accept is verified at runtime, gate 7a.
     */
    @Test
    void runCommandInviteIsRegisteredAndResolvable() {
        ConsentedCommandRunner runner = (invitee, cmd) -> true;

        UUID inviteeId = UUID.randomUUID();
        Player inviter = fakePlayer(UUID.randomUUID(), "Steve", new AtomicInteger());
        Player invitee = fakePlayer(inviteeId, "Alex", new AtomicInteger());

        BedrockBridge bridge = new BedrockBridge() {
            @Override public boolean isBedrock(UUID player) { return false; }
            @Override public boolean isAvailable() { return false; }
            @Override public boolean askConsent(UUID player, String title, String content,
                    Runnable onAccept, Runnable onDecline, Runnable onClose) {
                return false;
            }
        };

        ConsentService service = new ConsentService(
                fakePlugin(), Duration.ofSeconds(60), bridge, runner, noopScheduler());
        service.invite(inviter, invitee,
                new ConsentAction.RunCommand("curse trigger ZP25 %target%"), "t", "c");

        assertTrue(service.hasPendingInvite(inviteeId),
                "a RunCommand invite must register just like a Travel invite so a later accept "
                        + "(ConsentService.accept, which fires the injected runner in announce) has "
                        + "something to resolve");
    }

    /**
     * A hand-written {@link Proxy}-backed fake {@link Player}: answers {@code getUniqueId}, {@code
     * getName}, and {@code sendMessage} (counted in {@code messagesSent}); anything else throws,
     * so a future change to {@code invite()} that starts calling a different {@link Player} method
     * fails this test loudly instead of silently returning {@code null}/{@code 0}/{@code false}.
     */
    private static Player fakePlayer(UUID id, String name, AtomicInteger messagesSent) {
        InvocationHandler handler = (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "sendMessage" -> {
                    messagesSent.incrementAndGet();
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakePlayer{" + name + "}";
                default -> throw new UnsupportedOperationException(
                        "ConsentService.invite() should only call Player#getUniqueId/getName/"
                                + "sendMessage; it also called Player#" + method.getName());
            };
        };
        return (Player) Proxy.newProxyInstance(
                InviteWiringTest.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    /**
     * A hand-written {@link Proxy}-backed fake {@link Plugin}. {@code invite()} never calls a
     * {@link Plugin} method directly (it only passes the reference through to {@link
     * ConsentService.TimeoutScheduler}, which the fake below ignores), so every method throws —
     * this fake exists only to satisfy {@link ConsentService}'s {@code Objects.requireNonNull}.
     */
    private static Plugin fakePlugin() {
        InvocationHandler handler = (proxy, method, args) -> {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakePlugin";
                default -> throw new UnsupportedOperationException(
                        "this test's fake TimeoutScheduler never touches Plugin; Plugin#"
                                + method.getName() + " should not have been called");
            };
        };
        return (Plugin) Proxy.newProxyInstance(
                InviteWiringTest.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    /**
     * A {@link ConsentService.TimeoutScheduler} that never actually schedules anything and never
     * touches {@code Bukkit}. The timeout firing is not part of what these tests check.
     */
    /** A {@link ConsentedCommandRunner} that never runs anything; used where the accept path is not exercised. */
    private static ConsentedCommandRunner noopRunner() {
        return (invitee, command) -> false;
    }

    private static ConsentService.TimeoutScheduler noopScheduler() {
        return (plugin, task, delayTicks) -> new BukkitTask() {
            @Override public int getTaskId() { return 0; }
            @Override public Plugin getOwner() { return null; }
            @Override public boolean isSync() { return true; }
            @Override public boolean isCancelled() { return false; }
            @Override public void cancel() {}
        };
    }
}
