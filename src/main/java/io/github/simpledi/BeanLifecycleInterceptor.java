package io.github.simpledi;

/**
 * Observes actual bean instances at deterministic lifecycle boundaries.
 * Throwing fails the active operation and participates in transactional rollback.
 */
public interface BeanLifecycleInterceptor {
    default void afterConstruction(BeanLifecycleContext context, Object bean) throws Exception {}

    default void beforeInitialization(BeanLifecycleContext context, Object bean) throws Exception {}

    default void afterInitialization(BeanLifecycleContext context, Object bean) throws Exception {}

    default void beforeDestruction(BeanLifecycleContext context, Object bean) throws Exception {}

    default void afterDestruction(BeanLifecycleContext context, Object bean, Throwable failure) throws Exception {}
}
