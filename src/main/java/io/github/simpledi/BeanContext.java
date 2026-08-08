package io.github.simpledi;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A started, thread-safe object graph loaded from XML. */
public interface BeanContext extends AutoCloseable {
    Object require(String id);

    <T> T require(String id, Class<T> type);

    <T> T require(Class<T> type);

    /** Requires the unique bean assignable to a complete generic type. */
    default <T> T require(TypeRef<T> type) {
        throw new UnsupportedOperationException("Generic lookup is not supported by this BeanContext implementation");
    }

    Optional<Object> find(String id);

    <T> Optional<T> find(String id, Class<T> type);

    <T> Optional<T> find(Class<T> type);

    /** Finds the unique bean assignable to a complete generic type. */
    default <T> Optional<T> find(TypeRef<T> type) {
        throw new UnsupportedOperationException("Generic lookup is not supported by this BeanContext implementation");
    }

    <T> Map<String, T> beansOfType(Class<T> type);

    /** Returns all beans assignable to a complete generic type, in declaration order. */
    default <T> Map<String, T> beansOfType(TypeRef<T> type) {
        throw new UnsupportedOperationException("Generic lookup is not supported by this BeanContext implementation");
    }

    /** Returns the canonical declared type used for validation and lookup. */
    default Type beanType(String id) {
        throw new UnsupportedOperationException("Declared type inspection is not supported by this BeanContext implementation");
    }

    /**
     * Creates a caller-owned prototype and returns its destruction handle.
     * The named bean must use scope="prototype" ownership="caller" (the prototype default).
     */
    default BeanHandle<Object> create(String id) {
        throw new UnsupportedOperationException("Prototype handles are not supported by this BeanContext implementation");
    }

    /** Typed form of {@link #create(String)}. */
    default <T> BeanHandle<T> create(String id, Class<T> type) {
        throw new UnsupportedOperationException("Prototype handles are not supported by this BeanContext implementation");
    }

    boolean contains(String id);

    Set<String> beanNames();

    Set<String> aliases();

    boolean isClosed();

    /** Returns a names-and-counts-only runtime snapshot without constructing lazy beans. */
    default ContextSnapshot snapshot() {
        throw new UnsupportedOperationException("Runtime snapshots are not supported by this BeanContext implementation");
    }

    @Override
    void close();
}
