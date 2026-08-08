package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.ConditionOutcome;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.ConditionDef;
import io.github.simpledi.internal.Definitions.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Evaluates property conditions without loading classes or constructing application objects. */
public final class ConditionEvaluator {
    public record Result(Document document, List<ConditionOutcome> outcomes) {}

    private final PropertyResolver resolver;
    private final PropertyExpander expander;
    private final Set<String> externalNames;

    public ConditionEvaluator(PropertyResolver resolver, PropertyExpander expander, Set<String> externalNames) {
        this.resolver = resolver;
        this.expander = expander;
        this.externalNames = Set.copyOf(externalNames);
    }

    public Result evaluate(Document source) {
        LinkedHashMap<String, BeanDef> beans = new LinkedHashMap<>();
        List<ConditionOutcome> outcomes = new ArrayList<>();
        for (BeanDef bean : source.beans().values()) {
            ConditionDef condition = bean.condition();
            if (condition == null) {
                beans.put(bean.id(), bean);
                continue;
            }
            Optional<PropertyResolver.Resolved> selected = resolver.find(condition.property());
            boolean active;
            String operator;
            if (selected.isEmpty()) {
                active = condition.negated() || condition.matchIfMissing();
                operator = condition.negated() ? "unless-missing" : "if-missing";
            } else {
                String expected = condition.expectedValue();
                boolean matched = expected == null || selected.get().value().equals(expander.expand(expected,
                        condition.location()));
                active = condition.negated() ? !matched : matched;
                operator = condition.negated()
                        ? (expected == null ? "unless-present" : "unless-equals")
                        : (expected == null ? "if-present" : "if-equals");
            }
            outcomes.add(new ConditionOutcome(bean.id(), active, condition.property(), operator,
                    selected.map(PropertyResolver.Resolved::source).orElse(null),
                    selected.map(PropertyResolver.Resolved::sensitive).orElse(false), condition.location()));
            if (active) beans.put(bean.id(), bean);
        }

        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (var alias : source.aliases().entrySet()) {
            if (beans.containsKey(alias.getValue()) || externalNames.contains(alias.getValue())) {
                aliases.put(alias.getKey(), alias.getValue());
            }
        }
        for (BeanDef bean : beans.values()) {
            if (bean.replaces() != null && !bean.replaces().equals(bean.id())) {
                throw new BeanException(bean.location(), "Conditional overlay replacement must keep the same id");
            }
        }
        return new Result(new Document(beans, aliases, outcomes), List.copyOf(outcomes));
    }
}
