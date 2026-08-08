package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.ConversionContext;
import io.github.simpledi.SourceLocation;
import io.github.simpledi.internal.Definitions.ArrayValue;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.CallDef;
import io.github.simpledi.internal.Definitions.ConstantValue;
import io.github.simpledi.internal.Definitions.Document;
import io.github.simpledi.internal.Definitions.InjectionDef;
import io.github.simpledi.internal.Definitions.ListValue;
import io.github.simpledi.internal.Definitions.Literal;
import io.github.simpledi.internal.Definitions.MapEntry;
import io.github.simpledi.internal.Definitions.MapValue;
import io.github.simpledi.internal.Definitions.NestedBean;
import io.github.simpledi.internal.Definitions.OptionalRef;
import io.github.simpledi.internal.Definitions.OptionalValue;
import io.github.simpledi.internal.Definitions.PropertiesValue;
import io.github.simpledi.internal.Definitions.PropertyDef;
import io.github.simpledi.internal.Definitions.Ref;
import io.github.simpledi.internal.Definitions.SetValue;
import io.github.simpledi.internal.Definitions.SupplierRef;
import io.github.simpledi.internal.Definitions.ValueDef;
import io.github.simpledi.internal.ExecutableResolver.BoundExecutable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

/** Pre-binds the complete graph before any user constructor, factory, setter, or init method executes. */
public final class GraphCompiler {
    public sealed interface CompiledInjection permits CompiledProperty, CompiledCall {
        SourceLocation location();
    }

    public record CompiledProperty(PropertyDef definition, BoundExecutable binding)
            implements CompiledInjection {
        @Override public SourceLocation location() { return definition.location(); }
    }

    public record CompiledCall(CallDef definition, BoundExecutable binding)
            implements CompiledInjection {
        @Override public SourceLocation location() { return definition.location(); }
    }

    public record CompiledBean(
            BeanDef definition,
            Type declaredType,
            Class<?> productType,
            BoundExecutable creator,
            String factoryBean,
            List<CompiledInjection> injections,
            Method initMethod,
            Method destroyMethod) {}

    private final Document document;
    private final ClassLoader classLoader;
    private final DefaultConverterRegistry converters;
    private final PropertyExpander expander;
    private final Map<String, Type> externalTypes;
    private final ExecutableResolver resolver;
    private final IdentityHashMap<BeanDef, CompiledBean> byDefinition = new IdentityHashMap<>();
    private final LinkedHashMap<String, CompiledBean> topLevel = new LinkedHashMap<>();
    private boolean compiled;
    private Map<String, CompiledBean> compiledView;

    public GraphCompiler(Document document, ClassLoader classLoader,
                         DefaultConverterRegistry converters, PropertyExpander expander) {
        this(document, classLoader, converters, expander, Map.of());
    }

    public GraphCompiler(Document document, ClassLoader classLoader,
                         DefaultConverterRegistry converters, PropertyExpander expander,
                         Map<String, Type> externalTypes) {
        this.document = document;
        this.classLoader = classLoader;
        this.converters = converters;
        this.expander = expander;
        this.externalTypes = Collections.unmodifiableMap(new LinkedHashMap<>(externalTypes));
        this.resolver = new ExecutableResolver(classLoader, converters, document, externalTypes);
    }

    public synchronized Map<String, CompiledBean> compile() {
        if (compiled) return compiledView;
        new DependencyValidator().validate(document, externalTypes.keySet());
        for (Map.Entry<String, BeanDef> entry : document.beans().entrySet()) {
            topLevel.put(entry.getKey(), compileBean(entry.getValue()));
        }
        compiled = true;
        compiledView = Collections.unmodifiableMap(new LinkedHashMap<>(topLevel));
        return compiledView;
    }


    /** Side-effect-free best-effort compilation that collects independent bean binding failures. */
    public Inspection inspect() {
        LinkedHashMap<String, CompiledBean> successful = new LinkedHashMap<>();
        List<BeanException> failures = new ArrayList<>();
        try {
            new DependencyValidator().validate(document, externalTypes.keySet());
        } catch (BeanException failure) {
            addDistinct(failures, failure);
        }
        for (Map.Entry<String, BeanDef> entry : document.beans().entrySet()) {
            GraphCompiler isolated = new GraphCompiler(document, classLoader, converters, expander, externalTypes);
            try {
                successful.put(entry.getKey(), isolated.compileBean(entry.getValue()));
            } catch (BeanException failure) {
                addDistinct(failures, failure);
            }
        }
        return new Inspection(Collections.unmodifiableMap(successful), List.copyOf(failures));
    }

    public record Inspection(Map<String, CompiledBean> beans, List<BeanException> failures) {}

    private static void addDistinct(List<BeanException> failures, BeanException candidate) {
        boolean duplicate = failures.stream().anyMatch(existing ->
                Objects.equals(existing.location(), candidate.location())
                        && existing.getMessage().equals(candidate.getMessage()));
        if (!duplicate) failures.add(candidate);
    }

