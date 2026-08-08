package io.github.simpledi;

/**
 * A named runtime scope. The container reserves a bean, constructs outside scope locks, then publishes on transaction
 * commit. Implementations must be thread-safe and must not invoke application constructors themselves.
 */
public interface BeanScope extends AutoCloseable {
    /** Reserves the current logical scope's slot for {@code beanId}. */
    Reservation reserve(String beanId);

    interface Reservation {
        /** True only for the caller responsible for constructing and publishing the value. */
        boolean creator();

        /** Waits for and returns the published handle. Valid only when {@link #creator()} is false. */
        BeanHandle<Object> await();

        /** Publishes the constructed handle. Valid only for the creator reservation. */
        void publish(BeanHandle<Object> handle);

        /** Cancels an unpublished reservation after rollback. Valid only for the creator reservation. */
        void cancel(Throwable failure);
    }

    @Override
    void close();
}
