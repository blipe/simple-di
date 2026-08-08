package io.github.simpledi;

/** Result of preparing or atomically activating a context generation. */
public record ReloadResult(
        Status status,
        long previousGeneration,
        long activeGeneration,
        ConfigurationRevision revision,
        ValidationResult validation,
        ConfigurationDiff diff,
        ReloadFailure failure) {

    public enum Status {
        UNCHANGED,
        ACTIVATED,
        VALIDATION_FAILED,
        STARTUP_FAILED,
        HANDOFF_FAILED,
        STALE_CANDIDATE,
        BUSY,
        MANAGER_CLOSED
    }

    public boolean activated() {
        return status == Status.ACTIVATED;
    }
}
