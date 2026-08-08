package io.github.simpledi;

import java.util.Objects;

/** One named dependency edge in the compiled object graph. */
public record DependencyPlan(
        String sourceBean,
        String targetBean,
        String kind,
        boolean lazy,
        SourceLocation location) {
    public DependencyPlan {
        sourceBean = Objects.requireNonNull(sourceBean, "sourceBean");
        targetBean = Objects.requireNonNull(targetBean, "targetBean");
        kind = Objects.requireNonNull(kind, "kind");
    }
}
