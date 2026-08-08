package io.github.simpledi;

import java.util.List;
import java.util.Objects;

/** A structured validation problem suitable for build tools and IDE integrations. */
public record ConfigurationProblem(
        Code code,
        Severity severity,
        SourceLocation location,
        String beanId,
        List<String> dependencyPath,
        String message) {
    public enum Severity { ERROR, WARNING }
    public enum Code { XML, PROPERTY, CONDITION, SCOPE, REFERENCE, CYCLE, TYPE, EXECUTABLE, ACCESS, OVERLAY, CONFIGURATION }

    public ConfigurationProblem {
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        dependencyPath = List.copyOf(dependencyPath == null ? List.of() : dependencyPath);
        message = Objects.requireNonNull(message, "message");
    }
}
