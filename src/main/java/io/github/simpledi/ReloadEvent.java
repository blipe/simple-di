package io.github.simpledi;

/** Immutable reload lifecycle event. No bean instances or configuration values are exposed. */
public record ReloadEvent(
        Kind kind,
        long previousGeneration,
        long candidateGeneration,
        ConfigurationRevision revision,
        long durationNanos,
        int activeLeases,
        ReloadFailure failure) {

    public enum Kind {
        PREPARE_STARTED,
        PREPARE_SUCCEEDED,
        PREPARE_FAILED,
        CANDIDATE_STARTING,
        CANDIDATE_STARTED,
        CANDIDATE_FAILED,
        HANDOFF_STARTING,
        HANDOFF_COMPLETED,
        HANDOFF_FAILED,
        GENERATION_ACTIVATED,
        GENERATION_RETIRED,
        DRAIN_COMPLETED,
        DRAIN_TIMED_OUT,
        GENERATION_DESTROY_FAILED,
        MANAGER_CLOSING,
        MANAGER_CLOSED
    }

    public ReloadEvent {
        if (kind == null) throw new NullPointerException("kind");
        if (durationNanos < 0) throw new IllegalArgumentException("durationNanos");
        if (activeLeases < 0) throw new IllegalArgumentException("activeLeases");
    }
}
