package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.SourceLocation;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class Types {
    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "byte", byte.class, "short", short.class, "int", int.class,
            "long", long.class, "float", float.class, "double", double.class, "char", char.class,
            "void", void.class);
    private static final Map<Class<?>, Class<?>> WRAPPERS = new HashMap<>();

    static {
        WRAPPERS.put(boolean.class, Boolean.class);
        WRAPPERS.put(byte.class, Byte.class);
        WRAPPERS.put(short.class, Short.class);
        WRAPPERS.put(int.class, Integer.class);
        WRAPPERS.put(long.class, Long.class);
        WRAPPERS.put(float.class, Float.class);
        WRAPPERS.put(double.class, Double.class);
        WRAPPERS.put(char.class, Character.class);
        WRAPPERS.put(void.class, Void.class);
    }

    private Types() {}

    public static Class<?> load(String name, ClassLoader loader, SourceLocation location) {
        Class<?> primitive = PRIMITIVES.get(name);
        if (primitive != null) return primitive;
        if (name.endsWith("[]")) {
            Class<?> component = load(name.substring(0, name.length() - 2), loader, location);
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new BeanException(location, "Class not loadable: " + name + ": " + e, e);
        }
    }

    public static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) return c;
        if (type instanceof GenericArrayType a) {
            return java.lang.reflect.Array.newInstance(raw(a.getGenericComponentType()), 0).getClass();
        }
        if (type instanceof WildcardType w) {
            if (w.getUpperBounds().length > 0) return raw(w.getUpperBounds()[0]);
            if (w.getLowerBounds().length > 0) return raw(w.getLowerBounds()[0]);
        }
        if (type instanceof TypeVariable<?> v && v.getBounds().length > 0) return raw(v.getBounds()[0]);
        return Object.class;
    }

    public static Type argument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return effective(p.getActualTypeArguments()[index]);
        }
        return Object.class;
    }

    public static Type effective(Type type) {
        if (type instanceof WildcardType w) {
            if (w.getUpperBounds().length > 0) return w.getUpperBounds()[0];
            if (w.getLowerBounds().length > 0) return w.getLowerBounds()[0];
        }
        if (type instanceof TypeVariable<?> v && v.getBounds().length > 0) return v.getBounds()[0];
        return type;
    }

    public static Class<?> wrap(Class<?> type) {
        return type.isPrimitive() ? WRAPPERS.get(type) : type;
    }

    public static boolean sameBoxed(Class<?> a, Class<?> b) {
        return wrap(a).equals(wrap(b));
    }

    public static boolean assignable(Class<?> source, Class<?> target) {
        return sameBoxed(source, target) || (!target.isPrimitive() && target.isAssignableFrom(wrap(source)));
    }

    /** Full generic assignment check, including inherited type-variable substitution and wildcards. */
    public static boolean assignable(Type source, Type target) {
        if (target == Object.class) return true;
        if (source == null || target == null) return false;
        if (target instanceof Class<?> targetClass) {
            return assignable(raw(source), targetClass);
        }
        if (target instanceof WildcardType wildcard) return matchesWildcard(source, wildcard);
        if (target instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) if (!assignable(source, bound)) return false;
            return true;
        }
        if (target instanceof GenericArrayType targetArray) {
            Type sourceComponent = component(source);
            return sourceComponent != null && assignable(sourceComponent, targetArray.getGenericComponentType());
        }
        if (target instanceof ParameterizedType parameterizedTarget) {
            Class<?> targetRaw = raw(parameterizedTarget);
            Type sourceView = asSuperType(source, targetRaw);
            if (sourceView == null) return false;
            if (!(sourceView instanceof ParameterizedType parameterizedSource)) {
                // A raw source cannot prove a parameterized target.
                return false;
            }
            Type[] sourceArguments = parameterizedSource.getActualTypeArguments();
            Type[] targetArguments = parameterizedTarget.getActualTypeArguments();
            if (sourceArguments.length != targetArguments.length) return false;
            for (int i = 0; i < sourceArguments.length; i++) {
                if (!typeArgumentAssignable(sourceArguments[i], targetArguments[i])) return false;
            }
            return true;
        }
        return Objects.equals(source, target);
    }

    /** Resolves a member type declared on {@code declaringClass} in the supplied concrete context type. */
    public static Type resolve(Type memberType, Type contextType, Class<?> declaringClass) {
        Type declaringView = asSuperType(contextType, declaringClass);
        if (declaringView == null) return memberType;
        Map<TypeVariable<?>, Type> mapping = typeVariableMap(declaringView);
        return substitute(memberType, mapping);
    }

    /** Returns {@code source} viewed as {@code targetRaw}, preserving actual type arguments when known. */
    public static Type asSuperType(Type source, Class<?> targetRaw) {
        Class<?> sourceRaw = raw(source);
        if (!targetRaw.isAssignableFrom(sourceRaw)) return null;
        if (sourceRaw.equals(targetRaw)) return source;

        Map<TypeVariable<?>, Type> mapping = typeVariableMap(source);
        for (Type iface : sourceRaw.getGenericInterfaces()) {
            Type resolved = substitute(iface, mapping);
            Type found = asSuperType(resolved, targetRaw);
            if (found != null) return found;
        }
        Type parent = sourceRaw.getGenericSuperclass();
        if (parent != null) {
            Type resolved = substitute(parent, mapping);
            Type found = asSuperType(resolved, targetRaw);
            if (found != null) return found;
        }
        return null;
    }

    public static String display(Type type) {
        return type.getTypeName().replace("java.lang.", "");
    }

    private static boolean typeArgumentAssignable(Type source, Type target) {
        if (target instanceof WildcardType wildcard) return matchesWildcard(source, wildcard);
        if (target instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) if (!assignable(source, bound)) return false;
            return true;
        }
        // Generic Java parameters are invariant. Nested parameterized arguments therefore must be equivalent.
        return equivalent(source, target);
    }

    private static boolean equivalent(Type left, Type right) {
        if (Objects.equals(left, right)) return true;
        if (left instanceof ParameterizedType lp && right instanceof ParameterizedType rp) {
            if (!Objects.equals(lp.getRawType(), rp.getRawType())) return false;
            Type[] la = lp.getActualTypeArguments();
            Type[] ra = rp.getActualTypeArguments();
            if (la.length != ra.length) return false;
            for (int i = 0; i < la.length; i++) if (!equivalent(la[i], ra[i])) return false;
            return true;
        }
        if (left instanceof WildcardType lw && right instanceof WildcardType rw) {
            return Arrays.equals(lw.getUpperBounds(), rw.getUpperBounds())
                    && Arrays.equals(lw.getLowerBounds(), rw.getLowerBounds());
        }
        return false;
    }

    private static boolean matchesWildcard(Type source, WildcardType target) {
        for (Type upper : target.getUpperBounds()) {
            if (!assignable(source, upper)) return false;
        }
        for (Type lower : target.getLowerBounds()) {
            if (!assignable(lower, source)) return false;
        }
        return true;
    }

    private static Type component(Type type) {
        if (type instanceof Class<?> c && c.isArray()) return c.getComponentType();
        if (type instanceof GenericArrayType a) return a.getGenericComponentType();
        return null;
    }

    private static Map<TypeVariable<?>, Type> typeVariableMap(Type type) {
        LinkedHashMap<TypeVariable<?>, Type> result = new LinkedHashMap<>();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length; i++) result.put(variables[i], arguments[i]);
        }
        return result;
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> mapping) {
        if (type instanceof TypeVariable<?> variable) {
            Type replacement = mapping.get(variable);
            return replacement == null ? variable : substitute(replacement, mapping);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type owner = parameterized.getOwnerType() == null ? null : substitute(parameterized.getOwnerType(), mapping);
            Type[] args = parameterized.getActualTypeArguments();
            Type[] resolved = new Type[args.length];
            boolean changed = owner != parameterized.getOwnerType();
            for (int i = 0; i < args.length; i++) {
                resolved[i] = substitute(args[i], mapping);
                changed |= resolved[i] != args[i];
            }
            return changed ? new Parameterized(owner, parameterized.getRawType(), resolved) : parameterized;
        }
        if (type instanceof GenericArrayType array) {
            Type component = substitute(array.getGenericComponentType(), mapping);
            return component == array.getGenericComponentType() ? array : new GenericArray(component);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = substituteAll(wildcard.getUpperBounds(), mapping);
            Type[] lower = substituteAll(wildcard.getLowerBounds(), mapping);
            if (Arrays.equals(upper, wildcard.getUpperBounds()) && Arrays.equals(lower, wildcard.getLowerBounds())) {
                return wildcard;
            }
            return new Wildcard(upper, lower);
        }
        return type;
    }

    private static Type[] substituteAll(Type[] values, Map<TypeVariable<?>, Type> mapping) {
        Type[] result = new Type[values.length];
        for (int i = 0; i < values.length; i++) result[i] = substitute(values[i], mapping);
        return result;
    }

    private record Parameterized(Type ownerType, Type rawType, Type[] actualTypeArguments)
            implements ParameterizedType {
        private Parameterized {
            actualTypeArguments = actualTypeArguments.clone();
        }
        @Override public Type[] getActualTypeArguments() { return actualTypeArguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return ownerType; }
        @Override public String getTypeName() {
            return rawType.getTypeName() + "<" + Arrays.stream(actualTypeArguments)
                    .map(Type::getTypeName).collect(java.util.stream.Collectors.joining(", ")) + ">";
        }
    }

    private record GenericArray(Type genericComponentType) implements GenericArrayType {
        @Override public Type getGenericComponentType() { return genericComponentType; }
        @Override public String getTypeName() { return genericComponentType.getTypeName() + "[]"; }
    }

    private record Wildcard(Type[] upperBounds, Type[] lowerBounds) implements WildcardType {
        private Wildcard {
            upperBounds = upperBounds.clone();
            lowerBounds = lowerBounds.clone();
        }
        @Override public Type[] getUpperBounds() { return upperBounds.clone(); }
        @Override public Type[] getLowerBounds() { return lowerBounds.clone(); }
    }
}
