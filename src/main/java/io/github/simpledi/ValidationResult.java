package io.github.simpledi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Side-effect-free parse, graph, type, executable, overlay, condition, and property validation report. */
public final class ValidationResult {
    private final Set<String> beanNames;
    private final Set<String> aliases;
    private final Map<String, String> aliasTargets;
    private final Map<String, BeanPlan> beans;
    private final List<DependencyPlan> dependencies;
    private final List<String> creationOrder;
    private final List<String> destructionOrder;
    private final List<PropertyResolution> properties;
    private final List<ConditionOutcome> conditions;
    private final List<ConfigurationProblem> problems;

    /** Backward-compatible minimal successful result constructor. */
    public ValidationResult(Set<String> beanNames, Set<String> aliases) {
        this(beanNames, aliases, Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Backward-compatible 2.3 constructor. */
    public ValidationResult(
            Set<String> beanNames,
            Set<String> aliases,
            Map<String, BeanPlan> beans,
            List<DependencyPlan> dependencies,
            List<String> creationOrder,
            List<String> destructionOrder,
            List<PropertyResolution> properties,
            List<ConfigurationProblem> problems) {
        this(beanNames, aliases, Map.of(), beans, dependencies, creationOrder, destructionOrder, properties, List.of(), problems);
    }

    public ValidationResult(
            Set<String> beanNames,
            Set<String> aliases,
            Map<String, BeanPlan> beans,
            List<DependencyPlan> dependencies,
            List<String> creationOrder,
            List<String> destructionOrder,
            List<PropertyResolution> properties,
            List<ConditionOutcome> conditions,
            List<ConfigurationProblem> problems) {
        this(beanNames, aliases, Map.of(), beans, dependencies, creationOrder, destructionOrder,
                properties, conditions, problems);
    }

    /** Complete report constructor, including deterministic alias targets. */
    public ValidationResult(
            Set<String> beanNames,
            Set<String> aliases,
            Map<String, String> aliasTargets,
            Map<String, BeanPlan> beans,
            List<DependencyPlan> dependencies,
            List<String> creationOrder,
            List<String> destructionOrder,
            List<PropertyResolution> properties,
            List<ConditionOutcome> conditions,
            List<ConfigurationProblem> problems) {
        this.beanNames = Collections.unmodifiableSet(new LinkedHashSet<>(beanNames));
        this.aliases = Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
        this.aliasTargets = Collections.unmodifiableMap(new LinkedHashMap<>(aliasTargets));
        this.beans = Collections.unmodifiableMap(new LinkedHashMap<>(beans));
        this.dependencies = List.copyOf(dependencies);
        this.creationOrder = List.copyOf(creationOrder);
        this.destructionOrder = List.copyOf(destructionOrder);
        this.properties = List.copyOf(properties);
        this.conditions = List.copyOf(conditions);
        this.problems = List.copyOf(problems);
    }

    public Set<String> beanNames() { return beanNames; }
    public Set<String> aliases() { return aliases; }
    public Map<String, String> aliasTargets() { return aliasTargets; }
    public Map<String, BeanPlan> beans() { return beans; }
    public List<DependencyPlan> dependencies() { return dependencies; }
    public List<String> creationOrder() { return creationOrder; }
    public List<String> destructionOrder() { return destructionOrder; }
    public List<PropertyResolution> properties() { return properties; }
    public List<ConditionOutcome> conditions() { return conditions; }
    public List<ConfigurationProblem> problems() { return problems; }
    public boolean valid() { return problems.stream().noneMatch(p -> p.severity() == ConfigurationProblem.Severity.ERROR); }

    /** Throws the first validation error, preserving the historic validate-or-throw behavior. */
    public void throwIfInvalid() {
        if (valid()) return;
        ConfigurationProblem first = problems.get(0);
        throw new BeanException(first.location(), first.message());
    }

    /** Returns a deterministic structural diff that never exposes property values. */
    public ConfigurationDiff diff(ValidationResult newer) {
        Objects.requireNonNull(newer, "newer");
        LinkedHashSet<String> addedBeans = difference(newer.beanNames, beanNames);
        LinkedHashSet<String> removedBeans = difference(beanNames, newer.beanNames);
        LinkedHashMap<String, BeanChange> changed = new LinkedHashMap<>();
        for (String id : beanNames) {
            BeanPlan before = beans.get(id);
            BeanPlan after = newer.beans.get(id);
            if (before == null || after == null) continue;
            List<String> fields = changedFields(before, after);
            if (!fields.isEmpty()) changed.put(id, new BeanChange(id, fields, before, after));
        }

        LinkedHashSet<String> oldDeps = dependencyKeys(dependencies);
        LinkedHashSet<String> newDeps = dependencyKeys(newer.dependencies);
        LinkedHashMap<String, PropertyResolution> oldProperties = propertiesByName(properties);
        LinkedHashMap<String, PropertyResolution> newProperties = propertiesByName(newer.properties);
        LinkedHashSet<String> changedProperties = new LinkedHashSet<>();
        LinkedHashSet<String> allProperties = new LinkedHashSet<>(oldProperties.keySet());
        allProperties.addAll(newProperties.keySet());
        for (String name : allProperties) {
            if (!Objects.equals(oldProperties.get(name), newProperties.get(name))) changedProperties.add(name);
        }

        LinkedHashMap<String, AliasChange> changedAliases = new LinkedHashMap<>();
        for (String alias : aliases) {
            if (!newer.aliases.contains(alias)) continue;
            String beforeTarget = aliasTargets.get(alias);
            String afterTarget = newer.aliasTargets.get(alias);
            if (beforeTarget != null && afterTarget != null && !beforeTarget.equals(afterTarget)) {
                changedAliases.put(alias, new AliasChange(alias, beforeTarget, afterTarget));
            }
        }
        LinkedHashSet<String> changedConditions = changedConditionBeans(conditions, newer.conditions);

        return new ConfigurationDiff(addedBeans, removedBeans, changed,
                difference(newer.aliases, aliases), difference(aliases, newer.aliases), changedAliases,
                List.copyOf(difference(newDeps, oldDeps)), List.copyOf(difference(oldDeps, newDeps)),
                changedProperties, changedConditions);
    }

    /** Produces a deterministic Graphviz DOT graph suitable for build artifacts and diagnostics. */
    public String toDot() {
        StringBuilder out = new StringBuilder("digraph simple_di {\n  rankdir=LR;\n");
        for (String id : beanNames) {
            BeanPlan plan = beans.get(id);
            String label = plan == null ? id : id + "\\n" + plan.declaredType() + "\\n" + plan.scope();
            out.append("  ").append(quote(id)).append(" [label=").append(quote(label)).append("];\n");
        }
        for (Map.Entry<String, String> alias : aliasTargets.entrySet()) {
            out.append("  ").append(quote(alias.getKey()))
                    .append(" [shape=box,style=dashed,label=").append(quote(alias.getKey() + " (alias)"))
                    .append("];\n");
            out.append("  ").append(quote(alias.getKey())).append(" -> ").append(quote(alias.getValue()))
                    .append(" [style=dashed,label=\"alias\"];\n");
        }
        for (DependencyPlan dependency : dependencies) {
            out.append("  ").append(quote(dependency.sourceBean())).append(" -> ")
                    .append(quote(dependency.targetBean())).append(" [label=")
                    .append(quote(dependency.kind() + (dependency.lazy() ? " (lazy)" : ""))).append("];\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /** Human-readable deterministic explanation of one bean's compiled plan and edges. */
    public String explain(String beanId) {
        Objects.requireNonNull(beanId, "beanId");
        String aliasTarget = aliasTargets.get(beanId);
        if (aliasTarget != null) {
            return beanId + " is an alias for " + aliasTarget + "\n" + explain(aliasTarget);
        }
        BeanPlan plan = beans.get(beanId);
        if (plan == null) {
            List<ConditionOutcome> outcomes = conditions.stream()
                    .filter(value -> value.beanId().equals(beanId)).toList();
            if (!outcomes.isEmpty()) {
                StringBuilder inactive = new StringBuilder(beanId + " has no active compiled plan\n");
                for (ConditionOutcome outcome : outcomes) {
                    inactive.append("  condition: ").append(outcome.operator()).append(' ')
                            .append(outcome.property()).append(" => ")
                            .append(outcome.active() ? "active" : "inactive").append('\n');
                }
                return inactive.toString();
            }
            if (beanNames.contains(beanId)) return beanId + " is an external binding\n";
            throw new IllegalArgumentException("No configured bean or alias '" + beanId + "'");
        }
        StringBuilder out = new StringBuilder();
        out.append(beanId).append(" : ").append(plan.declaredType()).append('\n');
        out.append("  scope: ").append(plan.scope()).append('\n');
        out.append("  ownership: ").append(plan.ownership()).append('\n');
        out.append("  lazy: ").append(plan.lazy()).append('\n');
        out.append("  creator: ").append(plan.creator()).append('\n');
        for (String injection : plan.injections()) out.append("  injection: ").append(injection).append('\n');
        for (ConditionOutcome outcome : conditions) {
            if (outcome.beanId().equals(beanId)) {
                out.append("  condition: ").append(outcome.operator()).append(' ')
                        .append(outcome.property()).append(" => ")
                        .append(outcome.active() ? "active" : "inactive").append('\n');
            }
        }
        for (DependencyPlan dependency : dependencies) {
            if (dependency.sourceBean().equals(beanId)) {
                out.append("  depends on: ").append(dependency.targetBean()).append(" via ")
                        .append(dependency.kind());
                if (dependency.lazy()) out.append(" (lazy)");
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static List<String> changedFields(BeanPlan before, BeanPlan after) {
        List<String> result = new ArrayList<>();
        if (!before.declaredType().equals(after.declaredType())) result.add("declaredType");
        if (!before.scope().equals(after.scope())) result.add("scope");
        if (!before.ownership().equals(after.ownership())) result.add("ownership");
        if (before.lazy() != after.lazy()) result.add("lazy");
        if (!before.creator().equals(after.creator())) result.add("creator");
        if (!before.injections().equals(after.injections())) result.add("injections");
        if (!Objects.equals(before.initMethod(), after.initMethod())) result.add("initMethod");
        if (!Objects.equals(before.destroyMethod(), after.destroyMethod())) result.add("destroyMethod");
        return List.copyOf(result);
    }

    private static LinkedHashSet<String> dependencyKeys(List<DependencyPlan> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (DependencyPlan value : values) {
            result.add(value.sourceBean() + " -> " + value.targetBean() + " [" + value.kind()
                    + (value.lazy() ? ", lazy" : "") + "]");
        }
        return result;
    }

    private static LinkedHashMap<String, PropertyResolution> propertiesByName(List<PropertyResolution> values) {
        LinkedHashMap<String, PropertyResolution> result = new LinkedHashMap<>();
        for (PropertyResolution value : values) result.put(value.name(), value);
        return result;
    }

    private static LinkedHashSet<String> changedConditionBeans(
            List<ConditionOutcome> before, List<ConditionOutcome> after) {
        LinkedHashMap<String, List<String>> oldValues = conditionsByBean(before);
        LinkedHashMap<String, List<String>> newValues = conditionsByBean(after);
        LinkedHashSet<String> ids = new LinkedHashSet<>(oldValues.keySet());
        ids.addAll(newValues.keySet());
        LinkedHashSet<String> changed = new LinkedHashSet<>();
        for (String id : ids) {
            if (!Objects.equals(oldValues.get(id), newValues.get(id))) changed.add(id);
        }
        return changed;
    }

    private static LinkedHashMap<String, List<String>> conditionsByBean(List<ConditionOutcome> outcomes) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        LinkedHashMap<String, ArrayList<String>> mutable = new LinkedHashMap<>();
        for (ConditionOutcome outcome : outcomes) {
            mutable.computeIfAbsent(outcome.beanId(), ignored -> new ArrayList<>()).add(
                    outcome.active() + "|" + outcome.property() + "|" + outcome.operator() + "|"
                            + outcome.source() + "|" + outcome.sensitive());
        }
        mutable.forEach((id, values) -> result.put(id, List.copyOf(values)));
        return result;
    }

    private static LinkedHashSet<String> difference(Set<String> left, Set<String> right) {
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
