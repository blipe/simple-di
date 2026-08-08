package io.github.simpledi;

import io.github.simpledi.internal.DefaultBeanContext;
import io.github.simpledi.internal.DefaultConverterRegistry;
import io.github.simpledi.internal.ConditionEvaluator;
import io.github.simpledi.internal.Definitions.Document;
import io.github.simpledi.internal.DocumentMerger;
import io.github.simpledi.internal.DependencyValidator;
import io.github.simpledi.internal.ExecutableResolver;
import io.github.simpledi.internal.ExternalBinding;
import io.github.simpledi.internal.GraphCompiler;
import io.github.simpledi.internal.GraphCompiler.CompiledBean;
import io.github.simpledi.internal.GraphCompiler.CompiledCall;
import io.github.simpledi.internal.GraphCompiler.CompiledInjection;
import io.github.simpledi.internal.GraphCompiler.CompiledProperty;
import io.github.simpledi.internal.PropertyExpander;
import io.github.simpledi.internal.PropertyResolver;
import io.github.simpledi.internal.Types;
import io.github.simpledi.internal.XmlDefinitionParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Entry point for loading explicit XML object graphs. */
public final class XmlBeans {
    private XmlBeans() {}

    public static BeanContext load(Path file) {
        return builder().load(file);
    }

    public static BeanContext load(Path file, ClassLoader classLoader) {
        return builder().classLoader(classLoader).load(file);
    }

    public static BeanContext loadResource(String resource) {
        return builder().loadResource(resource);
    }

    public static BeanContext load(InputStream input, String sourceName) {
        return builder().load(input, sourceName);
    }

    public static BeanContext load(Reader reader, String sourceName) {
        return builder().load(reader, sourceName);
    }

    public static BeanContext loadXml(String xml, String sourceName) {
        return builder().loadXml(xml, sourceName);
    }

    public static ValidationResult validate(Path file) {
        return builder().validate(file);
    }

    public static ValidationResult validate(InputStream input, String sourceName) {
        return builder().validate(input, sourceName);
    }

    public static ValidationResult validate(Reader reader, String sourceName) {
        return builder().validate(reader, sourceName);
    }

