package io.github.simpledi.internal;

import io.github.simpledi.SourceLocation;
import io.github.simpledi.ConditionOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Definitions {
    private Definitions() {}

    public record Document(
            LinkedHashMap<String, BeanDef> beans,
            LinkedHashMap<String, String> aliases,
            List<ConditionOutcome> conditions) {
        public Document(LinkedHashMap<String, BeanDef> beans, LinkedHashMap<String, String> aliases) {
            this(beans, aliases, List.of());
        }

        public Document {
            beans = new LinkedHashMap<>(beans);
            aliases = new LinkedHashMap<>(aliases);
            conditions = List.copyOf(conditions);
        }
    }

    public enum Scope { SINGLETON, PROTOTYPE, CUSTOM }

    /** Lifecycle owner. EXTERNAL means the context creates/configures the object but never destroys it. */
    public enum Ownership { CONTEXT, CALLER, EXTERNAL, INHERIT }

    /** Relationship between an explicit destroy method and {@link AutoCloseable#close()}. */
    public enum AutoClosePolicy { FALLBACK, BEFORE, AFTER, NEVER }

    public record BeanDef(
            String id,
            String className,
            FactoryDef factory,
            String constructorSignature,
            List<ArgumentDef> constructorArgs,
            List<InjectionDef> injections,
            String initMethod,
            String destroyMethod,
            AutoClosePolicy autoClosePolicy,
            Scope scope,
            String scopeName,
            Ownership ownership,
            boolean lazy,
            List<String> dependsOn,
            String replaces,
            ConditionDef condition,
            SourceLocation location) {}


    /** Optional property-driven activation condition evaluated before overlay merge and graph compilation. */
    public record ConditionDef(
            String property,
            String expectedValue,
            boolean negated,
            boolean matchIfMissing,
            SourceLocation location) {}

    /**
     * One executable argument. Exactly one of {@code name} or {@code index} may be supplied.
     * Both null means ordinary positional order.
     */
    public record ArgumentDef(String name, Integer index, ValueDef value, SourceLocation location) {}

    /** ownerClassName and factoryBean are mutually exclusive; both null means the product class owns a static factory. */
    public record FactoryDef(
            String ownerClassName,
            String factoryBean,
            String method,
            String signature,
            List<ArgumentDef> args,
            SourceLocation location) {}

    public sealed interface InjectionDef permits PropertyDef, CallDef {
        SourceLocation location();
    }

    public record PropertyDef(String name, ValueDef value, SourceLocation location) implements InjectionDef {}

    public record CallDef(String method, String signature, List<ArgumentDef> args,
                          SourceLocation location) implements InjectionDef {}

    public sealed interface ValueDef permits Literal, Ref, SupplierRef, OptionalRef, ConstantValue,
            NestedBean, ListValue, SetValue, MapValue, PropertiesValue, ArrayValue, OptionalValue, NullValue {
        SourceLocation location();
    }

    public record Literal(String text, String explicitType, SourceLocation location) implements ValueDef {}

    public record Ref(String beanId, SourceLocation location) implements ValueDef {}

    public record SupplierRef(String beanId, SourceLocation location) implements ValueDef {}

    public record OptionalRef(String beanId, SourceLocation location) implements ValueDef {}

    public record ConstantValue(String className, String field, SourceLocation location) implements ValueDef {}

    public record NestedBean(BeanDef bean, SourceLocation location) implements ValueDef {}

    public record ListValue(List<ValueDef> values, boolean immutable, SourceLocation location) implements ValueDef {}

    public record SetValue(List<ValueDef> values, boolean immutable, SourceLocation location) implements ValueDef {}

    public record MapEntry(ValueDef key, ValueDef value, SourceLocation location) {}

    public record MapValue(List<MapEntry> entries, boolean immutable, SourceLocation location) implements ValueDef {}

    public record PropertiesValue(Map<String, String> values, boolean immutable,
                                  SourceLocation location) implements ValueDef {}

    public record ArrayValue(String componentType, List<ValueDef> values, SourceLocation location) implements ValueDef {}

    public record OptionalValue(ValueDef value, SourceLocation location) implements ValueDef {}

    public record NullValue(SourceLocation location) implements ValueDef {}
}
