package io.github.simpledi;

import java.util.Objects;

/** A property value together with its origin and sensitivity classification. */
public record PropertyValue(String value, boolean sensitive, String source) {
    public PropertyValue {
        value = Objects.requireNonNull(value, "value");
        source = Objects.requireNonNull(source, "source");
        if (source.isBlank()) throw new IllegalArgumentException("source must not be blank");
    }

    /** Creates a non-sensitive property value. */
    public static PropertyValue plain(String value, String source) {
        return new PropertyValue(value, false, source);
    }

    /** Creates a sensitive property value whose contents must be redacted from diagnostics. */
    public static PropertyValue secret(String value, String source) {
        return new PropertyValue(value, true, source);
    }
}
