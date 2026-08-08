package io.github.simpledi;

import java.lang.reflect.Type;
import java.util.Objects;

/** Immutable metadata supplied to bean lifecycle interceptors. */
public record BeanLifecycleContext(
        String beanId,
        Type declaredType,
        String scope,
        String ownership,
        SourceLocation location) {
    public BeanLifecycleContext {
        beanId = Objects.requireNonNull(beanId, "beanId");
        declaredType = Objects.requireNonNull(declaredType, "declaredType");
        scope = Objects.requireNonNull(scope, "scope");
        ownership = Objects.requireNonNull(ownership, "ownership");
    }
}