    public CompiledBean bean(String canonicalId) {
        compile();
        CompiledBean bean = topLevel.get(canonicalId);
        if (bean == null) throw new BeanException("Unknown bean '" + canonicalId + "'");
        return bean;
    }

    public CompiledBean nested(BeanDef definition) {
        compile();
        CompiledBean bean = byDefinition.get(definition);
        if (bean == null) throw new BeanException(definition.location(), "Nested bean was not precompiled");
        return bean;
    }

    public ExecutableResolver resolver() {
        return resolver;
    }

    private CompiledBean compileBean(BeanDef definition) {
        CompiledBean existing = byDefinition.get(definition);
        if (existing != null) return existing;
        Class<?> productType = Types.load(definition.className(), classLoader, definition.location());
        if (productType.isPrimitive() || productType == void.class || productType.isArray()) {
            throw new BeanException(definition.location(), "Bean class must be a concrete reference type: "
                    + productType.getTypeName());
        }
        if (!Modifier.isPublic(productType.getModifiers())) {
            throw new BeanException(definition.location(), "Bean class is not public: " + productType.getTypeName());
        }

        BoundExecutable creator;
        String factoryBean = null;
        if (definition.factory() == null) {
            creator = resolver.constructor(productType, definition.constructorArgs(),
                    definition.constructorSignature(), definition.location());
            validateArguments(definition.constructorArgs(), creator);
        } else if (definition.factory().factoryBean() != null) {
            factoryBean = resolver.canonical(definition.factory().factoryBean(), definition.factory().location());
            Type ownerType = resolver.declaredType(factoryBean, definition.factory().location());
            Class<?> owner = Types.raw(ownerType);
            creator = resolver.instanceFactory(ownerType, owner, productType, definition.factory().method(),
                    definition.factory().args(), definition.factory().signature(), definition.factory().location());
            validateArguments(definition.factory().args(), creator);
        } else {
            Class<?> owner = definition.factory().ownerClassName() == null ? productType
                    : Types.load(definition.factory().ownerClassName(), classLoader, definition.factory().location());
            creator = resolver.staticFactory(owner, productType, definition.factory().method(),
                    definition.factory().args(), definition.factory().signature(), definition.factory().location());
            validateArguments(definition.factory().args(), creator);
        }
        verifyAccessible(creator.executable(), definition.location());

        // Install a provisional plan so pathological recursive nested definitions fail cleanly rather than recurse forever.
        CompiledBean provisional = new CompiledBean(definition, productType, productType, creator, factoryBean,
                List.of(), null, null);
        byDefinition.put(definition, provisional);

        List<CompiledInjection> injections = new ArrayList<>();
        for (InjectionDef injection : definition.injections()) {
            if (injection instanceof PropertyDef property) {
                BoundExecutable binding = resolver.setter(productType, property.name(), property.value(), property.location());
                validateValue(property.value(), binding.inputTypes()[0]);
                verifyAccessible(binding.executable(), property.location());
                injections.add(new CompiledProperty(property, binding));
            } else if (injection instanceof CallDef call) {
                BoundExecutable binding = resolver.method(productType, call.method(), call.args(), call.signature(),
                        call.location());
                validateArguments(call.args(), binding);
                verifyAccessible(binding.executable(), call.location());
                injections.add(new CompiledCall(call, binding));
            }
        }

        Method init = definition.initMethod() == null ? null
                : resolver.lifecycle(productType, definition.initMethod(), definition.location());
        Method destroy = definition.destroyMethod() == null ? null
                : resolver.lifecycle(productType, definition.destroyMethod(), definition.location());
        if (init != null) verifyAccessible(init, definition.location());
        if (destroy != null) verifyAccessible(destroy, definition.location());

        CompiledBean result = new CompiledBean(definition, productType, productType, creator, factoryBean,
                List.copyOf(injections), init, destroy);
        byDefinition.put(definition, result);
        return result;
    }

    private void validateArguments(List<Definitions.ArgumentDef> arguments, BoundExecutable binding) {
        List<ValueDef> values = binding.orderedValues(arguments);
        Type[] targetTypes = binding.inputTypes();
        if (values.size() != targetTypes.length) {
            throw new IllegalStateException("Binding argument count mismatch");
        }
        for (int i = 0; i < values.size(); i++) validateValue(values.get(i), targetTypes[i]);
    }

