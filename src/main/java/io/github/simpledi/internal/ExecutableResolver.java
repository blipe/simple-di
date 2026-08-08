package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.SourceLocation;
import io.github.simpledi.internal.Definitions.ArgumentDef;
import io.github.simpledi.internal.Definitions.ArrayValue;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.ConstantValue;
import io.github.simpledi.internal.Definitions.Document;
import io.github.simpledi.internal.Definitions.ListValue;
import io.github.simpledi.internal.Definitions.Literal;
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
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Deterministic executable binding, including Java varargs and specificity tie-breaking. */
public final class ExecutableResolver {
    public record BoundExecutable(
            Executable executable,
            Type[] inputTypes,
            int[] sourceIndexes,
            boolean varArgs,
            boolean directVarargsArray,
            int fixedCount) {

        public BoundExecutable {
            inputTypes = inputTypes.clone();
            sourceIndexes = sourceIndexes.clone();
        }

        public List<ValueDef> orderedValues(List<ArgumentDef> arguments) {
            List<ValueDef> result = new ArrayList<>(sourceIndexes.length);
            for (int sourceIndex : sourceIndexes) result.add(arguments.get(sourceIndex).value());
            return List.copyOf(result);
        }

        public Object[] pack(Object[] values) {
            if (!varArgs || directVarargsArray) return values;
            Class<?> arrayType = executable.getParameterTypes()[executable.getParameterCount() - 1];
            Class<?> component = arrayType.getComponentType();
            Object packed = Array.newInstance(component, values.length - fixedCount);
            for (int i = fixedCount; i < values.length; i++) Array.set(packed, i - fixedCount, values[i]);
            Object[] result = new Object[fixedCount + 1];
            System.arraycopy(values, 0, result, 0, fixedCount);
            result[fixedCount] = packed;
            return result;
        }
    }

    private record Candidate(BoundExecutable binding, int total, int[] scores) {}

    private final ClassLoader classLoader;
    private final DefaultConverterRegistry converters;
    private final Document document;
    private final Map<String, Type> externalTypes;

    public ExecutableResolver(ClassLoader classLoader, DefaultConverterRegistry converters, Document document) {
        this(classLoader, converters, document, Map.of());
    }

    public ExecutableResolver(ClassLoader classLoader, DefaultConverterRegistry converters, Document document,
                              Map<String, Type> externalTypes) {
        this.classLoader = classLoader;
        this.converters = converters;
        this.document = document;
        this.externalTypes = Map.copyOf(externalTypes);
    }

    public BoundExecutable constructor(Class<?> type, List<ArgumentDef> args, String exactSignature,
                                       SourceLocation location) {
        List<Executable> candidates = Arrays.stream(type.getConstructors())
                .filter(c -> !c.isSynthetic())
                .map(c -> (Executable) c)
                .toList();
        return select("constructor for " + type.getTypeName(), candidates, args, exactSignature, location, type);
    }

    public BoundExecutable staticFactory(Class<?> owner, Class<?> productType, String name,
                                         List<ArgumentDef> args, String exactSignature,
                                         SourceLocation location) {
        List<Executable> candidates = Arrays.stream(owner.getMethods())
                .filter(m -> m.getName().equals(name) && Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isBridge() && !m.isSynthetic())
                .filter(m -> m.getReturnType() != void.class && productType.isAssignableFrom(m.getReturnType()))
                .map(m -> (Executable) m)
                .toList();
        return select("static factory " + owner.getTypeName() + "." + name, candidates, args,
                exactSignature, location, owner);
    }

    public BoundExecutable instanceFactory(Type ownerType, Class<?> owner, Class<?> productType, String name,
                                           List<ArgumentDef> args, String exactSignature,
                                           SourceLocation location) {
        List<Executable> candidates = methods(owner, name, false).stream()
                .filter(m -> m.getReturnType() != void.class)
                .filter(m -> Types.assignable(Types.resolve(m.getGenericReturnType(), ownerType, m.getDeclaringClass()), productType))
                .map(m -> (Executable) m)
                .toList();
        return select("instance factory " + Types.display(ownerType) + "." + name, candidates, args,
                exactSignature, location, ownerType);
    }

    public BoundExecutable setter(Class<?> type, String property, ValueDef value, SourceLocation location) {
        String methodName = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        List<Executable> candidates = methods(type, methodName, false).stream()
                .filter(m -> m.getParameterCount() == 1 && !m.isVarArgs())
                .map(m -> (Executable) m)
                .toList();
        return select("setter " + type.getTypeName() + "." + methodName,
                candidates, List.of(new ArgumentDef(null, null, value, location)), null, location, type);
    }

