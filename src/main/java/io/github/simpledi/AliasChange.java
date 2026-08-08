package io.github.simpledi;

import java.util.Objects;

/** Retargeting of an existing alias between two validated configurations. */
public record AliasChange(String alias, String beforeTarget, String afterTarget) {
    public AliasChange {
        alias = Objects.requireNonNull(alias, "alias");
        beforeTarget = Objects.requireNonNull(beforeTarget, "beforeTarget");
        afterTarget = Objects.requireNonNull(afterTarget, "afterTarget");
    }
}
