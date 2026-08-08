package io.github.simpledi;

import java.util.List;
import java.util.Objects;

/** Compiled, side-effect-free description of one configured bean. */
public record BeanPlan(
        String id,
        String declaredType,
        String scope,
        String ownership,
        boolean lazy,
        String creator,
        List<String> injections,
        String initMethod,
        String destroyMethod,
        SourceLocation location) {
    public BeanPlan {
        id = Objects.requireNonNull(id, "id");
        declaredType = Objects.requireNonNull(declaredType, "declaredType");
        scope = Objects.requireNonNull(scope, "scope");
        ownership = Objects.requireNonNull(ownership, "ownership");
        creator = Objects.requireNonNull(creator, "creator");
        injections = List.copyOf(injections);
    }
}