    public BoundExecutable method(Class<?> type, String name, List<ArgumentDef> args, String exactSignature,
                                  SourceLocation location) {
        List<Executable> candidates = methods(type, name, false).stream().map(m -> (Executable) m).toList();
        return select("method " + type.getTypeName() + "." + name, candidates, args,
                exactSignature, location, type);
    }

    public Method lifecycle(Class<?> type, String methodName, SourceLocation location) {
        List<Method> methods = methods(type, methodName, false).stream()
                .filter(m -> m.getParameterCount() == 0)
                .toList();
        if (methods.isEmpty()) {
            throw new BeanException(location,
                    "No public zero-argument instance method " + type.getTypeName() + "." + methodName + "()");
        }
        return mostSpecificMethod(type, methods);
    }

    public int compatibility(ValueDef value, Type targetType) {
        Class<?> target = Types.raw(targetType);
        if (value instanceof NullValue) return target.isPrimitive() ? -1 : 5;
        if (value instanceof Literal literal) return literalCompatibility(literal, target);
        if (value instanceof Ref ref) return objectCompatibility(declaredType(ref.beanId(), ref.location()), targetType);
        if (value instanceof SupplierRef supplier) {
            declaredType(supplier.beanId(), supplier.location());
            return target == Supplier.class ? 0 : target == Object.class ? 4 : -1;
        }
        if (value instanceof OptionalRef) return target == Optional.class ? 0 : target == Object.class ? 4 : -1;
        if (value instanceof ConstantValue constant) return objectCompatibility(constantType(constant), targetType);
        if (value instanceof NestedBean nested) {
            Class<?> source = Types.load(nested.bean().className(), classLoader, nested.location());
            return objectCompatibility(source, targetType);
        }
        if (value instanceof ListValue) return collectionCompatibility(target, false);
        if (value instanceof SetValue) return collectionCompatibility(target, true);
        if (value instanceof MapValue) return mapCompatibility(target);
        if (value instanceof PropertiesValue) {
            return target == Object.class ? 4
                    : (target.isAssignableFrom(java.util.Properties.class) ? 0 : -1);
        }
        if (value instanceof OptionalValue) {
            if (target == Object.class) return 4;
            return target == Optional.class ? 0 : -1;
        }
        if (value instanceof ArrayValue array) {
            if (target == Object.class && array.componentType() != null) return 4;
            if (!target.isArray()) return -1;
            if (array.componentType() == null) return 0;
            Class<?> explicit = Types.load(array.componentType(), classLoader, array.location());
            return Types.sameBoxed(explicit, target.getComponentType()) ? 0 : -1;
        }
        return -1;
    }

    public Type declaredType(String id, SourceLocation location) {
        String canonical = canonical(id, location);
        Type external = externalTypes.get(canonical);
        if (external != null) return external;
        BeanDef bean = document.beans().get(canonical);
        return Types.load(bean.className(), classLoader, bean.location());
    }

    public String canonical(String id, SourceLocation location) {
        if (document.beans().containsKey(id) || externalTypes.containsKey(id)) return id;
        String canonical = document.aliases().get(id);
        if (canonical != null) return canonical;
        throw new BeanException(location, "Unknown bean reference '" + id + "'");
    }

    public Type constantType(ConstantValue constant) {
        Class<?> owner = Types.load(constant.className(), classLoader, constant.location());
        try {
            Field field = owner.getField(constant.field());
            if (!Modifier.isStatic(field.getModifiers())) {
                throw new BeanException(constant.location(), "Constant field is not static: "
                        + owner.getTypeName() + "." + constant.field());
            }
            return field.getGenericType();
        } catch (NoSuchFieldException e) {
            throw new BeanException(constant.location(), "No public field "
                    + owner.getTypeName() + "." + constant.field(), e);
        }
    }

