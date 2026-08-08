package io.github.simpledi;

import java.nio.file.Path;

public record SourceLocation(Path source, int line, int column) {
    public SourceLocation {
        if (source == null) throw new IllegalArgumentException("source");
    }

    @Override
    public String toString() {
        return source + ":" + line + ":" + column;
    }
}
