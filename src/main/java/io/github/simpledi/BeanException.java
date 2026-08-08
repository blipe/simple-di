package io.github.simpledi;

/** Base exception for XML parsing, binding, construction, injection, and lifecycle failures. */
public class BeanException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient SourceLocation location;

    public BeanException(String message) {
        this(null, message, null);
    }

    public BeanException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public BeanException(SourceLocation location, String message) {
        this(location, message, null);
    }

    public BeanException(SourceLocation location, String message, Throwable cause) {
        super(format(location, message), cause);
        this.location = location;
    }

    public SourceLocation location() {
        return location;
    }

    private static String format(SourceLocation location, String message) {
        return location == null ? message : location + ": " + message;
    }
}
