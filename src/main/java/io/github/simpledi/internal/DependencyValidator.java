package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.SourceLocation;
import io.github.simpledi.internal.Definitions.ArrayValue;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.CallDef;
import io.github.simpledi.internal.Definitions.Document;
import io.github.simpledi.internal.Definitions.InjectionDef;
import io.github.simpledi.internal.Definitions.ListValue;
import io.github.simpledi.internal.Definitions.MapEntry;
import io.github.simpledi.internal.Definitions.MapValue;
import io.github.simpledi.internal.Definitions.NestedBean;
import io.github.simpledi.internal.Definitions.OptionalRef;
import io.github.simpledi.internal.Definitions.OptionalValue;
import io.github.simpledi.internal.Definitions.PropertyDef;
import io.github.simpledi.internal.Definitions.Ref;
import io.github.simpledi.internal.Definitions.SetValue;
import io.github.simpledi.internal.Definitions.SupplierRef;
import io.github.simpledi.internal.Definitions.ValueDef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates names and rejects eager dependency cycles. Supplier references intentionally are lazy edges. */
public final class DependencyValidator {
    private record Edge(String target, SourceLocation location, String reason) {}
    private enum State { VISITING, VISITED }


    public record Dependency(String source, String target, String kind, boolean lazy, SourceLocation location) {}

    /** Returns all named graph edges in deterministic declaration order. */
    public List<Dependency> describe(Document document, Set<String> externalNames) {
        List<Dependency> result = new ArrayList<>();
        for (BeanDef bean : document.beans().values()) {
            for (String dependency : bean.dependsOn()) {
                result.add(new Dependency(bean.id(),
                        canonicalAt(dependency, document, externalNames, bean.location()),
                        "depends-on", false, bean.location()));
            }
            if (bean.factory() != null && bean.factory().factoryBean() != null) {
                result.add(new Dependency(bean.id(),
                        canonicalAt(bean.factory().factoryBean(), document, externalNames, bean.factory().location()),
                        "instance-factory", false, bean.factory().location()));
            }
            describeBean(bean.id(), bean, result, document, externalNames);
        }
        return List.copyOf(result);
    }

    /** Returns the deterministic eager singleton startup order with dependencies before dependents. */
    public List<String> startupOrder(Document document, Set<String> externalNames) {
        List<Dependency> dependencies = describe(document, externalNames);
        Map<String, List<Dependency>> bySource = new LinkedHashMap<>();
        for (Dependency dependency : dependencies) {
            if (!dependency.lazy()) bySource.computeIfAbsent(dependency.source(), ignored -> new ArrayList<>()).add(dependency);
        }
        LinkedHashMap<String, Boolean> visited = new LinkedHashMap<>();
        List<String> result = new ArrayList<>();
        for (BeanDef bean : document.beans().values()) {
            if (bean.scope() == Definitions.Scope.SINGLETON && !bean.lazy()) {
                startupVisit(bean.id(), document, bySource, visited, result);
            }
        }
        return List.copyOf(result);
    }

    private void startupVisit(String id, Document document, Map<String, List<Dependency>> bySource,
                              Map<String, Boolean> visited, List<String> result) {
        if (visited.putIfAbsent(id, Boolean.TRUE) != null) return;
        for (Dependency dependency : bySource.getOrDefault(id, List.of())) {
            BeanDef target = document.beans().get(dependency.target());
            if (target != null && target.scope() == Definitions.Scope.SINGLETON) {
                startupVisit(target.id(), document, bySource, visited, result);
            }
        }
        result.add(id);
    }

    private void describeBean(String source, BeanDef bean, List<Dependency> result,
                              Document document, Set<String> externalNames) {
        bean.constructorArgs().forEach(argument -> describeValue(source, argument.value(), result, document, externalNames));
        if (bean.factory() != null) {
            bean.factory().args().forEach(argument -> describeValue(source, argument.value(), result, document, externalNames));
        }
        for (InjectionDef injection : bean.injections()) {
            if (injection instanceof PropertyDef property) {
                describeValue(source, property.value(), result, document, externalNames);
            } else if (injection instanceof CallDef call) {
                call.args().forEach(argument -> describeValue(source, argument.value(), result, document, externalNames));
            }
        }
    }

