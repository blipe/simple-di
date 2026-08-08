package io.github.simpledi;

/** Synchronous observer for generation preparation, activation, draining, and destruction. */
@FunctionalInterface
public interface ReloadListener {
    void onEvent(ReloadEvent event);
}
