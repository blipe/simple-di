package io.github.simpledi;

/**
 * A caller-owned prototype instance and its deterministic destruction boundary.
 * Closing the handle destroys the prototype graph in reverse creation order.
 */
public interface BeanHandle<T> extends AutoCloseable {
    T value();

    boolean isClosed();

    @Override
    void close();
}