    private void describeValue(String source, ValueDef value, List<Dependency> result,
                               Document document, Set<String> externalNames) {
        if (value instanceof Ref ref) {
            result.add(new Dependency(source, canonicalAt(ref.beanId(), document, externalNames, ref.location()),
                    "reference", false, ref.location()));
        } else if (value instanceof OptionalRef optionalRef) {
            String target = optionalCanonical(optionalRef.beanId(), document, externalNames);
            if (target != null) result.add(new Dependency(source, target, "optional-reference", false, optionalRef.location()));
        } else if (value instanceof SupplierRef supplier) {
            result.add(new Dependency(source, canonicalAt(supplier.beanId(), document, externalNames, supplier.location()),
                    "supplier", true, supplier.location()));
        } else if (value instanceof NestedBean nested) {
            describeBean(source, nested.bean(), result, document, externalNames);
        } else if (value instanceof ListValue list) {
            list.values().forEach(item -> describeValue(source, item, result, document, externalNames));
        } else if (value instanceof SetValue set) {
            set.values().forEach(item -> describeValue(source, item, result, document, externalNames));
        } else if (value instanceof ArrayValue array) {
            array.values().forEach(item -> describeValue(source, item, result, document, externalNames));
        } else if (value instanceof MapValue map) {
            for (MapEntry entry : map.entries()) {
                describeValue(source, entry.key(), result, document, externalNames);
                describeValue(source, entry.value(), result, document, externalNames);
            }
        } else if (value instanceof OptionalValue optional && optional.value() != null) {
            describeValue(source, optional.value(), result, document, externalNames);
        }
    }

    public void validate(Document document) {
        validate(document, Set.of());
    }

