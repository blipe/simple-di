package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.ConversionContext;
import io.github.simpledi.SourceLocation;
import io.github.simpledi.internal.Definitions.ArrayValue;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.ConstantValue;
import io.github.simpledi.internal.Definitions.ListValue;
import io.github.simpledi.internal.Definitions.Literal;
import io.github.simpledi.internal.Definitions.MapEntry;
import io.github.simpledi.internal.Definitions.MapValue;
import io.github.simpledi.internal.Definitions.NestedBean;
import io.github.simpledi.internal.Definitions.NullValue;
import io.github.simpledi.internal.Definitions.OptionalRef;
import io.github.simpledi.internal.Definitions.OptionalValue;
import io.github.simpledi.internal.Definitions.PropertiesValue;
import io.github.simpledi.internal.Definitions.Ref;
import io.github.simpledi.internal.Definitions.SetValue;
import io.github.simpledi.internal.Definitions.SupplierRef;
import io.github.simpledi.internal.Definitions.ValueDef;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ValueResolver {
    private final ClassLoader classLoader;
    private final DefaultConverterRegistry converters;
    private final PropertyExpander expander;
    private final Function<String, Object> beanResolver;
    private final Function<String, Optional<Object>> optionalBeanResolver;
    private final Function<BeanDef, Object> anonymousResolver;

    public ValueResolver(ClassLoader classLoader,
                         DefaultConverterRegistry converters,
                         PropertyExpander expander,
                         Function<String, Object> beanResolver,
                         Function<String, Optional<Object>> optionalBeanResolver,
                         Function<BeanDef, Object> anonymousResolver) {
        this.classLoader = classLoader;
        this.converters = converters;
        this.expander = expander;
        this.beanResolver = beanResolver;
        this.optionalBeanResolver = optionalBeanResolver;
        this.anonymousResolver = anonymousResolver;
    }

    public Object resolve(ValueDef value, Type targetType) {
        Class<?> target = Types.raw(targetType);
        if (value instanceof NullValue nullValue) {
            if (target.isPrimitive()) {
                throw new BeanException(nullValue.location(), "null cannot be assigned to primitive " + target.getTypeName());
            }
            return null;
        }
        if (value instanceof Literal literal) return resolveLiteral(literal, targetType);
        if (value instanceof Ref ref) return adaptObject(beanResolver.apply(ref.beanId()), target, ref.location());
        if (value instanceof SupplierRef supplier) {
            Supplier<Object> result = () -> beanResolver.apply(supplier.beanId());
            return adaptObject(result, target, supplier.location());
        }
        if (value instanceof OptionalRef optionalRef) {
            Optional<Object> result = optionalBeanResolver.apply(optionalRef.beanId());
            return adaptObject(result, target, optionalRef.location());
        }
        if (value instanceof ConstantValue constant) return resolveConstant(constant, target);
        if (value instanceof NestedBean nested) return adaptObject(anonymousResolver.apply(nested.bean()), target, nested.location());
        if (value instanceof ListValue list) return resolveList(list, targetType);
        if (value instanceof SetValue set) return resolveSet(set, targetType);
        if (value instanceof MapValue map) return resolveMap(map, targetType);
        if (value instanceof PropertiesValue properties) return resolveProperties(properties, target);
        if (value instanceof ArrayValue array) return resolveArray(array, targetType);
        if (value instanceof OptionalValue optional) return resolveOptional(optional, targetType);
        throw new BeanException(value.location(), "Unsupported value kind " + value.getClass().getName());
    }

    public Object[] resolveAll(List<ValueDef> values, Type[] targetTypes) {
        Object[] result = new Object[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = resolve(values.get(i), targetTypes[i]);
        return result;
    }

    private Object resolveLiteral(Literal literal, Type targetType) {
        PropertyExpander.Expanded expanded = expander.expandValue(literal.text(), literal.location());
        String text = expanded.value();
        Class<?> target = Types.raw(targetType);
        if (literal.explicitType() == null) {
            return converters.convert(text, target,
                    new ConversionContext(classLoader, targetType, literal.location(), expanded.sensitive()));
        }
        Class<?> explicit = Types.load(literal.explicitType(), classLoader, literal.location());
        Object value = converters.convert(text, explicit,
                new ConversionContext(classLoader, explicit, literal.location(), expanded.sensitive()));
        return adaptExplicit(value, explicit, target, literal.location());
    }

    private Object resolveConstant(ConstantValue constant, Class<?> target) {
        Class<?> owner = Types.load(constant.className(), classLoader, constant.location());
        try {
            Field field = owner.getField(constant.field());
            if (!Modifier.isStatic(field.getModifiers())) {
                throw new BeanException(constant.location(), "Field is not static: "
                        + owner.getTypeName() + "." + constant.field());
            }
            return adaptObject(field.get(null), target, constant.location());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new BeanException(constant.location(), "Cannot read public static field "
                    + owner.getTypeName() + "." + constant.field(), e);
        }
    }

    private Object resolveList(ListValue list, Type targetType) {
        Class<?> target = Types.raw(targetType);
        Type elementType = Types.argument(targetType, 0);
        Collection<Object> collection = newCollection(target, false, list.location());
        for (ValueDef value : list.values()) addCollection(collection, resolve(value, elementType), list.location());
        if (!list.immutable()) return collection;
        if (collection instanceof List<?> values) return Collections.unmodifiableList(new ArrayList<>(values));
        return Collections.unmodifiableCollection(new ArrayList<>(collection));
    }

    private Object resolveSet(SetValue set, Type targetType) {
        Class<?> target = Types.raw(targetType);
        Type elementType = Types.argument(targetType, 0);
        Collection<Object> collection = newCollection(target, true, set.location());
        for (ValueDef value : set.values()) addCollection(collection, resolve(value, elementType), set.location());
        if (!set.immutable()) return collection;
        if (target == NavigableSet.class) return Collections.unmodifiableNavigableSet((NavigableSet<Object>) collection);
        if (target == SortedSet.class) return Collections.unmodifiableSortedSet((SortedSet<Object>) collection);
        return Collections.unmodifiableSet(new LinkedHashSet<>(collection));
    }

    private Object resolveMap(MapValue map, Type targetType) {
        Class<?> target = Types.raw(targetType);
        Type keyType = Types.argument(targetType, 0);
        Type valueType = Types.argument(targetType, 1);
        Map<Object, Object> result = newMap(target, map.location());
        for (MapEntry entry : map.entries()) {
            Object key = resolve(entry.key(), keyType);
            Object value = resolve(entry.value(), valueType);
            if (result.containsKey(key)) throw new BeanException(entry.location(), "Duplicate map key '" + key + "'");
            try {
                result.put(key, value);
            } catch (RuntimeException e) {
                throw new BeanException(entry.location(), "Cannot add map entry: " + e, e);
            }
        }
        if (!map.immutable()) return result;
        if (target == NavigableMap.class) return Collections.unmodifiableNavigableMap((NavigableMap<Object, Object>) result);
        if (target == SortedMap.class) return Collections.unmodifiableSortedMap((SortedMap<Object, Object>) result);
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private Object resolveProperties(PropertiesValue definition, Class<?> target) {
        Properties properties = definition.immutable() ? new FrozenProperties() : new Properties();
        for (Map.Entry<String, String> entry : definition.values().entrySet()) {
            properties.setProperty(expander.expand(entry.getKey(), definition.location()),
                    expander.expand(entry.getValue(), definition.location()));
        }
        if (properties instanceof FrozenProperties frozen) frozen.freeze();
        return adaptObject(properties, target, definition.location());
    }

    private Object resolveArray(ArrayValue array, Type targetType) {
        Class<?> target = Types.raw(targetType);
        Class<?> component;
        if (target.isArray()) {
            component = target.getComponentType();
            if (array.componentType() != null) {
                Class<?> explicit = Types.load(array.componentType(), classLoader, array.location());
                if (!Types.sameBoxed(explicit, component)) {
                    throw new BeanException(array.location(), "Array component-type " + explicit.getTypeName()
                            + " does not match target " + component.getTypeName());
                }
            }
        } else if (target == Object.class && array.componentType() != null) {
            component = Types.load(array.componentType(), classLoader, array.location());
        } else {
            throw new BeanException(array.location(), "<array> requires an array target or component-type");
        }
        Object result = Array.newInstance(component, array.values().size());
        for (int i = 0; i < array.values().size(); i++) {
            try {
                Array.set(result, i, resolve(array.values().get(i), component));
            } catch (RuntimeException e) {
                throw new BeanException(array.values().get(i).location(), "Cannot assign array element " + i, e);
            }
        }
        return result;
    }

    private Object resolveOptional(OptionalValue optional, Type targetType) {
        Class<?> target = Types.raw(targetType);
        if (target != Optional.class && target != Object.class) {
            throw new BeanException(optional.location(), "<optional> cannot be assigned to " + target.getTypeName());
        }
        if (optional.value() == null) return Optional.empty();
        return Optional.ofNullable(resolve(optional.value(), Types.argument(targetType, 0)));
    }

    private static Object adaptExplicit(Object value, Class<?> source, Class<?> target, SourceLocation location) {
        if (target == Object.class || Types.sameBoxed(source, target)) return value;
        Class<?> wrappedTarget = Types.wrap(target);
        if (wrappedTarget.isInstance(value)) return value;
        if (value instanceof Number number) return widen(number, Types.wrap(source), wrappedTarget, location);
        throw new BeanException(location, "Explicit " + source.getTypeName()
                + " value cannot be assigned to " + target.getTypeName());
    }

    private static Object adaptObject(Object value, Class<?> target, SourceLocation location) {
        if (value == null) {
            if (target.isPrimitive()) throw new BeanException(location, "null cannot be assigned to primitive " + target.getTypeName());
            return null;
        }
        if (target == Object.class || Types.wrap(target).isInstance(value)) return value;
        throw new BeanException(location, value.getClass().getTypeName() + " cannot be assigned to " + target.getTypeName());
    }

    private static Object widen(Number value, Class<?> source, Class<?> target, SourceLocation location) {
        List<Class<?>> order = List.of(Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);
        int sourceIndex = order.indexOf(source);
        int targetIndex = order.indexOf(target);
        if (sourceIndex < 0 || targetIndex < sourceIndex) {
            throw new BeanException(location, "Numeric narrowing from " + source.getTypeName()
                    + " to " + target.getTypeName() + " is not allowed");
        }
        if (target == Byte.class) return value.byteValue();
        if (target == Short.class) return value.shortValue();
        if (target == Integer.class) return value.intValue();
        if (target == Long.class) return value.longValue();
        if (target == Float.class) return value.floatValue();
        if (target == Double.class) return value.doubleValue();
        throw new BeanException(location, "Numeric value cannot be assigned to " + target.getTypeName());
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollection(Class<?> target, boolean set, SourceLocation location) {
        validateCollectionTarget(target, set, location);
        if (!set) {
            if (target == Object.class || target == Collection.class || target == Iterable.class || target == List.class) return new ArrayList<>();
            if (target == Queue.class || target == Deque.class) return new LinkedList<>();
        } else {
            if (target == SortedSet.class || target == NavigableSet.class) return new TreeSet<>();
            if (target == Set.class || target == Object.class) return new LinkedHashSet<>();
        }
        return (Collection<Object>) instantiateContainer(target, location, "collection");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMap(Class<?> target, SourceLocation location) {
        validateMapTarget(target, location);
        if (target == SortedMap.class || target == NavigableMap.class) return new TreeMap<>();
        if (target == ConcurrentMap.class) return new ConcurrentHashMap<>();
        if (target == Object.class || target == Map.class) return new LinkedHashMap<>();
        return (Map<Object, Object>) instantiateContainer(target, location, "map");
    }

    public static void validateCollectionTarget(Class<?> target, boolean set, SourceLocation location) {
        if (target == Object.class) return;
        if (target == Iterable.class && !set) return;
        if (!Collection.class.isAssignableFrom(target)) {
            throw new BeanException(location, "Collection value cannot be assigned to " + target.getTypeName());
        }
        if (target.isInterface() || Modifier.isAbstract(target.getModifiers())) {
            boolean supported = set
                    ? target == Set.class || target == SortedSet.class || target == NavigableSet.class
                    : target == Collection.class || target == List.class || target == Queue.class || target == Deque.class;
            if (!supported) throw new BeanException(location, "Unsupported abstract collection target " + target.getTypeName());
            return;
        }
        requireNoArgConstructor(target, location, "Collection type");
    }

    public static void validateMapTarget(Class<?> target, SourceLocation location) {
        if (target == Object.class) return;
        if (!Map.class.isAssignableFrom(target)) {
            throw new BeanException(location, "Map value cannot be assigned to " + target.getTypeName());
        }
        if (target.isInterface() || Modifier.isAbstract(target.getModifiers())) {
            boolean supported = target == Map.class || target == SortedMap.class || target == NavigableMap.class
                    || target == ConcurrentMap.class;
            if (!supported) throw new BeanException(location, "Unsupported abstract map target " + target.getTypeName());
            return;
        }
        requireNoArgConstructor(target, location, "Map type");
    }

    private static void requireNoArgConstructor(Class<?> target, SourceLocation location, String label) {
        try {
            target.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new BeanException(location, label + " " + target.getTypeName()
                    + " requires a public no-argument constructor", e);
        }
    }

    private static Object instantiateContainer(Class<?> target, SourceLocation location, String kind) {
        try {
            Constructor<?> constructor = target.getConstructor();
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new BeanException(location, "Cannot instantiate " + kind + " type " + target.getTypeName(), e);
        }
    }

    private static void addCollection(Collection<Object> collection, Object value, SourceLocation location) {
        try {
            collection.add(value);
        } catch (RuntimeException e) {
            throw new BeanException(location, "Cannot add collection value '" + value + "': " + e, e);
        }
    }

    /** A practical read-only Properties implementation for immutable="true". */
    private static final class FrozenProperties extends Properties {
        private static final long serialVersionUID = 1L;
        private boolean frozen;

        void freeze() { frozen = true; }
        private void check() { if (frozen) throw new UnsupportedOperationException("Properties are immutable"); }

        @Override public synchronized Object put(Object key, Object value) { check(); return super.put(key, value); }
        @Override public synchronized Object remove(Object key) { check(); return super.remove(key); }
        @Override public synchronized void putAll(Map<?, ?> values) { check(); super.putAll(values); }
        @Override public synchronized void clear() { check(); super.clear(); }
        @Override public synchronized Object putIfAbsent(Object key, Object value) { check(); return super.putIfAbsent(key, value); }
        @Override public synchronized boolean remove(Object key, Object value) { check(); return super.remove(key, value); }
        @Override public synchronized boolean replace(Object key, Object oldValue, Object newValue) { check(); return super.replace(key, oldValue, newValue); }
        @Override public synchronized Object replace(Object key, Object value) { check(); return super.replace(key, value); }
        @Override public synchronized void replaceAll(java.util.function.BiFunction<? super Object, ? super Object, ?> function) { check(); super.replaceAll(function); }
        @Override public synchronized Object computeIfAbsent(Object key, java.util.function.Function<? super Object, ?> mappingFunction) { check(); return super.computeIfAbsent(key, mappingFunction); }
        @Override public synchronized Object computeIfPresent(Object key, java.util.function.BiFunction<? super Object, ? super Object, ?> remappingFunction) { check(); return super.computeIfPresent(key, remappingFunction); }
        @Override public synchronized Object compute(Object key, java.util.function.BiFunction<? super Object, ? super Object, ?> remappingFunction) { check(); return super.compute(key, remappingFunction); }
        @Override public synchronized Object merge(Object key, Object value, java.util.function.BiFunction<? super Object, ? super Object, ?> remappingFunction) { check(); return super.merge(key, value, remappingFunction); }
        @Override public synchronized Set<Object> keySet() { return Collections.unmodifiableSet(new LinkedHashSet<>(super.keySet())); }
        @Override public synchronized Set<Map.Entry<Object, Object>> entrySet() {
            LinkedHashSet<Map.Entry<Object, Object>> copy = new LinkedHashSet<>();
            for (Map.Entry<Object, Object> entry : super.entrySet()) copy.add(Map.entry(entry.getKey(), entry.getValue()));
            return Collections.unmodifiableSet(copy);
        }
        @Override public synchronized Collection<Object> values() { return Collections.unmodifiableList(new ArrayList<>(super.values())); }
    }
}
