package io.github.simpledi;

/** Explicit opt-in state transfer performed after candidate startup but before atomic publication. */
@FunctionalInterface
public interface GenerationHandoff {
    void transfer(BeanContext previous, BeanContext candidate, ConfigurationDiff diff) throws Exception;
}
