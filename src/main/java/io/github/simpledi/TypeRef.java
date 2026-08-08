package io.github.simpledi;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/** Captures a generic Java type for typed bindings and generic by-type lookup. */
public abstract class TypeRef<T> {
    private final Type type;

    /** Captures {@code T} from an anonymous subclass such as {@code new TypeRef<List<String>>() {}}. */
    protected TypeRef() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterized)
                || parameterized.getRawType() != TypeRef.class) {
            throw new IllegalStateException("TypeRef must be created as an anonymous parameterized subclass");
        }
        this.type = parameterized.getActualTypeArguments()[0];
    }

    private TypeRef(Type type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Returns a type reference for a non-generic class. */
    public static <T> TypeRef<T> of(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return new TypeRef<>(type) {};
    }

    /** Returns the captured reflective type. */
    public final Type type() {
        return type;
    }

    @Override
    public final String toString() {
        return type.getTypeName();
    }
}
