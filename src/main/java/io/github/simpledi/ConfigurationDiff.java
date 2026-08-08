package io.github.simpledi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, value-free structural difference between two side-effect-free validation reports. */
public record ConfigurationDiff(
        Set<String> addedBeans,
        Set<String> removedBeans,
        Map<String, BeanChange> changedBeans,
        Set<String> addedAliases,
        Set<String> removedAliases,
        Map<String, AliasChange> changedAliases,
        List<String> addedDependencies,
        List<String> removedDependencies,
        Set<String> changedProperties,
        Set<String> changedConditions) {

    /** Backward-compatible constructor for diffs produced before alias and condition change tracking. */
    public ConfigurationDiff(
            Set<String> addedBeans,
            Set<String> removedBeans,
            Map<String, BeanChange> changedBeans,
            Set<String> addedAliases,
            Set<String> removedAliases,
            List<String> addedDependencies,
            List<String> removedDependencies,
            Set<String> changedProperties) {
        this(addedBeans, removedBeans, changedBeans, addedAliases, removedAliases, Map.of(),
                addedDependencies, removedDependencies, changedProperties, Set.of());
    }

    public ConfigurationDiff {
        addedBeans = Collections.unmodifiableSet(new LinkedHashSet<>(addedBeans));
        removedBeans = Collections.unmodifiableSet(new LinkedHashSet<>(removedBeans));
        changedBeans = Collections.unmodifiableMap(new LinkedHashMap<>(changedBeans));
        addedAliases = Collections.unmodifiableSet(new LinkedHashSet<>(addedAliases));
        removedAliases = Collections.unmodifiableSet(new LinkedHashSet<>(removedAliases));
        changedAliases = Collections.unmodifiableMap(new LinkedHashMap<>(changedAliases));
        addedDependencies = List.copyOf(addedDependencies);
        removedDependencies = List.copyOf(removedDependencies);
        changedProperties = Collections.unmodifiableSet(new LinkedHashSet<>(changedProperties));
        changedConditions = Collections.unmodifiableSet(new LinkedHashSet<>(changedConditions));
    }

    public boolean empty() {
        return addedBeans.isEmpty() && removedBeans.isEmpty() && changedBeans.isEmpty()
                && addedAliases.isEmpty() && removedAliases.isEmpty() && changedAliases.isEmpty()
                && addedDependencies.isEmpty() && removedDependencies.isEmpty()
                && changedProperties.isEmpty() && changedConditions.isEmpty();
    }
}
