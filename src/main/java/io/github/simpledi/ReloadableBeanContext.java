package io.github.simpledi;

import java.nio.file.Path;
import java.util.List;

/** Atomic, generation-based owner of reloadable {@link BeanContext} instances. */
public interface ReloadableBeanContext extends AutoCloseable {
    ContextLease acquire();
    long generation();
    ConfigurationRevision revision();
    ContextSnapshot snapshot();
    PreparedReload prepare(Path configuration);
    ReloadResult reload(Path configuration);
    List<RetiredGeneration> retiredGenerations();
    boolean isClosed();
    @Override void close();
}
