package io.github.simpledi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Value-only description of a reload failure.
 *
 * <p>No {@link Throwable}, application {@link Class}, stack trace element, or other
 * reflective object is retained. Reload results and event histories can therefore be
 * kept without preventing a retired generation's classloader from being collected.</p>
 */
public record ReloadFailure(String type, String message, List<String> details) {
    private static final int MAX_ENTRIES = 64;

    public ReloadFailure {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type");
        message = message == null ? "" : message;
        details = List.copyOf(details);
    }

    /** Creates a bounded value-only description, or {@code null} when failure is null. */
    public static ReloadFailure describe(Throwable failure) {
        if (failure == null) return null;
        List<String> details = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(failure);
        append(failure.getCause(), "caused by", details, seen);
        for (Throwable suppressed : failure.getSuppressed()) {
            append(suppressed, "suppressed", details, seen);
        }
        return new ReloadFailure(failure.getClass().getName(), safeMessage(failure), details);
    }


    /** Combines an existing value-only failure with an additional cleanup failure. */
    public static ReloadFailure combine(ReloadFailure primary, Throwable additional) {
        ReloadFailure next = describe(additional);
        if (primary == null) return next;
        if (next == null) return primary;
        List<String> combined = new ArrayList<>(primary.details());
        if (combined.size() < MAX_ENTRIES) combined.add("additional " + next.summary());
        for (String detail : next.details()) {
            if (combined.size() >= MAX_ENTRIES) break;
            combined.add(detail);
        }
        return new ReloadFailure(primary.type(), primary.message(), combined);
    }

    /** A compact human-readable summary without stack traces or application objects. */
    public String summary() {
        return message.isBlank() ? type : type + ": " + message;
    }

    private static void append(Throwable failure, String relation, List<String> result, Set<Throwable> seen) {
        if (failure == null || result.size() >= MAX_ENTRIES || !seen.add(failure)) return;
        result.add(relation + " " + failure.getClass().getName()
                + (safeMessage(failure).isBlank() ? "" : ": " + safeMessage(failure)));
        append(failure.getCause(), "caused by", result, seen);
        for (Throwable suppressed : failure.getSuppressed()) {
            append(suppressed, "suppressed", result, seen);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? "" : message;
    }
}