    public static ValidationResult validateXml(String xml, String sourceName) {
        return builder().validateXml(xml, sourceName);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Creates a generation-based, atomically reloadable context builder. */
    public static ReloadableBuilder reloadable() {
        return new ReloadableBuilder();
    }

    /** Builder for safe whole-graph replacement. */
    public static final class ReloadableBuilder {
        private Supplier<Builder> builderFactory = XmlBeans::builder;
        private ReloadPolicy reloadPolicy = ReloadPolicy.GRACEFUL;
        private ReloadPolicy shutdownPolicy = ReloadPolicy.GRACEFUL_WITH_TIMEOUT;
        private Duration drainTimeout = Duration.ofSeconds(30);
        private LeaseDiagnostics leaseDiagnostics = LeaseDiagnostics.NONE;
        private GenerationClassLoaderOwnership classLoaderOwnership = GenerationClassLoaderOwnership.EXTERNAL;
        private GenerationHandoff handoff = (previous, candidate, diff) -> {};
        private final List<ReloadListener> listeners = new ArrayList<>();
        private boolean copiedBuilder;

        private ReloadableBuilder() {}

        /** Uses an immutable snapshot of a normal builder for every generation. */
        public ReloadableBuilder builder(Builder builder) {
            Builder snapshot = Objects.requireNonNull(builder, "builder").copy();
            this.builderFactory = snapshot::copy;
            this.copiedBuilder = true;
            return this;
        }

        /** Supplies a fresh builder for every generation, required for generation-specific classloaders or scopes. */
        public ReloadableBuilder builderFactory(Supplier<Builder> factory) {
            this.builderFactory = Objects.requireNonNull(factory, "factory");
            this.copiedBuilder = false;
            return this;
        }

        public ReloadableBuilder reloadPolicy(ReloadPolicy policy) {
            this.reloadPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public ReloadableBuilder shutdownPolicy(ReloadPolicy policy) {
            Objects.requireNonNull(policy, "policy");
            if (policy == ReloadPolicy.REJECT_WHILE_BUSY) {
                throw new IllegalArgumentException("REJECT_WHILE_BUSY is a reload-only policy");
            }
            this.shutdownPolicy = policy;
            return this;
        }

        public ReloadableBuilder drainTimeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            this.drainTimeout = timeout;
            return this;
        }

        public ReloadableBuilder leaseDiagnostics(LeaseDiagnostics diagnostics) {
            this.leaseDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            return this;
        }

        public ReloadableBuilder classLoaderOwnership(GenerationClassLoaderOwnership ownership) {
            this.classLoaderOwnership = Objects.requireNonNull(ownership, "ownership");
            return this;
        }

        public ReloadableBuilder handoff(GenerationHandoff handoff) {
            this.handoff = Objects.requireNonNull(handoff, "handoff");
            return this;
        }

        public ReloadableBuilder listener(ReloadListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        public ReloadableBeanContext load(Path configuration) {
            Objects.requireNonNull(configuration, "configuration");
            if (copiedBuilder) {
                Builder probe = requireBuilder(builderFactory);
                if (!probe.scopes.isEmpty()) {
                    throw new IllegalStateException("Reloadable contexts with custom scopes require builderFactory(...) "
                            + "so each generation receives fresh scope instances");
                }
            }
            return ReloadableContexts.load(builderFactory, configuration.toAbsolutePath().normalize(),
                    reloadPolicy, shutdownPolicy, drainTimeout, leaseDiagnostics, classLoaderOwnership,
                    handoff, List.copyOf(listeners));
        }

        private static Builder requireBuilder(Supplier<Builder> factory) {
            return Objects.requireNonNull(factory.get(), "builderFactory returned null");
        }
    }

    public static final class Builder {
        private record Layer(Path file, String resource) {
            private Layer {
                if ((file == null) == (resource == null)) throw new IllegalArgumentException("layer source");
            }
        }

        private record ParentImport(String parentId, String childId) {}

        private Builder() {
            this.converters = new DefaultConverterRegistry();
        }

        private Builder(Builder source) {
            this.converters = new DefaultConverterRegistry(source.converters);
            this.classLoader = source.classLoader;
            this.directProperties.putAll(source.directProperties);
            this.sensitiveDirectProperties.addAll(source.sensitiveDirectProperties);
            this.propertySources.addAll(source.propertySources);
            this.defaultPropertySources = source.defaultPropertySources;
            this.bindings.putAll(source.bindings);
            this.bindingIdentities.putAll(source.bindingIdentities);
            this.overlays.addAll(source.overlays);
            this.parent = source.parent;
            this.parentImports.addAll(source.parentImports);
            this.limits = source.limits;
            this.fileIncludesEnabled = source.fileIncludesEnabled;
            this.classpathIncludesEnabled = source.classpathIncludesEnabled;
            this.fileIncludeRoot = source.fileIncludeRoot;
            this.classpathIncludeRoot = source.classpathIncludeRoot;
            this.listeners.addAll(source.listeners);
            this.lifecycleInterceptors.addAll(source.lifecycleInterceptors);
            this.scopes.putAll(source.scopes);
            this.revisionTokens.addAll(source.revisionTokens);
        }

        /** Returns an independent mutable builder with the same registrations and configuration. */
        public Builder copy() {
            return new Builder(this);
        }

        private ClassLoader classLoader = defaultClassLoader();
        private final LinkedHashMap<String, String> directProperties = new LinkedHashMap<>();
        private final LinkedHashSet<String> sensitiveDirectProperties = new LinkedHashSet<>();
        private final List<PropertySource> propertySources = new ArrayList<>();
        private boolean defaultPropertySources = true;
        private final LinkedHashMap<String, ExternalBinding> bindings = new LinkedHashMap<>();
        private final IdentityHashMap<Object, String> bindingIdentities = new IdentityHashMap<>();
        private final List<Layer> overlays = new ArrayList<>();
        private BeanContext parent;
        private final List<ParentImport> parentImports = new ArrayList<>();
        private final DefaultConverterRegistry converters;
        private XmlLimits limits = XmlLimits.DEFAULT;
        private boolean fileIncludesEnabled = true;
        private boolean classpathIncludesEnabled = true;
        private Path fileIncludeRoot;
        private String classpathIncludeRoot;
        private final List<BeanContextListener> listeners = new ArrayList<>();
        private final List<BeanLifecycleInterceptor> lifecycleInterceptors = new ArrayList<>();
        private final LinkedHashMap<String, BeanScope> scopes = new LinkedHashMap<>();
        private final LinkedHashSet<String> revisionTokens = new LinkedHashSet<>();

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
            return this;
        }

        /** Adds the highest-precedence non-sensitive property. */
        public Builder property(String name, String value) {
            directProperties.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            sensitiveDirectProperties.remove(name);
            return this;
        }

        /** Adds the highest-precedence sensitive property. Its value is redacted from diagnostics. */
        public Builder secret(String name, String value) {
            property(name, value);
            sensitiveDirectProperties.add(name);
            return this;
        }

        public Builder properties(Map<String, String> values) {
            Objects.requireNonNull(values, "values").forEach(this::property);
            return this;
        }

        /** Adds a property source. Later sources override earlier sources; direct properties override all sources. */
        public Builder propertySource(PropertySource source) {
            propertySources.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        public Builder propertiesFile(Path file) {
            return propertiesFile(file, Set.of());
        }

        public Builder propertiesFile(Path file, Set<String> sensitiveKeys) {
            Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
            return propertySource(PropertySource.of(normalized.toString(), readProperties(normalized), sensitiveKeys));
        }

        public Builder propertiesResource(String resource) {
            return propertiesResource(resource, Set.of());
        }

        public Builder propertiesResource(String resource, Set<String> sensitiveKeys) {
            String normalized = normalizeResource(resource);
            Properties values = new Properties();
            try (InputStream input = classLoader.getResourceAsStream(normalized)) {
                if (input == null) throw new BeanException("Property resource not found: " + normalized);
                values.load(input);
            } catch (IOException e) {
                throw new BeanException("Cannot read property resource " + normalized, e);
            }
            return propertySource(PropertySource.of("classpath:" + normalized, ordered(values), sensitiveKeys));
        }

        /** Disables the built-in system-property and env.NAME sources. */
        public Builder withoutDefaultPropertySources() {
            defaultPropertySources = false;
            return this;
        }

        /** Adds an existing, externally owned singleton using its runtime class as the declared API type. */
        public Builder bind(String id, Object instance) {
            Objects.requireNonNull(instance, "instance");
            return bindType(id, instance.getClass(), instance, "external binding");
        }

        /** Adds an existing singleton while deliberately exposing only {@code declaredType} to the graph. */
        public <T> Builder bind(String id, Class<T> declaredType, T instance) {
            return bindType(id, Objects.requireNonNull(declaredType, "declaredType"), instance, "typed external binding");
        }

        /** Adds an existing singleton with a complete generic declared type. */
        public <T> Builder bind(String id, TypeRef<T> declaredType, T instance) {
            return bindType(id, Objects.requireNonNull(declaredType, "declaredType").type(), instance,
                    "generic external binding");
        }

        private Builder bindType(String id, Type declaredType, Object instance, String origin) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(instance, "instance");
            if (id.isBlank()) throw new IllegalArgumentException("Binding id must not be blank");
            if (bindings.containsKey(id)) throw new IllegalArgumentException("Duplicate external binding '" + id + "'");
            Class<?> raw = Types.raw(declaredType);
            if (!raw.isInstance(instance)) {
                throw new IllegalArgumentException("Binding '" + id + "' instance is "
                        + instance.getClass().getTypeName() + ", not " + declaredType.getTypeName());
            }
            String existing = bindingIdentities.get(instance);
            if (existing != null) {
                throw new IllegalArgumentException("External bindings '" + existing + "' and '" + id
                        + "' use the same object identity. Bind it once and declare an XML alias.");
            }
            bindings.put(id, new ExternalBinding(instance, declaredType, origin + " '" + id + "'"));
            bindingIdentities.put(instance, id);
            return this;
        }

        public Builder bindings(Map<String, ?> values) {
            Objects.requireNonNull(values, "values").forEach(this::bind);
            return this;
        }

        /** Adds an independent overlay file. Existing beans can be changed only with replaces="sameId". */
        public Builder overlay(Path file) {
            overlays.add(new Layer(Objects.requireNonNull(file, "file").toAbsolutePath().normalize(), null));
            return this;
        }

        /** Adds an independent classpath overlay resource. */
        public Builder overlayResource(String resource) {
            overlays.add(new Layer(null, normalizeResource(resource)));
            return this;
        }

        /** Selects a parent context. Only explicitly imported parent beans are visible to this graph. */
        public Builder parent(BeanContext parent) {
            this.parent = Objects.requireNonNull(parent, "parent");
            return this;
        }

        /** Imports a parent bean under the same id. The parent retains lifecycle ownership. */
        public Builder importBean(String id) {
            return importBean(id, id);
        }

        /** Imports {@code parentId} under {@code childId}. */
        public Builder importBean(String parentId, String childId) {
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(childId, "childId");
            if (parentId.isBlank() || childId.isBlank()) throw new IllegalArgumentException("Imported ids must not be blank");
            if (parentImports.stream().anyMatch(value -> value.childId().equals(childId))) {
                throw new IllegalArgumentException("Duplicate parent import '" + childId + "'");
            }
            parentImports.add(new ParentImport(parentId, childId));
            return this;
        }

        public Builder importBeans(String... ids) {
            for (String id : Objects.requireNonNull(ids, "ids")) importBean(id);
            return this;
        }

        public Builder limits(XmlLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /** Restricts all file-backed roots and includes to this directory, resolving symlinks where possible. */
        public Builder fileIncludeRoot(Path root) {
            this.fileIncludeRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            return this;
        }

        /** Restricts classpath roots and includes to this normalized prefix. */
        public Builder classpathIncludeRoot(String root) {
            this.classpathIncludeRoot = Objects.requireNonNull(root, "root");
            return this;
        }

        public Builder withoutFileIncludes() {
            this.fileIncludesEnabled = false;
            return this;
        }

        public Builder withoutClasspathIncludes() {
            this.classpathIncludesEnabled = false;
            return this;
        }

        /** Adds a synchronous lifecycle listener. Listener failure participates in normal rollback. */
        public Builder listener(BeanContextListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /** Adds an instance-aware lifecycle interceptor. */
        public Builder lifecycleInterceptor(BeanLifecycleInterceptor interceptor) {
            lifecycleInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /** Registers a named custom scope referenced by scope="name" in XML. */
        public Builder scope(String name, BeanScope scope) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(scope, "scope");
            if (!name.matches("[A-Za-z][A-Za-z0-9._-]*")
                    || "singleton".equals(name) || "prototype".equals(name)) {
                throw new IllegalArgumentException("Custom scope name must match [A-Za-z][A-Za-z0-9._-]* "
                        + "and not be a built-in scope");
            }
            if (scopes.putIfAbsent(name, scope) != null) {
                throw new IllegalArgumentException("Duplicate custom scope '" + name + "'");
            }
            return this;
        }

        public <T> Builder converter(Class<T> type, ValueConverter<? extends T> converter) {
            converters.register(type, converter);
            return this;
        }

        public ConverterRegistry converters() {
            return converters;
        }

        /** Adds a deterministic host revision token for converters, scopes, interceptors, or external facilities. */
        public Builder revisionToken(String token) {
            Objects.requireNonNull(token, "token");
            if (token.isBlank()) throw new IllegalArgumentException("revision token must not be blank");
            revisionTokens.add(token);
            return this;
        }

        /** Validates and throws on the first error, preserving the 2.1 contract. */
        public ValidationResult validate(Path file) {
            ValidationResult result = inspect(file);
            result.throwIfInvalid();
            return result;
        }

        public ValidationResult validateResource(String resource) {
            ValidationResult result = inspectResource(resource);
            result.throwIfInvalid();
            return result;
        }

        public ValidationResult validate(InputStream input, String sourceName) {
            ValidationResult result = inspect(input, sourceName);
            result.throwIfInvalid();
            return result;
        }

        public ValidationResult validate(Reader reader, String sourceName) {
            ValidationResult result = inspect(reader, sourceName);
            result.throwIfInvalid();
            return result;
        }

        public ValidationResult validateXml(String xml, String sourceName) {
            ValidationResult result = inspectXml(xml, sourceName);
            result.throwIfInvalid();
            return result;
        }

        /** Returns a structured side-effect-free report, including failures. */
        public ValidationResult inspect(Path file) {
            return inspectPrepared(() -> preparePath(file));
        }

        public ValidationResult inspectResource(String resource) {
            return inspectPrepared(() -> prepareResource(resource));
        }

        public ValidationResult inspect(InputStream input, String sourceName) {
            return inspectPrepared(() -> prepareInput(input, sourceName));
        }

        public ValidationResult inspect(Reader reader, String sourceName) {
            return inspectPrepared(() -> prepareReader(reader, sourceName));
        }

        public ValidationResult inspectXml(String xml, String sourceName) {
            return inspectPrepared(() -> prepareXml(xml, sourceName));
        }

        private ValidationResult inspectPrepared(PreparedAction action) {
            try {
                Prepared prepared = action.run();
                long started = System.nanoTime();
                emit(BeanEvent.context(BeanEvent.Kind.GRAPH_COMPILING));
                try {
                    GraphCompiler.Inspection inspection = prepared.compiler().inspect();
                    emit(new BeanEvent(BeanEvent.Kind.GRAPH_COMPILED, null, null,
                            System.nanoTime() - started, null));
                    return report(prepared, inspection.beans(), problems(prepared, inspection.failures()));
                } catch (Throwable failure) {
                    emitFailure(new BeanEvent(BeanEvent.Kind.GRAPH_FAILED, null, null,
                            System.nanoTime() - started, failure), failure);
                    throw failure;
                }
            } catch (BeanException e) {
                return invalidReport(e);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                return invalidReport(new BeanException("Configuration inspection failed: " + failure, failure));
            }
        }

        public BeanContext load(Path file) {
            return start(preparePath(file));
        }

        public BeanContext loadResource(String resource) {
            return start(prepareResource(resource));
        }

        public BeanContext load(InputStream input, String sourceName) {
            return start(prepareInput(input, sourceName));
        }

        public BeanContext load(Reader reader, String sourceName) {
            return start(prepareReader(reader, sourceName));
        }

        public BeanContext loadXml(String xml, String sourceName) {
            return start(prepareXml(xml, sourceName));
        }

        ReloadPreparation prepareForReload(Path file, byte[] secretKey, RevisionIdentityRegistry identities) {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(secretKey, "secretKey");
            Objects.requireNonNull(identities, "identities");
            try {
                Prepared prepared = preparePath(file);
                long started = System.nanoTime();
                emit(BeanEvent.context(BeanEvent.Kind.GRAPH_COMPILING));
                GraphCompiler.Inspection inspection;
                try {
                    inspection = prepared.compiler().inspect();
                    emit(new BeanEvent(BeanEvent.Kind.GRAPH_COMPILED, null, null,
                            System.nanoTime() - started, null));
                } catch (Throwable failure) {
                    emitFailure(new BeanEvent(BeanEvent.Kind.GRAPH_FAILED, null, null,
                            System.nanoTime() - started, failure), failure);
                    throw failure;
                }
                ValidationResult validation = report(prepared, inspection.beans(),
                        problems(prepared, inspection.failures()));
                if (!validation.valid()) return ReloadPreparation.invalid(validation, classLoader);
                return new ReloadPreparation(this, prepared, validation,
                        revision(prepared, validation, secretKey, identities), classLoader);
            } catch (BeanException failure) {
                return ReloadPreparation.invalid(invalidReport(failure), classLoader);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                return ReloadPreparation.invalid(invalidReport(
                        new BeanException("Configuration preparation failed: " + failure, failure)), classLoader);
            }
        }

        private ConfigurationRevision revision(Prepared prepared, ValidationResult validation, byte[] secretKey,
                                               RevisionIdentityRegistry identities) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, prepared.document().toString());
                for (PropertyResolution resolution : validation.properties()) {
                    update(digest, "property:" + resolution.name() + ':' + resolution.selectedSource() + ':');
                    var resolved = prepared.expander().resolver().find(resolution.name());
                    if (resolved.isEmpty()) {
                        update(digest, "<missing>");
                    } else if (resolved.get().sensitive()) {
                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
                        digest.update(mac.doFinal(resolved.get().value().getBytes(StandardCharsets.UTF_8)));
                    } else {
                        update(digest, resolved.get().value());
                    }
                }
                prepared.localBindings().forEach((id, binding) ->
                        update(digest, "binding:" + id + ':' + binding.declaredType().getTypeName() + ':'
                                + identities.id(binding.instance())));
                update(digest, "classloader:" + classLoader.getClass().getName() + ':' + identities.id(classLoader));
                converters.converters().forEach((type, converter) ->
                        update(digest, "converter:" + type.getTypeName() + ':' + converter.getClass().getName()));
                scopes.forEach((name, scope) -> update(digest, "scope:" + name + ':' + scope.getClass().getName()));
                lifecycleInterceptors.forEach(value ->
                        update(digest, "interceptor:" + value.getClass().getName()));
                revisionTokens.forEach(token -> update(digest, "host-revision:" + token));
                if (parent != null) update(digest, "parent:" + identities.id(parent));
                return new ConfigurationRevision(hex(digest.digest()));
            } catch (GeneralSecurityException failure) {
                throw new BeanException("Cannot calculate configuration revision", failure);
            }
        }

        private static void update(MessageDigest digest, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }

        private static String hex(byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        }

        private BeanContext start(Prepared prepared) {
            long compileStarted = System.nanoTime();
            emit(BeanEvent.context(BeanEvent.Kind.GRAPH_COMPILING));
            try {
                prepared.compiler().compile();
                emit(new BeanEvent(BeanEvent.Kind.GRAPH_COMPILED, null, null,
                        System.nanoTime() - compileStarted, null));
            } catch (Throwable failure) {
                emitFailure(new BeanEvent(BeanEvent.Kind.GRAPH_FAILED, null, null,
                        System.nanoTime() - compileStarted, failure), failure);
                throw rethrow(failure);
            }
            Runnable parentRelease = () -> {};
            try {
                if (parent != null) {
                    if (!(parent instanceof DefaultBeanContext defaultParent)) {
                        throw new IllegalArgumentException("Parent must be a simple-di BeanContext implementation");
                    }
                    parentRelease = defaultParent.retainChild();
                } else if (!parentImports.isEmpty()) {
                    throw new IllegalStateException("parent(...) is required before importBean(...)");
                }
                LinkedHashMap<String, ExternalBinding> runtimeBindings = new LinkedHashMap<>(prepared.localBindings());
                if (parent != null) {
                    for (ParentImport imported : parentImports) {
                        Object instance = parent.require(imported.parentId());
                        Type type = parent.beanType(imported.parentId());
                        runtimeBindings.put(imported.childId(), new ExternalBinding(instance, type,
                                "parent bean '" + imported.parentId() + "'"));
                    }
                }
                DefaultBeanContext context = new DefaultBeanContext(prepared.document(), classLoader,
                        prepared.contextConverters(), prepared.expander(), prepared.compiler(), runtimeBindings,
                        parentRelease, List.copyOf(listeners), List.copyOf(lifecycleInterceptors),
                        new LinkedHashMap<>(scopes));
                return context.start();
            } catch (Throwable failure) {
                try {
                    parentRelease.run();
                } catch (Throwable releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new BeanException("Cannot start context", failure);
            }
        }

        private Prepared preparePath(Path file) {
            Objects.requireNonNull(file, "file");
            Path source = file.toAbsolutePath().normalize();
            return parsePrepared(() -> {
                XmlDefinitionParser parser = parser(true);
                return merge(parser.parse(source), parser);
            });
        }

        private Prepared prepareResource(String resource) {
            String normalized = normalizeResource(resource);
            return parsePrepared(() -> {
                XmlDefinitionParser parser = parser(true);
                return merge(parser.parseResource(normalized), parser);
            });
        }

        private Prepared prepareInput(InputStream input, String sourceName) {
            Objects.requireNonNull(input, "input");
            return parsePrepared(() -> {
                XmlDefinitionParser parser = parser(true);
                return merge(parser.parse(input, sourceName), parser);
            });
        }

        private Prepared prepareReader(Reader reader, String sourceName) {
            Objects.requireNonNull(reader, "reader");
            return parsePrepared(() -> {
                XmlDefinitionParser parser = parser(true);
                return merge(parser.parse(reader, sourceName), parser);
            });
        }

        private Prepared prepareXml(String xml, String sourceName) {
            Objects.requireNonNull(xml, "xml");
            return parsePrepared(() -> {
                XmlDefinitionParser parser = parser(true);
                return merge(parser.parseXml(xml, sourceName), parser);
            });
        }

        private Prepared parsePrepared(DocumentAction action) {
            long started = System.nanoTime();
            emit(BeanEvent.context(BeanEvent.Kind.CONFIG_PARSING));
            try {
                Prepared result = prepared(action.run());
                emit(new BeanEvent(BeanEvent.Kind.CONFIG_PARSED, null, null,
                        System.nanoTime() - started, null));
                return result;
            } catch (Throwable failure) {
                emitFailure(new BeanEvent(BeanEvent.Kind.CONFIG_FAILED, null, null,
                        System.nanoTime() - started, failure), failure);
                throw rethrow(failure);
            }
        }

        private Document merge(Document base, XmlDefinitionParser parser) {
            LinkedHashSet<String> externalNames = new LinkedHashSet<>(bindings.keySet());
            for (ParentImport imported : parentImports) externalNames.add(imported.childId());
            PropertyResolver conditionResolver = new PropertyResolver(resolvedPropertySources());
            PropertyExpander conditionExpander = new PropertyExpander(conditionResolver);
            ConditionEvaluator evaluator = new ConditionEvaluator(conditionResolver, conditionExpander, externalNames);
            Document conditionedBase = evaluator.evaluate(base).document();
            List<Document> documents = new ArrayList<>();
            for (Layer layer : overlays) {
                Document raw = layer.file() != null ? parser.parse(layer.file()) : parser.parseResource(layer.resource());
                documents.add(evaluator.evaluate(raw).document());
            }
            Document merged = DocumentMerger.merge(conditionedBase, documents);
            int externalCount = bindings.size() + parentImports.size();
            if (merged.beans().size() + externalCount > limits.maxBeans()) {
                throw new BeanException("Merged bean count exceeds bean limit: " + limits.maxBeans());
            }
            return merged;
        }

        private XmlDefinitionParser parser(boolean cumulativeLimits) {
            LinkedHashSet<String> externalNames = new LinkedHashSet<>(bindings.keySet());
            for (ParentImport imported : parentImports) externalNames.add(imported.childId());
            return new XmlDefinitionParser(classLoader, limits, externalNames,
                    fileIncludesEnabled, classpathIncludesEnabled, fileIncludeRoot, classpathIncludeRoot,
                    cumulativeLimits);
        }

        private Prepared prepared(Document document) {
            DefaultConverterRegistry contextConverters = new DefaultConverterRegistry(converters);
            PropertyResolver propertyResolver = new PropertyResolver(resolvedPropertySources());
            PropertyExpander expander = new PropertyExpander(propertyResolver);
            LinkedHashMap<String, ExternalBinding> localBindings = new LinkedHashMap<>(bindings);
            LinkedHashMap<String, Type> externalTypes = new LinkedHashMap<>();
            localBindings.forEach((id, binding) -> externalTypes.put(id, binding.declaredType()));
            if (parent != null) {
                for (ParentImport imported : parentImports) {
                    if (externalTypes.containsKey(imported.childId())) {
                        throw new IllegalArgumentException("Parent import collides with external binding '"
                                + imported.childId() + "'");
                    }
                    externalTypes.put(imported.childId(), parent.beanType(imported.parentId()));
                }
            } else if (!parentImports.isEmpty()) {
                throw new IllegalStateException("parent(...) is required before importBean(...)");
            }
            for (var bean : document.beans().values()) {
                if (bean.scope() == io.github.simpledi.internal.Definitions.Scope.CUSTOM
                        && !scopes.containsKey(bean.scopeName())) {
                    throw new BeanException(bean.location(), "No BeanScope registered for custom scope '"
                            + bean.scopeName() + "'");
                }
            }
            GraphCompiler compiler = new GraphCompiler(document, classLoader, contextConverters, expander, externalTypes);
            return new Prepared(document, contextConverters, expander, compiler, localBindings, externalTypes);
        }

        private List<PropertySource> resolvedPropertySources() {
            List<PropertySource> result = new ArrayList<>();
            if (!directProperties.isEmpty()) {
                result.add(PropertySource.of("builder", directProperties, sensitiveDirectProperties));
            }
            for (int i = propertySources.size() - 1; i >= 0; i--) result.add(propertySources.get(i));
            if (defaultPropertySources) {
                result.add(PropertySource.of("system-properties", systemProperties()));
                result.add(PropertySource.of("environment", environmentProperties()));
            }
            return List.copyOf(result);
        }

        private ValidationResult report(Prepared prepared, Map<String, CompiledBean> plans,
                                        List<ConfigurationProblem> problems) {
            LinkedHashSet<String> names = new LinkedHashSet<>(prepared.externalTypes().keySet());
            names.addAll(prepared.document().beans().keySet());
            LinkedHashMap<String, BeanPlan> beanPlans = new LinkedHashMap<>();
            for (Map.Entry<String, CompiledBean> entry : plans.entrySet()) {
                CompiledBean plan = entry.getValue();
                List<String> injections = new ArrayList<>();
                for (CompiledInjection injection : plan.injections()) {
                    if (injection instanceof CompiledProperty property) {
                        injections.add(ExecutableResolver.signature(property.binding().executable()));
                    } else if (injection instanceof CompiledCall call) {
                        injections.add(ExecutableResolver.signature(call.binding().executable()));
                    }
                }
                beanPlans.put(entry.getKey(), new BeanPlan(entry.getKey(), plan.declaredType().getTypeName(),
                        plan.definition().scopeName(),
                        plan.definition().ownership().name().toLowerCase(), plan.definition().lazy(),
                        ExecutableResolver.signature(plan.creator().executable()), injections,
                        plan.initMethod() == null ? null : plan.initMethod().getName(),
                        plan.destroyMethod() == null ? null : plan.destroyMethod().getName(),
                        plan.definition().location()));
            }
            DependencyValidator dependencyValidator = new DependencyValidator();
            List<DependencyPlan> dependencies = dependencyValidator
                    .describe(prepared.document(), prepared.externalTypes().keySet()).stream()
                    .map(edge -> new DependencyPlan(edge.source(), edge.target(), edge.kind(), edge.lazy(), edge.location()))
                    .toList();
            List<String> creation = dependencyValidator.startupOrder(prepared.document(), prepared.externalTypes().keySet());
            List<String> destruction = new ArrayList<>(creation);
            Collections.reverse(destruction);
            return new ValidationResult(names, prepared.document().aliases().keySet(), prepared.document().aliases(),
                    beanPlans, dependencies, creation, destruction, prepared.expander().resolver().resolutions(),
                    prepared.document().conditions(), problems);
        }

        private List<ConfigurationProblem> problems(Prepared prepared, List<BeanException> failures) {
            List<ConfigurationProblem> result = new ArrayList<>();
            for (BeanException error : failures) {
                String message = stripLocation(error.getMessage(), error.location());
                result.add(new ConfigurationProblem(classify(message),
                        ConfigurationProblem.Severity.ERROR, error.location(),
                        beanAt(prepared.document(), error.location()), dependencyPath(message), message));
            }
            return List.copyOf(result);
        }

        private static String beanAt(Document document, SourceLocation location) {
            if (location == null) return null;
            String selected = null;
            int selectedLine = -1;
            for (var bean : document.beans().values()) {
                SourceLocation candidate = bean.location();
                if (candidate.source().equals(location.source()) && candidate.line() <= location.line()
                        && candidate.line() >= selectedLine) {
                    selected = bean.id();
                    selectedLine = candidate.line();
                }
            }
            return selected;
        }

        private static List<String> dependencyPath(String message) {
            int marker = message.indexOf("Circular dependency:");
            if (marker < 0) return List.of();
            String path = message.substring(marker + "Circular dependency:".length());
            int detail = path.indexOf(" (");
            if (detail >= 0) path = path.substring(0, detail);
            return java.util.Arrays.stream(path.trim().split("\\s*->\\s*"))
                    .filter(value -> !value.isBlank()).toList();
        }

        private ValidationResult invalidReport(BeanException error) {
            String message = stripLocation(error.getMessage(), error.location());
            ConfigurationProblem problem = new ConfigurationProblem(classify(message),
                    ConfigurationProblem.Severity.ERROR, error.location(), null, List.of(), message);
            return new ValidationResult(Set.of(), Set.of(), Map.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(problem));
        }

        private static ConfigurationProblem.Code classify(String message) {
            String lower = message.toLowerCase();
            if (lower.contains("xml") || lower.contains("element") || lower.contains("attribute")) {
                return ConfigurationProblem.Code.XML;
            }
            if (lower.contains("if-property") || lower.contains("unless-property")
                    || lower.contains("activation condition")) return ConfigurationProblem.Code.CONDITION;
            if (lower.contains("beanscope") || lower.contains("custom scope")
                    || lower.contains("scope violation")) return ConfigurationProblem.Code.SCOPE;
            if (lower.contains("property")) return ConfigurationProblem.Code.PROPERTY;
            if (lower.contains("overlay") || lower.contains("replace")) return ConfigurationProblem.Code.OVERLAY;
            if (lower.contains("cycle") || lower.contains("circular")) return ConfigurationProblem.Code.CYCLE;
            if (lower.contains("unknown bean") || lower.contains("reference")) return ConfigurationProblem.Code.REFERENCE;
            if (lower.contains("constructor") || lower.contains("factory") || lower.contains("method")
                    || lower.contains("setter") || lower.contains("ambiguous")) return ConfigurationProblem.Code.EXECUTABLE;
            if (lower.contains("assign") || lower.contains("type") || lower.contains("class")) {
                return ConfigurationProblem.Code.TYPE;
            }
            return ConfigurationProblem.Code.CONFIGURATION;
        }

        private static String stripLocation(String message, SourceLocation location) {
            if (location == null) return message;
            String prefix = location + ": ";
            return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
        }

        private void emit(BeanEvent event) {
            for (BeanContextListener listener : List.copyOf(listeners)) {
                try {
                    listener.onEvent(event);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    throw new BeanException(event.location(), "BeanContextListener failed for " + event.kind(), failure);
                }
            }
        }

        private void emitFailure(BeanEvent event, Throwable primary) {
            try {
                emit(event);
            } catch (Throwable listenerFailure) {
                if (listenerFailure != primary) primary.addSuppressed(listenerFailure);
            }
        }

        private static RuntimeException rethrow(Throwable failure) {
            if (failure instanceof RuntimeException runtime) return runtime;
            if (failure instanceof Error error) throw error;
            return new BeanException("Configuration operation failed: " + failure, failure);
        }

        @FunctionalInterface
        private interface DocumentAction { Document run(); }

        @FunctionalInterface
        private interface PreparedAction { Prepared run(); }

        private record Prepared(Document document, DefaultConverterRegistry contextConverters,
                                PropertyExpander expander, GraphCompiler compiler,
                                LinkedHashMap<String, ExternalBinding> localBindings,
                                LinkedHashMap<String, Type> externalTypes) {}

        private static Map<String, String> readProperties(Path file) {
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                values.load(input);
            } catch (IOException e) {
                throw new BeanException("Cannot read properties file " + file, e);
            }
            return ordered(values);
        }

        private static Map<String, String> ordered(Properties properties) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            properties.stringPropertyNames().stream().sorted()
                    .forEach(name -> result.put(name, properties.getProperty(name)));
            return result;
        }

