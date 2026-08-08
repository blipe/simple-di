package io.github.simpledi;

import java.lang.reflect.Type;

/** Context supplied to string value converters. */
public record ConversionContext(
        ClassLoader classLoader,
        Type targetType,
        SourceLocation location,
        boolean sensitive) {
    /** Compatibility constructor for non-sensitive values. */
    public ConversionContext(ClassLoader classLoader, Type targetType, SourceLocation location) {
        this(classLoader, targetType, location, false);
    }
}
