package io.github.simpledi;

import java.util.List;
import java.util.Objects;

/** Structural change to one compiled bean plan. */
public record BeanChange(String beanId, List<String> fields, BeanPlan before, BeanPlan after) {
    public BeanChange {
        beanId = Objects.requireNonNull(beanId, "beanId");
        fields = List.copyOf(fields);
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
    }
}