    private BoundExecutable select(String description, List<Executable> executables,
                                   List<ArgumentDef> args, String exactSignature,
                                   SourceLocation location, Type contextType) {
        if (executables.isEmpty()) {
            throw new BeanException(location, "No public matching " + description);
        }
        List<Executable> selected = exactSignature == null ? executables
                : executables.stream().filter(value -> matchesExactSignature(value, exactSignature, location)).toList();
        if (selected.isEmpty()) {
            throw new BeanException(location, "No " + description + " has exact signature " + exactSignature
                    + ". Candidates:\n" + candidateSignatures(executables));
        }
        List<Candidate> matches = new ArrayList<>();
        for (Executable executable : selected) addCandidates(executable, contextType, args, matches);
        if (matches.isEmpty()) {
            boolean named = args.stream().anyMatch(argument -> argument.name() != null);
            if (named && selected.stream().allMatch(executable -> parameterNames(executable) == null)) {
                throw new BeanException(location, "Cannot bind named arguments for " + description
                        + ": parameter names are unavailable. Compile the target with -parameters,"
                        + " use record component names, or use arg index=\"...\".");
            }
            throw new BeanException(location, noMatchMessage(description, selected, args));
        }

        matches.sort(Comparator.comparingInt(Candidate::total)
                .thenComparing(c -> Arrays.toString(c.scores()))
                .thenComparing(c -> signature(c.binding().executable())));
        Candidate best = matches.get(0);
        List<Candidate> tied = matches.stream()
                .filter(c -> c.total() == best.total() && Arrays.equals(c.scores(), best.scores()))
                .toList();
        if (tied.size() == 1) return best.binding();

        List<Candidate> maximal = tied.stream()
                .filter(candidate -> tied.stream().noneMatch(other -> other != candidate && moreSpecific(other, candidate)))
                .toList();
        if (maximal.size() == 1) return maximal.get(0).binding();

        StringBuilder b = new StringBuilder("Ambiguous ").append(description).append(". Candidates:\n");
        maximal.forEach(c -> b.append("  ").append(signature(c.binding().executable())).append('\n'));
        b.append("Add type=\"...\" to ambiguous literals or pin signature=\"(...)\".");
        throw new BeanException(location, b.toString());
    }

    private void addCandidates(Executable executable, Type contextType, List<ArgumentDef> args,
                               List<Candidate> matches) {
        Type[] generic = executable.getGenericParameterTypes();
        Type[] parameters = new Type[generic.length];
        for (int i = 0; i < generic.length; i++) {
            parameters[i] = Types.resolve(generic[i], contextType, executable.getDeclaringClass());
        }
        boolean selectedArguments = args.stream().anyMatch(value -> value.name() != null || value.index() != null);
        if (selectedArguments) {
            if (executable.isVarArgs() || parameters.length != args.size()) return;
            int[] sourceIndexes = selectedArgumentOrder(executable, args);
            if (sourceIndexes == null) return;
            addCandidate(executable, parameters, sourceIndexes, false, false, parameters.length, args, matches);
            return;
        }

        if (!executable.isVarArgs()) {
            if (parameters.length == args.size()) {
                addCandidate(executable, parameters, identityOrder(args.size()), false, false,
                        parameters.length, args, matches);
            }
            return;
        }
        int fixed = parameters.length - 1;
        if (args.size() < fixed) return;
        if (args.size() == parameters.length
                && compatibility(args.get(fixed).value(), parameters[fixed]) >= 0) {
            addCandidate(executable, parameters, identityOrder(args.size()), true, true,
                    fixed, args, matches);
        }
        Type array = parameters[fixed];
        Type component = array instanceof java.lang.reflect.GenericArrayType genericArray
                ? genericArray.getGenericComponentType()
                : Types.raw(array).getComponentType();
        Type[] expanded = new Type[args.size()];
        System.arraycopy(parameters, 0, expanded, 0, fixed);
        Arrays.fill(expanded, fixed, expanded.length, component);
        addCandidate(executable, expanded, identityOrder(args.size()), true, false, fixed, args, matches);
    }

    private void addCandidate(Executable executable, Type[] inputTypes, int[] sourceIndexes,
                              boolean varargs, boolean directArray, int fixed,
                              List<ArgumentDef> args, List<Candidate> matches) {
        int[] scores = new int[inputTypes.length];
        int total = varargs && !directArray ? 1 : 0;
        for (int i = 0; i < inputTypes.length; i++) {
            int score = compatibility(args.get(sourceIndexes[i]).value(), inputTypes[i]);
            if (score < 0) return;
            scores[i] = score;
            total += score;
        }
        matches.add(new Candidate(new BoundExecutable(executable, inputTypes, sourceIndexes,
                varargs, directArray, fixed), total, scores));
    }

    private int[] selectedArgumentOrder(Executable executable, List<ArgumentDef> args) {
        int[] result = new int[executable.getParameterCount()];
        Arrays.fill(result, -1);
        String[] names = parameterNames(executable);
        for (int source = 0; source < args.size(); source++) {
            ArgumentDef argument = args.get(source);
            int target;
            if (argument.index() != null) {
                target = argument.index();
                if (target >= result.length) return null;
            } else {
                if (names == null) return null;
                target = indexOf(names, argument.name());
                if (target < 0) return null;
            }
            if (result[target] >= 0) return null;
            result[target] = source;
        }
        for (int index : result) if (index < 0) return null;
        return result;
    }

