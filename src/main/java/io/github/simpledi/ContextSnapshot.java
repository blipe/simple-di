package io.github.simpledi;

import java.util.List;

/** Immutable runtime diagnostics containing names and counts only, never bean instances. */
public record ContextSnapshot(
        String state,
        List<String> createdSingletons,
        List<String> creatingSingletons,
        int activeOperations,
        int liveChildren,
        int managedLifecycleEntries) {
    public ContextSnapshot {
        createdSingletons = List.copyOf(createdSingletons);
        creatingSingletons = List.copyOf(creatingSingletons);
    }
}
