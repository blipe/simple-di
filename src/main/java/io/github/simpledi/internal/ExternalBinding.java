package io.github.simpledi.internal;

import java.lang.reflect.Type;
import java.util.Objects;

/** Runtime instance with the API type intentionally exposed to the graph compiler. */
public record ExternalBinding(Object instance, Type declaredType, String origin) {
    public ExternalBinding {
        instance = Objects.requireNonNull(instance, "instance");
        declaredType = Objects.requireNonNull(declaredType, "declaredType");
        origin = Objects.requireNonNull(origin, "origin");
    }
}
