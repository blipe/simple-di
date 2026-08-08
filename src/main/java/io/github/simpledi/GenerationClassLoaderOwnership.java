package io.github.simpledi;

/** Lifecycle ownership for the classloader supplied by each reload generation's builder. */
public enum GenerationClassLoaderOwnership {
    /** The host owns and closes classloaders. */
    EXTERNAL,
    /** Close an {@link AutoCloseable} classloader after its context generation is destroyed. */
    CLOSE_ON_RETIREMENT
}
