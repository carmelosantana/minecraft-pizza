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

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tracks in-memory, un-persisted travel invites and is the only place in the plugin that may
 * teleport a player as a result of one. Nobody is ever moved without their invite having resolved
 * to {@link InviteOutcome#ACCEPTED} — not the inviter, not staff, nobody.
 *
 * <p>This class is deliberately thin: all of the safety-critical decision logic — "did exactly one
 * outcome win" — lives in {@link PendingInvite}, which is pure and fully unit-tested. What is left
 * here is Bukkit glue: scheduling the timeout on the plugin's scheduler, looking up {@link Player}
 * instances by {@link UUID} (which may legitimately come back {@code null} if the player logged
 * off between the triggering event and this code running), and performing the teleport/notify
 * side effects that must happen on the main server thread.
 *
 * <p><b>Threading.</b> {@link PendingInvite#resolve(InviteOutcome)} is safe to call from any
 * thread. Everything that touches a {@link Player} — sending a message, teleporting — is not, and
 * is routed through {@link #onMainThread(Runnable)} so it always runs on the main server thread
 * even if the resolution that triggered it happened elsewhere (a scheduler thread for the timeout,
 * or a packet-handling thread for a click response). {@link #invite(Player, Player, String)} and
 * {@link #forget(UUID)} themselves are expected to be called from the main thread only, consistent
 * with {@link Player} being a main-thread-only Bukkit type in the first place; the race this class
 * defends against is exclusively between the async resolution paths (accept, decline, timeout,
 * supersede, close), never between two concurrent calls to {@code invite}.
 *
 * <p><b>Wiring note.</b> This task builds the consent state machine, not the accept/decline
 * presentation layer. {@link #accept(UUID)} and {@link #decline(UUID)} are the public seam a
 * future command or form handler calls once a player actually taps a response; nothing in this
 * plugin's current scope invokes them yet.
 */
public final class ConsentService {

    private final Plugin plugin;
    private final Duration timeout;

    /** At most one pending invite per invitee, keyed by the invitee's UUID. */
    private final Map<UUID, PendingInvite> pendingByInvitee = new ConcurrentHashMap<>();

    /** The scheduled timeout task for each pending invite, keyed the same way. */
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();

    public ConsentService(Plugin plugin, Duration timeout) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Invites {@code invitee} to travel to {@code world} at {@code inviter}'s side. If {@code
     * invitee} already has a pending invite, that older invite is resolved as {@link
     * InviteOutcome#SUPERSEDED} first — its own inviter is told it was replaced, not that they
     * were declined.
     */
    public void invite(Player inviter, Player invitee, String world) {
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        Objects.requireNonNull(world, "world");

        UUID invId = invitee.getUniqueId();

        PendingInvite superseded = pendingByInvitee.get(invId);
        if (superseded != null) {
            settle(superseded, InviteOutcome.SUPERSEDED);
        }

        PendingInvite created = new PendingInvite(inviter.getUniqueId(), invId, world);
        pendingByInvitee.put(invId, created);

        long delayTicks = Math.max(1L, timeout.toMillis() / 50L);
        BukkitTask task = Bukkit.getScheduler()
                .runTaskLater(plugin, () -> settle(created, InviteOutcome.TIMED_OUT), delayTicks);
        timeoutTasks.put(invId, task);

        invitee.sendMessage(Component.text(
                inviter.getName() + " invited you to travel to '" + world + "'.",
                NamedTextColor.YELLOW));
        inviter.sendMessage(Component.text(
                "Invite sent to " + invitee.getName() + ".", NamedTextColor.GRAY));
    }

    /** Accepts {@code invitee}'s current pending invite, if it still has one. */
    public void accept(UUID invitee) {
        PendingInvite invite = pendingByInvitee.get(Objects.requireNonNull(invitee, "invitee"));
        if (invite != null) {
            settle(invite, InviteOutcome.ACCEPTED);
        }
    }

    /** Declines {@code invitee}'s current pending invite, if it still has one. */
    public void decline(UUID invitee) {
        PendingInvite invite = pendingByInvitee.get(Objects.requireNonNull(invitee, "invitee"));
        if (invite != null) {
            settle(invite, InviteOutcome.DECLINED);
        }
    }

    /**
     * Called on quit. Drops {@code player}'s pending invite whether they hold it as the invitee or
     * sent it as the inviter, cancelling its timeout task and resolving it to {@link
     * InviteOutcome#CLOSED} so any response that arrives after this point (a click on a stale
     * message, for example) is a guaranteed no-op rather than acting on a departed player.
     */
    public void forget(UUID player) {
        Objects.requireNonNull(player, "player");

        PendingInvite asInvitee = pendingByInvitee.remove(player);
        if (asInvitee != null) {
            cancelTimeout(player);
            asInvitee.resolve(InviteOutcome.CLOSED);
        }

        for (PendingInvite invite : pendingByInvitee.values()) {
            if (invite.inviter().equals(player) && pendingByInvitee.remove(invite.invitee(), invite)) {
                cancelTimeout(invite.invitee());
                invite.resolve(InviteOutcome.CLOSED);
            }
        }
    }

    /**
     * Resolves {@code invite} to {@code outcome}. If this call wins the race, the invite is
     * removed from {@link #pendingByInvitee} (only if it is still the current invite for that
     * invitee — a superseded invite must never evict the newer one that replaced it), its timeout
     * task is cancelled, and the outcome is announced on the main thread. If this call loses the
     * race (someone else already resolved this exact invite), it is a complete no-op.
     */
    private void settle(PendingInvite invite, InviteOutcome outcome) {
        if (!invite.resolve(outcome)) {
            return;
        }
        UUID invId = invite.invitee();
        pendingByInvitee.remove(invId, invite);
        cancelTimeout(invId);
        onMainThread(() -> announce(invite, outcome));
    }

    private void cancelTimeout(UUID invitee) {
        BukkitTask task = timeoutTasks.remove(invitee);
        if (task != null) {
            task.cancel();
        }
    }

    private void onMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    /**
     * Player-facing side effects of a settled invite. Every branch null-checks {@link
     * Bukkit#getPlayer(UUID)} before touching a player — both {@code inviter} and {@code invitee}
     * may have disconnected between the triggering event and this running. Only {@link
     * InviteOutcome#ACCEPTED}, and only when both parties are still online, results in a teleport.
     */
    private void announce(PendingInvite invite, InviteOutcome outcome) {
        Player inviter = Bukkit.getPlayer(invite.inviter());
        Player invitee = Bukkit.getPlayer(invite.invitee());

        switch (outcome) {
            case ACCEPTED -> {
                if (inviter != null && invitee != null) {
                    invitee.teleport(inviter.getLocation());
                    invitee.sendMessage(Component.text(
                            "You joined " + inviter.getName() + ".", NamedTextColor.GREEN));
                    inviter.sendMessage(Component.text(
                            invitee.getName() + " joined you.", NamedTextColor.GREEN));
                }
            }
            case DECLINED -> {
                if (inviter != null) {
                    String name = invitee != null ? invitee.getName() : "The invited player";
                    inviter.sendMessage(Component.text(name + " declined your invite.", NamedTextColor.RED));
                }
            }
            case TIMED_OUT -> {
                if (inviter != null) {
                    String name = invitee != null ? invitee.getName() : "The invited player";
                    inviter.sendMessage(Component.text(
                            "Your invite to " + name + " expired.", NamedTextColor.GRAY));
                }
            }
            case SUPERSEDED -> {
                if (inviter != null) {
                    String name = invitee != null ? invitee.getName() : "the invited player";
                    inviter.sendMessage(Component.text(
                            "Your invite to " + name + " was replaced by a newer invite.",
                            NamedTextColor.GRAY));
                }
            }
            case CLOSED -> {
                // A party quit before responding. Nobody left online to notify meaningfully.
            }
        }
    }
}