    public void validate(Document document, Set<String> externalNames) {
        LinkedHashMap<String, BeanDef> beans = document.beans();
        Map<String, List<Edge>> graph = new LinkedHashMap<>();
        for (BeanDef bean : beans.values()) {
            List<Edge> edges = new ArrayList<>();
            for (String dependency : bean.dependsOn()) {
                edges.add(new Edge(canonicalAt(dependency, document, externalNames, bean.location()), bean.location(), "depends-on"));
            }
            if (bean.factory() != null && bean.factory().factoryBean() != null) {
                edges.add(new Edge(canonical(bean.factory().factoryBean(), document, externalNames),
                        bean.factory().location(), "instance factory"));
            }
            collectBean(bean, edges, document, externalNames);
            graph.put(bean.id(), List.copyOf(edges));
        }

        for (BeanDef source : beans.values()) {
            if (source.scope() != Definitions.Scope.SINGLETON) continue;
            for (Edge edge : graph.getOrDefault(source.id(), List.of())) {
                BeanDef target = beans.get(edge.target());
                if (target == null) continue;
                if (target.scope() == Definitions.Scope.CUSTOM) {
                    throw new BeanException(edge.location(), "Scope violation: singleton bean '" + source.id()
                            + "' directly depends on custom-scoped bean '" + target.id()
                            + "'. Inject <supplier ref=\"" + target.id() + "\"/> instead.");
                }
                if (target.scope() == Definitions.Scope.PROTOTYPE
                        && target.ownership() == Definitions.Ownership.CALLER) {
                    throw new BeanException(edge.location(), "Scope violation: singleton bean '" + source.id()
                            + "' directly depends on caller-owned prototype '" + target.id()
                            + "'. Inject a supplier or use ownership=\"context\".");
                }
            }
        }

        Map<String, State> states = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : beans.keySet()) {
            if (!states.containsKey(id)) dfs(id, graph, states, path);
        }
    }

    public static String canonical(String name, Document document) {
        return canonical(name, document, Set.of());
    }

    public static String canonical(String name, Document document, Set<String> externalNames) {
        if (document.beans().containsKey(name) || externalNames.contains(name)) return name;
        String target = document.aliases().get(name);
        if (target != null) return target;
        throw new BeanException("Unknown bean reference '" + name + "'");
    }

    private void dfs(String id, Map<String, List<Edge>> graph, Map<String, State> states, Deque<String> path) {
        states.put(id, State.VISITING);
        path.addLast(id);
        for (Edge edge : graph.getOrDefault(id, List.of())) {
            State targetState = states.get(edge.target());
            if (targetState == State.VISITING) {
                List<String> cycle = new ArrayList<>();
                boolean include = false;
                for (String item : path) {
                    if (item.equals(edge.target())) include = true;
                    if (include) cycle.add(item);
                }
                cycle.add(edge.target());
                throw new BeanException(edge.location(), "Circular dependency: "
                        + String.join(" -> ", cycle) + " (eager " + edge.reason() + "). Use <supplier ref=\"...\"/> only for a genuinely lazy edge.");
            }
            if (targetState == null) dfs(edge.target(), graph, states, path);
        }
        path.removeLast();
        states.put(id, State.VISITED);
    }

    private void collectBean(BeanDef bean, List<Edge> edges, Document document, Set<String> externalNames) {
        bean.constructorArgs().forEach(v -> collectValue(v.value(), edges, document, externalNames));
        if (bean.factory() != null) bean.factory().args().forEach(v -> collectValue(v.value(), edges, document, externalNames));
        for (InjectionDef injection : bean.injections()) {
            if (injection instanceof PropertyDef p) collectValue(p.value(), edges, document, externalNames);
            else if (injection instanceof CallDef c) c.args().forEach(v -> collectValue(v.value(), edges, document, externalNames));
        }
    }

    private void collectValue(ValueDef value, List<Edge> edges, Document document, Set<String> externalNames) {
        if (value instanceof Ref ref) {
            edges.add(new Edge(canonicalAt(ref.beanId(), document, externalNames, ref.location()), ref.location(), "reference"));
        } else if (value instanceof OptionalRef optionalRef) {
            String target = optionalCanonical(optionalRef.beanId(), document, externalNames);
            if (target != null) edges.add(new Edge(target, optionalRef.location(), "optional reference"));
        } else if (value instanceof SupplierRef supplier) {
            canonicalAt(supplier.beanId(), document, externalNames, supplier.location());
        } else if (value instanceof NestedBean nested) {
            collectBean(nested.bean(), edges, document, externalNames);
        } else if (value instanceof ListValue list) {
            list.values().forEach(v -> collectValue(v, edges, document, externalNames));
        } else if (value instanceof SetValue set) {
            set.values().forEach(v -> collectValue(v, edges, document, externalNames));
        } else if (value instanceof ArrayValue array) {
            array.values().forEach(v -> collectValue(v, edges, document, externalNames));
        } else if (value instanceof MapValue map) {
            for (MapEntry entry : map.entries()) {
                collectValue(entry.key(), edges, document, externalNames);
                collectValue(entry.value(), edges, document, externalNames);
            }
        } else if (value instanceof OptionalValue optional && optional.value() != null) {
            collectValue(optional.value(), edges, document, externalNames);
        }
    }

    private static String canonicalAt(String name, Document document, Set<String> externalNames, SourceLocation location) {
        if (document.beans().containsKey(name) || externalNames.contains(name)) return name;
        String target = document.aliases().get(name);
        if (target != null) return target;
        throw new BeanException(location, "Unknown bean reference '" + name + "'");
    }

    private static String optionalCanonical(String name, Document document, Set<String> externalNames) {
        if (document.beans().containsKey(name) || externalNames.contains(name)) return name;
        return document.aliases().get(name);
    }
}