    private static String[] parameterNames(Executable executable) {
        if (executable instanceof Constructor<?> constructor && constructor.getDeclaringClass().isRecord()) {
            RecordComponent[] components = constructor.getDeclaringClass().getRecordComponents();
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (components.length == parameterTypes.length) {
                boolean canonical = true;
                for (int i = 0; i < components.length; i++) {
                    if (!components[i].getType().equals(parameterTypes[i])) {
                        canonical = false;
                        break;
                    }
                }
                if (canonical) return Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new);
            }
        }
        Parameter[] parameters = executable.getParameters();
        if (Arrays.stream(parameters).anyMatch(parameter -> !parameter.isNamePresent())) return null;
        return Arrays.stream(parameters).map(Parameter::getName).toArray(String[]::new);
    }

    private boolean matchesExactSignature(Executable executable, String signature, SourceLocation location) {
        String value = signature.trim();
        if (!value.startsWith("(") || !value.endsWith(")")) {
            throw new BeanException(location, "Exact executable signature must use '(type,type)' syntax: " + signature);
        }
        String body = value.substring(1, value.length() - 1).trim();
        String[] names = body.isEmpty() ? new String[0] : Arrays.stream(body.split(",", -1))
                .map(String::trim).toArray(String[]::new);
        if (Arrays.stream(names).anyMatch(String::isEmpty)) {
            throw new BeanException(location, "Exact executable signature contains an empty parameter: " + signature);
        }
        if (names.length != executable.getParameterCount()) return false;
        Class<?>[] actual = executable.getParameterTypes();
        for (int i = 0; i < names.length; i++) {
            String name = names[i].endsWith("...")
                    ? names[i].substring(0, names[i].length() - 3) + "[]" : names[i];
            Class<?> expected = Types.load(name, classLoader, location);
            if (!actual[i].equals(expected)) return false;
        }
        return true;
    }

    private static int[] identityOrder(int size) {
        int[] result = new int[size];
        for (int i = 0; i < size; i++) result[i] = i;
        return result;
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(target)) return i;
        return -1;
    }

    private static String candidateSignatures(List<Executable> candidates) {
        StringBuilder result = new StringBuilder();
        candidates.forEach(candidate -> result.append("  ").append(signature(candidate)).append('\n'));
        return result.toString();
    }

    private int literalCompatibility(Literal literal, Class<?> target) {
        if (literal.explicitType() != null) {
            Class<?> explicit = Types.load(literal.explicitType(), classLoader, literal.location());
            if (Types.sameBoxed(explicit, target)) return 0;
            Class<?> wrappedExplicit = Types.wrap(explicit);
            Class<?> wrappedTarget = Types.wrap(target);
            if (wrappedTarget.isAssignableFrom(wrappedExplicit)) return 1;
            if (isNumericWidening(wrappedExplicit, wrappedTarget)) return 2;
            return -1;
        }
        if (target == String.class) return 0;
        if (target == Object.class) return 4;
        if (target == Class.class || target.isEnum() || converters.hasConverter(target)) return 3;
        return -1;
    }

    private static int objectCompatibility(Type sourceType, Type targetType) {
        if (!Types.assignable(sourceType, targetType)) return -1;
        Class<?> source = Types.raw(sourceType);
        Class<?> target = Types.raw(targetType);
        if (target.isPrimitive()) return Types.sameBoxed(source, target) ? 0 : -1;
        if (target.equals(source)) return 0;
        return Math.min(4, 1 + inheritanceDistance(Types.wrap(source), target));
    }

    private static int collectionCompatibility(Class<?> target, boolean set) {
        if (target == Object.class) return 4;
        if (target == Iterable.class && !set) return 0;
        if (!Collection.class.isAssignableFrom(target)) return -1;
        if (set && (target == Set.class || target == SortedSet.class || target == NavigableSet.class)) return 0;
        if (!set && (target == List.class || target == Collection.class || target == Iterable.class
                || target == Queue.class || target == Deque.class)) return 0;
        if (!target.isInterface() && !Modifier.isAbstract(target.getModifiers())) return 2;
        return set ? (target.isAssignableFrom(java.util.LinkedHashSet.class) ? 1 : -1)
                : (target.isAssignableFrom(java.util.ArrayList.class) ? 1 : -1);
    }

    private static int mapCompatibility(Class<?> target) {
        if (target == Object.class) return 4;
        if (!Map.class.isAssignableFrom(target)) return -1;
        if (target == Map.class || target == SortedMap.class || target == NavigableMap.class
                || target == ConcurrentMap.class) return 0;
        if (!target.isInterface() && !Modifier.isAbstract(target.getModifiers())) return 2;
        return target.isAssignableFrom(java.util.LinkedHashMap.class) ? 1 : -1;
    }

    private static boolean moreSpecific(Candidate a, Candidate b) {
        Type[] aa = a.binding().inputTypes();
        Type[] bb = b.binding().inputTypes();
        if (aa.length != bb.length) return false;
        boolean strict = false;
        for (int i = 0; i < aa.length; i++) {
            Class<?> ac = Types.wrap(Types.raw(aa[i]));
            Class<?> bc = Types.wrap(Types.raw(bb[i]));
            if (!bc.isAssignableFrom(ac)) return false;
            if (!ac.equals(bc)) strict = true;
        }
        if (!strict && a.binding().executable() instanceof Method am && b.binding().executable() instanceof Method bm) {
            Class<?> ad = am.getDeclaringClass();
            Class<?> bd = bm.getDeclaringClass();
            strict = bd.isAssignableFrom(ad) && !ad.equals(bd);
        }
        return strict;
    }

    private static List<Method> methods(Class<?> type, String name, boolean requireStatic) {
        List<Method> result = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.isBridge() || method.isSynthetic()) continue;
            if (Modifier.isStatic(method.getModifiers()) != requireStatic) continue;
            boolean duplicate = result.stream().anyMatch(existing -> sameErasedSignature(existing, method));
            if (!duplicate) result.add(method);
            else {
                for (int i = 0; i < result.size(); i++) {
                    Method existing = result.get(i);
                    if (sameErasedSignature(existing, method)
                            && existing.getDeclaringClass().isAssignableFrom(method.getDeclaringClass())) {
                        result.set(i, method);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private static Method mostSpecificMethod(Class<?> type, List<Method> methods) {
        return methods.stream()
                .min(Comparator.comparingInt(m -> inheritanceDistance(type, m.getDeclaringClass())))
                .orElseThrow();
    }

    private static boolean sameErasedSignature(Method a, Method b) {
        return a.getName().equals(b.getName()) && Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    private static boolean isNumericWidening(Class<?> source, Class<?> target) {
        List<Class<?>> order = List.of(Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);
        int s = order.indexOf(source);
        int t = order.indexOf(target);
        return s >= 0 && t >= 0 && s <= t;
    }

    static int inheritanceDistance(Class<?> source, Class<?> target) {
        if (source.equals(target)) return 0;
        if (!target.isAssignableFrom(source)) return 1000;
        if (target.isInterface()) return interfaceDistance(source, target, 0, new java.util.HashSet<>());
        int distance = 0;
        Class<?> current = source;
        while (current != null && !current.equals(target)) {
            current = current.getSuperclass();
            distance++;
        }
        return distance;
    }

    private static int interfaceDistance(Class<?> source, Class<?> target, int depth, Set<Class<?>> seen) {
        if (source == null || !seen.add(source)) return 1000;
        if (source.equals(target)) return depth;
        int best = 1000;
        for (Class<?> iface : source.getInterfaces()) {
            best = Math.min(best, interfaceDistance(iface, target, depth + 1, seen));
        }
        return Math.min(best, interfaceDistance(source.getSuperclass(), target, depth + 1, seen));
    }

    private static String noMatchMessage(String description, List<Executable> candidates, List<ArgumentDef> args) {
        StringBuilder b = new StringBuilder("No compatible ").append(description).append(" for ")
                .append(args.size()).append(" argument(s). Candidates:\n");
        candidates.forEach(c -> b.append("  ").append(signature(c)).append('\n'));
        b.append("Provided value kinds: ");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(args.get(i).value().getClass().getSimpleName());
        }
        return b.toString();
    }

    public static String signature(Executable executable) {
        String name = executable instanceof Constructor<?> ? executable.getDeclaringClass().getSimpleName()
                : executable.getDeclaringClass().getSimpleName() + "." + executable.getName();
        String parameters = Arrays.stream(executable.getGenericParameterTypes())
                .map(Types::display)
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
        return name + parameters + (executable.isVarArgs() ? " [varargs]" : "");
    }
}
