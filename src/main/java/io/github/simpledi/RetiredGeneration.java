package io.github.simpledi;

import java.time.Instant;
import java.util.List;

/** Value-only diagnostics for a generation that is draining or has been destroyed. */
public record RetiredGeneration(
        long generation,
        ConfigurationRevision revision,
        String state,
        int activeLeases,
        Instant retiredAt,
        Instant closedAt,
        String destructionFailure,
        List<String> leaseDiagnostics) {
    public RetiredGeneration {
        leaseDiagnostics = List.copyOf(leaseDiagnostics);
    }
}
