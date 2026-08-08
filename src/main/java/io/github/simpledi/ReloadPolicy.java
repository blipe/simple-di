package io.github.simpledi;

/** Retirement behavior used when replacing or closing a context generation. */
public enum ReloadPolicy {
    /** Publish without waiting for existing leases; destroy an already-drained generation inline. */
    GRACEFUL,
    /** Publish immediately, wait up to the configured timeout, then leave a still-busy generation retired. */
    GRACEFUL_WITH_TIMEOUT,
    /** Close the retired context immediately, invalidating outstanding leases. */
    IMMEDIATE,
    /** Reject activation while the active generation has leases. */
    REJECT_WHILE_BUSY
}
