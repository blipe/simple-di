package io.github.simpledi;

import java.util.Objects;

/** Side-effect-free result of evaluating one bean activation condition. */
public record ConditionOutcome(
        String beanId,
        boolean active,
        String property,
        String operator,
        String source,
        boolean sensitive,
        SourceLocation location) {
    public ConditionOutcome {
        beanId = Objects.requireNonNull(beanId, "beanId");
        property = Objects.requireNonNull(property, "property");
        operator = Objects.requireNonNull(operator, "operator");
    }
}