    private void validateValue(ValueDef value, Type targetType) {
        int compatibility = resolver.compatibility(value, targetType);
        if (compatibility < 0) {
            throw new BeanException(value.location(), value.getClass().getSimpleName()
                    + " cannot be assigned to " + Types.display(targetType));
        }
        Class<?> target = Types.raw(targetType);
        if (value instanceof Literal literal) {
            PropertyExpander.Expanded expanded = expander.expandValue(literal.text(), literal.location());
            Class<?> conversionType = literal.explicitType() == null ? target
                    : Types.load(literal.explicitType(), classLoader, literal.location());
            if (converters.isBuiltIn(conversionType) || conversionType == String.class
                    || conversionType == Class.class || conversionType.isEnum()) {
                converters.convert(expanded.value(), conversionType,
                        new ConversionContext(classLoader, conversionType, literal.location(), expanded.sensitive()));
            }
        } else if (value instanceof Ref ref) {
            verifyGenericReference(ref.beanId(), targetType, ref.location());
        } else if (value instanceof SupplierRef supplier) {
            if (target != Supplier.class && target != Object.class) {
                throw new BeanException(supplier.location(), "<supplier> requires Supplier target");
            }
            if (target == Supplier.class) verifyGenericReference(supplier.beanId(), Types.argument(targetType, 0), supplier.location());
        } else if (value instanceof OptionalRef optionalRef) {
            if (target != Optional.class && target != Object.class) {
                throw new BeanException(optionalRef.location(), "<optional-ref> requires Optional target");
            }
            if (target == Optional.class && hasBean(optionalRef.beanId())) {
                verifyGenericReference(optionalRef.beanId(), Types.argument(targetType, 0), optionalRef.location());
            }
        } else if (value instanceof ConstantValue constant) {
            resolver.constantType(constant);
        } else if (value instanceof NestedBean nested) {
            CompiledBean nestedBean = compileBean(nested.bean());
            if (!target.isPrimitive() && target != Object.class && !target.isAssignableFrom(nestedBean.productType())) {
                throw new BeanException(nested.location(), nestedBean.productType().getTypeName()
                        + " cannot be assigned to " + target.getTypeName());
            }
        } else if (value instanceof ListValue list) {
            ValueResolver.validateCollectionTarget(target, false, list.location());
            Type elementType = Types.argument(targetType, 0);
            list.values().forEach(v -> validateValue(v, elementType));
            if (list.immutable() && !(target == Object.class || target == java.util.List.class
                    || target == java.util.Collection.class || target == Iterable.class)) {
                throw new BeanException(list.location(), "immutable list supports Object, List, Collection, or Iterable targets, not "
                        + target.getTypeName());
            }
        } else if (value instanceof SetValue set) {
            ValueResolver.validateCollectionTarget(target, true, set.location());
            Type elementType = Types.argument(targetType, 0);
            set.values().forEach(v -> validateValue(v, elementType));
            if (set.immutable() && !(target == Object.class || target == java.util.Set.class
                    || target == java.util.SortedSet.class || target == java.util.NavigableSet.class)) {
                throw new BeanException(set.location(), "immutable set supports Object, Set, SortedSet, or NavigableSet targets, not "
                        + target.getTypeName());
            }
        } else if (value instanceof MapValue map) {
            ValueResolver.validateMapTarget(target, map.location());
            Type keyType = Types.argument(targetType, 0);
            Type valueType = Types.argument(targetType, 1);
            for (MapEntry entry : map.entries()) {
                validateValue(entry.key(), keyType);
                validateValue(entry.value(), valueType);
            }
            if (map.immutable() && !(target == Object.class || target == java.util.Map.class
                    || target == java.util.SortedMap.class || target == java.util.NavigableMap.class)) {
                throw new BeanException(map.location(), "immutable map supports Object, Map, SortedMap, or NavigableMap targets, not "
                        + target.getTypeName());
            }
        } else if (value instanceof PropertiesValue properties) {
            for (Map.Entry<String, String> entry : properties.values().entrySet()) {
                expander.expand(entry.getKey(), properties.location());
                expander.expand(entry.getValue(), properties.location());
            }
        } else if (value instanceof ArrayValue array) {
            Class<?> component = target.isArray() ? target.getComponentType()
                    : Types.load(array.componentType(), classLoader, array.location());
            array.values().forEach(v -> validateValue(v, component));
        } else if (value instanceof OptionalValue optional && optional.value() != null) {
            validateValue(optional.value(), Types.argument(targetType, 0));
        }
    }

    private void verifyGenericReference(String id, Type targetType, SourceLocation location) {
        Type source = resolver.declaredType(id, location);
        if (!Types.assignable(source, targetType)) {
            throw new BeanException(location, Types.display(source) + " cannot be assigned to " + Types.display(targetType));
        }
    }

    private boolean hasBean(String id) {
        return document.beans().containsKey(id) || document.aliases().containsKey(id) || externalTypes.containsKey(id);
    }

    private static void verifyAccessible(Executable executable, SourceLocation location) {
        Class<?> declaring = executable.getDeclaringClass();
        if (!Modifier.isPublic(declaring.getModifiers()) || !Modifier.isPublic(executable.getModifiers())) {
            throw new BeanException(location, "Executable or declaring class is not public: "
                    + ExecutableResolver.signature(executable));
        }
        Module target = declaring.getModule();
        Module caller = GraphCompiler.class.getModule();
        if (target.isNamed() && target != caller && !target.isExported(declaring.getPackageName(), caller)) {
            throw new BeanException(location, "Package '" + declaring.getPackageName() + "' in module '"
                    + target.getName() + "' is not exported to module '" + caller.getName() + "': "
                    + ExecutableResolver.signature(executable));
        }
    }
}
