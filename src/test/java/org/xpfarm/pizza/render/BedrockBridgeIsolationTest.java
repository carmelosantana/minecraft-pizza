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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The plugin must load and enable on a server without Floodgate. That holds only while every
 * reference to a Geyser or Cumulus type stays inside a class the guarded factory never
 * instantiates in that case. CrossplatForms shipped exactly this bug — an empty handler whose
 * own method signature named a Cumulus type — and worked around it with a comment.
 */
final class BedrockBridgeIsolationTest {

    private static final List<String> QUARANTINED =
            List.of("FloodgateBridge.java", "BedrockRenderer.java");

    private static Stream<Path> sources() throws IOException {
        return Files.walk(Path.of("src", "main", "java"))
                .filter(p -> p.toString().endsWith(".java"));
    }

    @Test
    void onlyQuarantinedClassesReferenceGeyser() throws IOException {
        try (Stream<Path> sources = sources()) {
            sources.filter(p -> !QUARANTINED.contains(p.getFileName().toString()))
                    .forEach(path -> {
                        String body = read(path);
                        assertFalse(body.contains("org.geysermc"),
                                path + " references org.geysermc; only " + QUARANTINED
                                        + " may, or the plugin breaks without Floodgate");
                    });
        }
    }

    @Test
    void quarantinedClassesExistSoTheGuardIsNotVacuous() throws IOException {
        try (Stream<Path> sources = sources()) {
            List<String> names = sources.map(p -> p.getFileName().toString()).toList();
            QUARANTINED.forEach(q -> assertTrue(names.contains(q), q + " is missing"));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
