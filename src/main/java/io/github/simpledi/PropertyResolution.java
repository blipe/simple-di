package io.github.simpledi;

import java.util.List;
import java.util.Objects;

/** Metadata describing which property source won without exposing the property's value. */
public record PropertyResolution(
        String name,
        String selectedSource,
        List<String> shadowedSources,
        boolean sensitive,
        boolean defaultValue) {
    public PropertyResolution {
        name = Objects.requireNonNull(name, "name");
        selectedSource = Objects.requireNonNull(selectedSource, "selectedSource");
        shadowedSources = List.copyOf(shadowedSources);
    }
}
