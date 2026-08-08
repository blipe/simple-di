package io.github.simpledi;

/** Receives synchronous context events. Throwing fails the active operation and triggers normal rollback. */
@FunctionalInterface
public interface BeanContextListener {
    void onEvent(BeanEvent event) throws Exception;
}
