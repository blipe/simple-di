package io.github.simpledi;

import java.util.Objects;

/** Opaque, non-reversible fingerprint of one fully resolved configuration generation. */
public record ConfigurationRevision(String sha256) {
    public ConfigurationRevision {
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
    }

    /** Short display identifier suitable for logs, never for equality checks. */
    public String shortId() {
        return sha256.substring(0, 12);
    }
}
