package io.github.simpledi.internal;

import io.github.simpledi.PropertyResolution;
import io.github.simpledi.PropertySource;
import io.github.simpledi.PropertyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Immutable precedence-aware property resolver that records value-free resolution metadata. */
public final class PropertyResolver {
    public record Resolved(String value, boolean sensitive, String source, List<String> shadowedSources) {}

    private final List<PropertySource> sources;
    private final LinkedHashMap<String, PropertyResolution> resolutions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Resolved> resolvedValues = new LinkedHashMap<>();

    /** Sources are ordered from highest to lowest precedence. */
    public PropertyResolver(List<PropertySource> sources) {
        this.sources = List.copyOf(sources);
    }

    public synchronized Optional<Resolved> find(String name) {
        Resolved cached = resolvedValues.get(name);
        if (cached != null) return Optional.of(cached);
        PropertyValue selected = null;
        List<String> shadowed = new ArrayList<>();
        for (PropertySource source : sources) {
            Optional<PropertyValue> candidate = source.find(name);
            if (candidate.isEmpty()) continue;
            if (selected == null) selected = candidate.get();
            else shadowed.add(candidate.get().source());
        }
        if (selected == null) return Optional.empty();
        Resolved resolved = new Resolved(selected.value(), selected.sensitive(), selected.source(), List.copyOf(shadowed));
        resolvedValues.put(name, resolved);
        resolutions.putIfAbsent(name, new PropertyResolution(name, selected.source(), shadowed,
                selected.sensitive(), false));
        return Optional.of(resolved);
    }

    public synchronized void recordDefault(String name, boolean sensitive) {
        resolutions.putIfAbsent(name, new PropertyResolution(name, "<default>", List.of(), sensitive, true));
    }

    public synchronized List<PropertyResolution> resolutions() {
        return Collections.unmodifiableList(new ArrayList<>(resolutions.values()));
    }

    public synchronized void clear() {
        resolutions.clear();
        resolvedValues.clear();
    }
}
