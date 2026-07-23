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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * Real-classloading proof for C1: {@code PizzaPlugin.onEnable} must not force-load Cumulus on a
 * server without Floodgate. {@link BedrockBridgeIsolationTest} only greps source text and cannot
 * catch this — it would happily pass even if {@code BedrockRenderer} were constructed
 * unconditionally, because the import still lives inside the one file the grep quarantines.
 *
 * <p><b>Why a custom classloader, not simply Floodgate/Cumulus off the classpath.</b> Cumulus and
 * Floodgate are declared {@code provided} scope in {@code pom.xml}, which Maven puts on the test
 * classpath as well as the compile classpath (that is what lets {@link BedrockRenderer} and {@link
 * FloodgateBridge} compile at all here). There is no Maven scope that keeps a dependency off the
 * test classpath while still compiling the main sources that use it, so a normal JUnit test cannot
 * exercise "Cumulus genuinely absent". Instead, {@link BlockingClassLoader} below makes every
 * {@code org.geysermc.*} class name unresolvable, which is functionally identical — from the
 * perspective of anything loaded through it — to that package being absent from the classpath
 * entirely. {@link BedrockRenderer} and {@link BedrockRendererFactory} are redefined fresh under
 * that loader (class linking/verification status is tracked per-classloader, so whatever already
 * happened under the ordinary test classloader has no bearing here); every other class — Bukkit
 * API types, this plugin's other classes — is delegated to the ordinary parent loader, since
 * {@code BedrockRenderer} and {@code FloodgateBridge} are the only two classes in the whole plugin
 * that ever reference Geyser/Cumulus (enforced separately by {@link BedrockBridgeIsolationTest}).
 *
 * <p>{@link #directBedrockRendererConstructionFailsUnderTheBlockingLoader()} is the control: it
 * proves the harness genuinely reproduces C1's {@code NoClassDefFoundError} when the guard is
 * bypassed (unconditional construction), so a pass on the guarded path below cannot be a vacuous
 * "the harness never actually blocks anything". {@link
 * #guardedFactoryCompletesWithoutLinkingBedrockRendererWhenBridgeIsUnavailable()} then proves the
 * production guard — {@link BedrockRendererFactory#create}, called from {@code
 * PizzaPlugin.onEnable} — never trips that failure when {@code bridge.isAvailable()} is {@code
 * false}, which is exactly the Floodgate-absent condition.
 */
final class BedrockRendererGuardClassloadingTest {

    @Test
    void directBedrockRendererConstructionFailsUnderTheBlockingLoader() {
        // Deliberately not narrowed to a single step (forName/getConstructor/newInstance): on this
        // JDK, merely reflecting on BedrockRenderer's declared constructors under the blocking
        // loader is enough to trigger linking of the whole class — including the private methods
        // that reference Cumulus types — so NoClassDefFoundError can surface from getConstructor()
        // itself rather than from newInstance(). Either way is an equally valid reproduction of
        // C1's original failure (new BedrockRenderer(...) throwing at the moment it links), so this
        // test asserts on the outcome of the whole sequence rather than pinning one exact step.
        NoClassDefFoundError thrown = assertThrows(
                NoClassDefFoundError.class,
                () -> {
                    BlockingClassLoader blocked = new BlockingClassLoader(getClass().getClassLoader());
                    Class<?> rendererClass =
                            Class.forName("org.xpfarm.pizza.render.BedrockRenderer", false, blocked);
                    Class<?> sinkClass =
                            Class.forName("org.xpfarm.pizza.render.ButtonSink", false, blocked);
                    Class<?> bridgeClass =
                            Class.forName("org.xpfarm.pizza.render.BedrockBridge", false, blocked);
                    var constructor = rendererClass.getConstructor(Plugin.class, sinkClass, bridgeClass);
                    constructor.newInstance(fakePlugin(), fakeSink(), NoopBridge.INSTANCE);
                },
                "constructing BedrockRenderer under a loader that cannot resolve org.geysermc.* "
                        + "must fail exactly as it does on a real Floodgate-absent server — if this "
                        + "assertion itself fails, the blocking loader is not actually isolating "
                        + "anything and the other test in this class proves nothing");
        assertTrue(
                thrown.getMessage() != null && thrown.getMessage().contains("geysermc"),
                "expected the failure to name an org.geysermc class, got: " + thrown.getMessage());
    }

    @Test
    void guardedFactoryCompletesWithoutLinkingBedrockRendererWhenBridgeIsUnavailable() throws Exception {
        BlockingClassLoader blocked = new BlockingClassLoader(getClass().getClassLoader());
        Class<?> factoryClass =
                Class.forName("org.xpfarm.pizza.render.BedrockRendererFactory", true, blocked);
        Class<?> sinkClass = Class.forName("org.xpfarm.pizza.render.ButtonSink", false, blocked);
        Class<?> bridgeClass = Class.forName("org.xpfarm.pizza.render.BedrockBridge", false, blocked);
        Method create = factoryClass.getMethod("create", Plugin.class, sinkClass, bridgeClass);

        // NoopBridge is exactly what BedrockBridge.create returns on a Floodgate-absent server
        // (Bukkit.getPluginManager().isPluginEnabled("floodgate") == false); its isAvailable()
        // always returns false, which is the guard BedrockRendererFactory.create checks.
        Object result = assertDoesNotThrow(
                () -> create.invoke(null, fakePlugin(), fakeSink(), NoopBridge.INSTANCE),
                "BedrockRendererFactory.create must complete without linking BedrockRenderer (and "
                        + "therefore without touching org.geysermc.*) when the bridge reports "
                        + "Floodgate is unavailable — this is the exact guard that fixes C1");
        assertNull(
                result,
                "no BedrockRenderer must be constructed at all when Floodgate is unavailable");
    }

    private static Plugin fakePlugin() {
        return (Plugin) Proxy.newProxyInstance(
                BedrockRendererGuardClassloadingTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getLogger("bedrock-renderer-guard-test");
                    case "toString" -> "fake-plugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "fake plugin does not implement " + method.getName());
                });
    }

    /** A minimal, dependency-free {@code ButtonSink} — never actually invoked in these tests. */
    private static Object fakeSink() {
        return (ButtonSink) (player, menu, button) -> {
            throw new AssertionError("not expected to be called in this test");
        };
    }

    /**
     * Loads every class itself except {@code org.geysermc.*} (unresolvable, simulating Floodgate
     * and Cumulus being absent from the server entirely) and {@code BedrockRenderer}/{@code
     * BedrockRendererFactory} (redefined fresh under this loader, rather than reused from the
     * parent, so their link/verify status is independent of anything the ordinary test classloader
     * already did with them). Everything else — {@code org.bukkit.*}, the rest of {@code
     * org.xpfarm.pizza.*}, the JDK — is delegated to the parent loader unchanged, so instances
     * created by test code with the parent's classes (a {@link Proxy} implementing {@code Plugin},
     * {@link NoopBridge#INSTANCE}, and so on) remain assignable to the parameter types this loader
     * resolves.
     */
    private static final class BlockingClassLoader extends ClassLoader {

        private static final Set<String> OWNED = Set.of(
                "org.xpfarm.pizza.render.BedrockRenderer",
                "org.xpfarm.pizza.render.BedrockRendererFactory");

        BlockingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    if (name.startsWith("org.geysermc.")) {
                        throw new ClassNotFoundException(
                                "blocked by BlockingClassLoader to simulate a Floodgate/Cumulus-"
                                        + "absent server: " + name);
                    }
                    found = OWNED.contains(name) ? findClass(name) : getParent().loadClass(name);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String resourcePath = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