        private static Map<String, String> systemProperties() {
            return ordered(System.getProperties());
        }

        private static Map<String, String> environmentProperties() {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            System.getenv().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.put("env." + entry.getKey(), entry.getValue()));
            return result;
        }

        private static String normalizeResource(String resource) {
            Objects.requireNonNull(resource, "resource");
            String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
            if (normalized.isBlank()) throw new IllegalArgumentException("resource");
            return normalized;
        }

        static final class ReloadPreparation implements AutoCloseable {
        private final Builder owner;
        private final Prepared prepared;
        private final ValidationResult validation;
        private final ConfigurationRevision revision;
        private final ClassLoader classLoader;
        private BeanContext context;
        private boolean startAttempted;
        private boolean transferred;
        private boolean closed;

        private ReloadPreparation(Builder owner, Prepared prepared, ValidationResult validation,
                                  ConfigurationRevision revision, ClassLoader classLoader) {
            this.owner = owner;
            this.prepared = prepared;
            this.validation = validation;
            this.revision = revision;
            this.classLoader = classLoader;
        }

        private ReloadPreparation(ValidationResult validation, ClassLoader classLoader) {
            this(null, null, validation, null, classLoader);
        }

        static ReloadPreparation invalid(ValidationResult validation, ClassLoader classLoader) {
            return new ReloadPreparation(validation, classLoader);
        }

        ValidationResult validation() { return validation; }
        ConfigurationRevision revision() { return revision; }
        ClassLoader classLoader() { return classLoader; }

        synchronized BeanContext start() {
            if (closed) throw new IllegalStateException("Reload preparation is closed");
            if (prepared == null) throw new IllegalStateException("Invalid configuration cannot be started");
            if (startAttempted) {
                if (context == null) throw new IllegalStateException("Candidate startup already failed");
                return context;
            }
            startAttempted = true;
            context = owner.start(prepared);
            return context;
        }

        synchronized BeanContext transfer() {
            if (context == null) throw new IllegalStateException("Candidate is not started");
            if (transferred) throw new IllegalStateException("Candidate already transferred");
            transferred = true;
            BeanContext result = context;
            context = null;
            closed = true;
            return result;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (context != null) context.close();
            context = null;
        }
    }

    private static ClassLoader defaultClassLoader() {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return loader != null ? loader : XmlBeans.class.getClassLoader();
        }
    }
}
