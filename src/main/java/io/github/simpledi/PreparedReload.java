package io.github.simpledi;

/** Fully validated and, when changed, fully started candidate awaiting atomic publication. */
public interface PreparedReload extends AutoCloseable {
    long baseGeneration();
    ConfigurationRevision revision();
    ValidationResult validation();
    ConfigurationDiff diff();
    boolean changed();
    ReloadResult activate();
    boolean isClosed();
    @Override void close();
}
