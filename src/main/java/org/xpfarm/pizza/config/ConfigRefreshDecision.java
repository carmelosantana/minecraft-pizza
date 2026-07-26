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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;

/**
 * Pure decision: given the on-disk {@code config.yml} bytes, the bundled default bytes, and the set
 * of SHA-256 hex hashes of every default the plugin has ever shipped, decide whether the on-disk
 * file may be safely replaced with the current default. Bukkit-free and fully unit-testable.
 */
public final class ConfigRefreshDecision {

    public enum Result {
        /** On-disk config already equals the current default. */
        UP_TO_DATE,
        /** On-disk config is a known older default — provably unmodified, safe to replace. */
        REFRESH,
        /** On-disk config matches no known default — the admin edited it; never overwrite. */
        CUSTOMIZED
    }

    private ConfigRefreshDecision() {}

    public static Result decide(byte[] onDisk, byte[] currentDefault, Set<String> knownHashes) {
        Objects.requireNonNull(onDisk, "onDisk");
        Objects.requireNonNull(currentDefault, "currentDefault");
        Objects.requireNonNull(knownHashes, "knownHashes");
        String disk = sha256Hex(onDisk);
        String current = sha256Hex(currentDefault);
        if (disk.equals(current)) {
            return Result.UP_TO_DATE;
        }
        if (knownHashes.contains(disk)) {
            return Result.REFRESH;
        }
        return Result.CUSTOMIZED;
    }

    public static String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required algorithm on every conformant JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
