/*
 * Pizza - touch-friendly in-game menu that runs everyday xpfarm tasks for younger players.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.pizza.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the two bundled resources the config-refresh scheme needs: {@code config-hashes.txt} (the
 * SHA-256 of every default {@code config.yml} ever shipped, one hex hash per line) and the current
 * default {@code config.yml} itself. Both are classpath resources, so this reads what is actually
 * packaged in the jar, not a working-tree file.
 */
public final class ConfigHashes {

    private ConfigHashes() {}

    public static Set<String> knownHashes() {
        try (InputStream in = resource("config-hashes.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("could not read config-hashes.txt", e);
        }
    }

    public static byte[] currentDefaultBytes() {
        try (InputStream in = resource("config.yml")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read bundled config.yml", e);
        }
    }

    private static InputStream resource(String name) {
        InputStream in = ConfigHashes.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("bundled resource missing: " + name);
        }
        return in;
    }
}
