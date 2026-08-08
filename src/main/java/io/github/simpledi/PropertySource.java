package io.github.simpledi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves configuration properties. Sources added later to a builder have higher precedence. */
@FunctionalInterface
public interface PropertySource {
    /** Returns a value for {@code name}, or an empty result when this source does not define it. */
    Optional<PropertyValue> find(String name);

    /** Creates a named, non-sensitive map-backed source. */
    static PropertySource of(String sourceName, Map<String, String> values) {
        return of(sourceName, values, Set.of());
    }

    /** Creates a named map-backed source with selected keys classified as sensitive. */
    static PropertySource of(String sourceName, Map<String, String> values, Set<String> sensitiveKeys) {
        Objects.requireNonNull(sourceName, "sourceName");
        if (sourceName.isBlank()) throw new IllegalArgumentException("sourceName must not be blank");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        Objects.requireNonNull(values, "values").forEach((key, value) ->
                copy.put(Objects.requireNonNull(key, "property key"), Objects.requireNonNull(value, "property value")));
        Set<String> secrets = Set.copyOf(Objects.requireNonNull(sensitiveKeys, "sensitiveKeys"));
        return name -> {
            String value = copy.get(name);
            if (value == null) return Optional.empty();
            return Optional.of(new PropertyValue(value, secrets.contains(name), sourceName));
        };
    }
}
