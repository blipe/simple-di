package io.github.simpledi;

/** Pins one context generation until closed. Closing is idempotent. */
public interface ContextLease extends AutoCloseable {
    long generation();
    BeanContext context();
    boolean isClosed();
    /** Optional acquisition trace when lease diagnostics are enabled. */
    String acquisitionTrace();

    default <T> T require(String id, Class<T> type) {
        return context().require(id, type);
    }

    default Object require(String id) {
        return context().require(id);
    }

    @Override
    void close();
}
