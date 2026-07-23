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
import org.bukkit.plugin.Plugin;

/**
 * The single call site allowed to construct {@link BedrockRenderer}, gated on {@link
 * BedrockBridge#isAvailable()} so that class — and therefore Cumulus — is only ever linked when
 * Floodgate is actually present.
 *
 * <p>JVM class loading/linking is lazy: a class referenced by a method is only resolved when the
 * bytecode that names it actually executes. {@link #create} relies on exactly that — the {@code
 * new BedrockRenderer(...)} branch below is textually present but never executes when {@code
 * bridge.isAvailable()} is {@code false}, so {@link BedrockRenderer} (and transitively its Cumulus
 * {@code Form} supertype) is never loaded, verified, or resolved on a server without Floodgate.
 * Before this class existed, {@code PizzaPlugin.onEnable} called {@code new BedrockRenderer(...)}
 * unconditionally, which force-loaded Cumulus on every server and threw {@code
 * NoClassDefFoundError} — and therefore failed {@code onEnable} — whenever Floodgate (and so
 * Cumulus) was absent.
 *
 * <p>Extracted out of {@code PizzaPlugin.onEnable} into its own tiny, dependency-light, static
 * method specifically so a classloading test can exercise the guarded branch directly (see {@code
 * BedrockRendererGuardClassloadingTest}) without needing to construct a whole {@code JavaPlugin}.
 */
public final class BedrockRendererFactory {

    private BedrockRendererFactory() {}

    /**
     * @return a real {@link BedrockRenderer} when {@code bridge.isAvailable()}; {@code null}
     *     otherwise. {@code null}, not some no-op {@link MenuRenderer}, because {@code
     *     MenuService.rendererFor} never routes to the Bedrock slot unless the bridge itself
     *     reports availability — a Bedrock player cannot exist on a Floodgate-absent server in the
     *     first place, so the slot is provably unreachable in that case, and {@code
     *     MenuService.rendererFor} still falls back to the chest renderer defensively even if it
     *     is ever asked to route to a null Bedrock slot.
     */
    public static MenuRenderer create(Plugin plugin, ButtonSink sink, BedrockBridge bridge) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(bridge, "bridge");
        return bridge.isAvailable() ? new BedrockRenderer(plugin, sink, bridge) : null;
    }
}
