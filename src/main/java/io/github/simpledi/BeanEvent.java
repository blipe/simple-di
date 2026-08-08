package io.github.simpledi;

/** A synchronous, immutable configuration or container lifecycle event. Durations are monotonic nanoseconds. */
public record BeanEvent(
        Kind kind,
        String beanId,
        SourceLocation location,
        long durationNanos,
        Throwable failure) {

    public enum Kind {
        CONFIG_PARSING,
        CONFIG_PARSED,
        CONFIG_FAILED,
        GRAPH_COMPILING,
        GRAPH_COMPILED,
        GRAPH_FAILED,
        CONTEXT_STARTING,
        CONTEXT_STARTED,
        CONTEXT_CLOSING,
        CONTEXT_CLOSED,
        BEAN_CREATING,
        BEAN_CREATED,
        BEAN_FAILED,
        BEAN_DESTROYING,
        BEAN_DESTROYED,
        BEAN_DESTROY_FAILED,
        ROLLBACK_STARTING,
        ROLLBACK_COMPLETED
    }

    public BeanEvent {
        if (kind == null) throw new NullPointerException("kind");
        if (durationNanos < 0) throw new IllegalArgumentException("durationNanos");
    }

    public static BeanEvent context(Kind kind) {
        return new BeanEvent(kind, null, null, 0, null);
    }
}
