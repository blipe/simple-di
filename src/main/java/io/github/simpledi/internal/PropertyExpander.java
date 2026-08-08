package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.SourceLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** Expands escaped, nested, and recursively referenced ${name:default} placeholders. */
public final class PropertyExpander {
    public record Expanded(String value, boolean sensitive) {}

    private static final int MAX_EXPANSION_DEPTH = 64;
    private final PropertyResolver properties;

    /** Compatibility constructor for a single map source. */
    public PropertyExpander(Map<String, String> properties) {
        this(new PropertyResolver(java.util.List.of(io.github.simpledi.PropertySource.of("builder", properties))));
    }

    public PropertyExpander(PropertyResolver properties) {
        this.properties = properties;
    }

    public String expand(String input, SourceLocation location) {
        return expandValue(input, location).value();
    }

    public Expanded expandValue(String input, SourceLocation location) {
        return expand(input, location, new ArrayDeque<>(), 0);
    }

    public PropertyResolver resolver() {
        return properties;
    }

    private Expanded expand(String input, SourceLocation location, Deque<String> stack, int depth) {
        if (depth > MAX_EXPANSION_DEPTH) {
            throw new BeanException(location, "Property expansion exceeds " + MAX_EXPANSION_DEPTH + " levels");
        }
        StringBuilder out = new StringBuilder(input.length());
        boolean sensitive = false;
        for (int i = 0; i < input.length();) {
            if (input.charAt(i) == '\\' && i + 2 < input.length()
                    && input.charAt(i + 1) == '$' && input.charAt(i + 2) == '{') {
                out.append("${");
                i += 3;
                continue;
            }
            if (input.charAt(i) != '$' || i + 1 >= input.length() || input.charAt(i + 1) != '{') {
                out.append(input.charAt(i++));
                continue;
            }
            int end = placeholderEnd(input, i + 2, location);
            String body = input.substring(i + 2, end);
            int colon = topLevelColon(body);
            String keyExpression = colon < 0 ? body : body.substring(0, colon);
            String fallback = colon < 0 ? null : body.substring(colon + 1);
            Expanded expandedKey = expand(keyExpression, location, stack, depth + 1);
            String key = expandedKey.value();
            sensitive |= expandedKey.sensitive();
            if (key.isEmpty()) throw new BeanException(location, "Empty property placeholder");
            if (stack.contains(key)) {
                StringBuilder cycle = new StringBuilder();
                for (String item : stack) cycle.append(item).append(" -> ");
                cycle.append(key);
                throw new BeanException(location, "Circular property expansion: " + cycle);
            }
            PropertyResolver.Resolved found = properties.find(key).orElse(null);
            String value;
            boolean valueSensitive;
            if (found == null) {
                if (fallback == null) throw new BeanException(location, "Missing property '" + key + "'");
                Expanded expandedFallback = expand(fallback, location, stack, depth + 1);
                value = expandedFallback.value();
                valueSensitive = expandedFallback.sensitive();
                properties.recordDefault(key, valueSensitive);
            } else {
                value = found.value();
                valueSensitive = found.sensitive();
                stack.addLast(key);
                try {
                    Expanded nested = expand(value, location, stack, depth + 1);
                    value = nested.value();
                    valueSensitive |= nested.sensitive();
                } finally {
                    stack.removeLast();
                }
            }
            out.append(value);
            sensitive |= valueSensitive;
            i = end + 1;
        }
        return new Expanded(out.toString(), sensitive);
    }

    private static int placeholderEnd(String input, int start, SourceLocation location) {
        int nested = 0;
        for (int i = start; i < input.length(); i++) {
            if (input.charAt(i) == '$' && i + 1 < input.length() && input.charAt(i + 1) == '{') {
                nested++;
                i++;
            } else if (input.charAt(i) == '}') {
                if (nested == 0) return i;
                nested--;
            }
        }
        throw new BeanException(location, "Unclosed property placeholder");
    }

    private static int topLevelColon(String body) {
        int nested = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '$' && i + 1 < body.length() && body.charAt(i + 1) == '{') {
                nested++;
                i++;
            } else if (body.charAt(i) == '}') {
                nested--;
            } else if (body.charAt(i) == ':' && nested == 0) {
                return i;
            }
        }
        return -1;
    }
}
