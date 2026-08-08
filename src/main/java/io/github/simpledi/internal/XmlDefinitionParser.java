package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.SourceLocation;
import io.github.simpledi.XmlLimits;
import io.github.simpledi.internal.Definitions.ArgumentDef;
import io.github.simpledi.internal.Definitions.ArrayValue;
import io.github.simpledi.internal.Definitions.AutoClosePolicy;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.CallDef;
import io.github.simpledi.internal.Definitions.ConditionDef;
import io.github.simpledi.internal.Definitions.ConstantValue;
import io.github.simpledi.internal.Definitions.Document;
import io.github.simpledi.internal.Definitions.FactoryDef;
import io.github.simpledi.internal.Definitions.InjectionDef;
import io.github.simpledi.internal.Definitions.ListValue;
import io.github.simpledi.internal.Definitions.Literal;
import io.github.simpledi.internal.Definitions.MapEntry;
import io.github.simpledi.internal.Definitions.MapValue;
import io.github.simpledi.internal.Definitions.NestedBean;
import io.github.simpledi.internal.Definitions.NullValue;
import io.github.simpledi.internal.Definitions.OptionalRef;
import io.github.simpledi.internal.Definitions.OptionalValue;
import io.github.simpledi.internal.Definitions.Ownership;
import io.github.simpledi.internal.Definitions.PropertiesValue;
import io.github.simpledi.internal.Definitions.PropertyDef;
import io.github.simpledi.internal.Definitions.Ref;
import io.github.simpledi.internal.Definitions.SetValue;
import io.github.simpledi.internal.Definitions.Scope;
import io.github.simpledi.internal.Definitions.SupplierRef;
import io.github.simpledi.internal.Definitions.ValueDef;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict, location-aware, XXE-safe parser with recursive file/classpath includes. */
public final class XmlDefinitionParser {
    private enum Kind { FILE, CLASSPATH, INLINE }

    private record Resource(Kind kind, String key, Path display, Path file, String classpathName) {}

    private static final Set<String> EMPTY = Set.of();
    private final ClassLoader classLoader;
    private final XmlLimits limits;
    private final Set<String> externalNames;
    private final boolean fileIncludesEnabled;
    private final boolean classpathIncludesEnabled;
    private final Path configuredFileRoot;
    private final String configuredClasspathRoot;
    private final boolean cumulativeLimits;
    private boolean parseStarted;
    private final LinkedHashMap<String, BeanDef> beans = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
    private final Set<String> loaded = new LinkedHashSet<>();
    private final Deque<String> includeStack = new ArrayDeque<>();
    private int documents;
    private int elements;
    private long totalInputUnits;
    private Path effectiveFileRoot;
    private String effectiveClasspathRoot;

    public XmlDefinitionParser(ClassLoader classLoader, XmlLimits limits) {
        this(classLoader, limits, Set.of());
    }

    public XmlDefinitionParser(ClassLoader classLoader, XmlLimits limits, Set<String> externalNames) {
        this(classLoader, limits, externalNames, true, true, null, null, false);
    }

    public XmlDefinitionParser(ClassLoader classLoader, XmlLimits limits, Set<String> externalNames,
                               boolean fileIncludesEnabled, boolean classpathIncludesEnabled,
                               Path fileRoot, String classpathRoot) {
        this(classLoader, limits, externalNames, fileIncludesEnabled, classpathIncludesEnabled,
                fileRoot, classpathRoot, false);
    }

    public XmlDefinitionParser(ClassLoader classLoader, XmlLimits limits, Set<String> externalNames,
                               boolean fileIncludesEnabled, boolean classpathIncludesEnabled,
                               Path fileRoot, String classpathRoot, boolean cumulativeLimits) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.externalNames = Set.copyOf(Objects.requireNonNull(externalNames, "externalNames"));
        this.fileIncludesEnabled = fileIncludesEnabled;
        this.classpathIncludesEnabled = classpathIncludesEnabled;
        this.configuredFileRoot = fileRoot == null ? null : fileRoot.toAbsolutePath().normalize();
        this.configuredClasspathRoot = classpathRoot == null ? null : normalizeClasspathRoot(classpathRoot);
        this.cumulativeLimits = cumulativeLimits;
        if (this.externalNames.size() > limits.maxBeans()) {
            throw new IllegalArgumentException("External binding count exceeds bean limit: " + limits.maxBeans());
        }
    }

    public Document parse(Path source) {
        reset();
        Path normalized = source.toAbsolutePath().normalize();
        effectiveFileRoot = configuredFileRoot != null ? configuredFileRoot : normalized.getParent();
        normalized = requireWithinFileRoot(normalized, new SourceLocation(normalized, 1, 1));
        parseResource(new Resource(Kind.FILE, fileKey(normalized), normalized, normalized, null));
        return finish();
    }

    public Document parseResource(String resource) {
        reset();
        String normalized = normalizeClasspath(resource);
        effectiveClasspathRoot = configuredClasspathRoot != null ? configuredClasspathRoot : classpathParent(normalized);
        requireWithinClasspathRoot(normalized, new SourceLocation(Path.of("classpath").resolve(normalized), 1, 1));
        parseResource(new Resource(Kind.CLASSPATH, "classpath:" + normalized,
                Path.of("classpath").resolve(normalized), null, normalized));
        return finish();
    }

    public Document parse(InputStream input, String sourceName) {
        Objects.requireNonNull(input, "input");
        reset();
        Resource resource = inlineResource(sourceName);
        parseInline(resource, input);
        return finish();
    }

    public Document parse(Reader reader, String sourceName) {
        Objects.requireNonNull(reader, "reader");
        reset();
        Resource resource = inlineResource(sourceName);
        parseInline(resource, reader);
        return finish();
    }

    public Document parseXml(String xml, String sourceName) {
        return parse(new StringReader(Objects.requireNonNull(xml, "xml")), sourceName);
    }

    private void reset() {
        beans.clear();
        aliases.clear();
        loaded.clear();
        includeStack.clear();
        if (!cumulativeLimits || !parseStarted) {
            documents = 0;
            elements = 0;
            totalInputUnits = 0;
        }
        parseStarted = true;
        effectiveFileRoot = configuredFileRoot;
        effectiveClasspathRoot = configuredClasspathRoot;
    }

    private Document finish() {
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        for (String alias : aliases.keySet()) {
            resolved.put(alias, resolveAlias(alias, new ArrayDeque<>()));
        }
        return new Document(beans, resolved);
    }

    private String resolveAlias(String alias, Deque<String> path) {
        if (path.contains(alias)) {
            path.addLast(alias);
            throw new BeanException("Alias cycle: " + String.join(" -> ", path));
        }
        String target = aliases.get(alias);
        if (target == null) {
            if (beans.containsKey(alias) || externalNames.contains(alias)) return alias;
            throw new BeanException("Alias refers to unknown bean or alias '" + alias + "'");
        }
        path.addLast(alias);
        try {
            if (beans.containsKey(target) || externalNames.contains(target)) return target;
            if (aliases.containsKey(target)) return resolveAlias(target, path);
            throw new BeanException("Alias '" + alias + "' refers to unknown bean or alias '" + target + "'");
        } finally {
            path.removeLast();
        }
    }

    private void parseResource(Resource resource) {
        if (loaded.contains(resource.key())) return;
        if (includeStack.contains(resource.key())) {
            includeStack.addLast(resource.key());
            throw new BeanException("Include cycle: " + String.join(" -> ", includeStack));
        }
        beginDocument(resource);
        includeStack.addLast(resource.key());
        try (InputStream input = open(resource)) {
            parseDocument(resource, new LimitedInputStream(input, resource));
            loaded.add(resource.key());
        } catch (IOException e) {
            throw new BeanException(new SourceLocation(resource.display(), 1, 1),
                    "Cannot read XML: " + e.getMessage(), e);
        } finally {
            includeStack.removeLast();
        }
    }

    private void parseInline(Resource resource, InputStream input) {
        beginDocument(resource);
        parseDocument(resource, new LimitedInputStream(input, resource));
    }

    private void parseInline(Resource resource, Reader input) {
        beginDocument(resource);
        parseDocument(resource, new LimitedReader(input, resource));
    }

    private void beginDocument(Resource resource) {
        if (++documents > limits.maxDocuments()) {
            throw new BeanException(new SourceLocation(resource.display(), 1, 1),
                    "XML document limit exceeded: " + limits.maxDocuments());
        }
    }

    private InputStream open(Resource resource) throws IOException {
        if (resource.kind() == Kind.FILE) return new BufferedInputStream(Files.newInputStream(resource.file()));
        if (resource.kind() == Kind.CLASSPATH) {
            InputStream input = classLoader.getResourceAsStream(resource.classpathName());
            if (input == null) throw new IOException("Classpath resource not found: " + resource.classpathName());
            return new BufferedInputStream(input);
        }
        throw new IOException("Inline resource cannot be reopened");
    }

    private void parseDocument(Resource resource, InputStream input) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        secure(factory, resource.display());
        try {
            parseDocument(resource, factory.createXMLStreamReader(input));
        } catch (XMLStreamException e) {
            throw xmlFailure(resource, null, e);
        }
    }

    private void parseDocument(Resource resource, Reader input) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        secure(factory, resource.display());
        try {
            parseDocument(resource, factory.createXMLStreamReader(input));
        } catch (XMLStreamException e) {
            throw xmlFailure(resource, null, e);
        }
    }

    private void parseDocument(Resource resource, XMLStreamReader reader) {
        try {
            Cursor c = new Cursor(reader, resource.display());
            c.moveToRoot();
            requireElement(c, "beans");
            requireOnlyAttributes(c, Set.of("version", "default-lazy"));
            String version = blankToNull(attr(c, "version"));
            if (version != null && !"2".equals(version)) {
                throw new BeanException(c.location(), "Unsupported simple-di XML version '" + version
                        + "'; supported version is 2");
            }
            boolean defaultLazy = bool(attr(c, "default-lazy"), false, c.location(), "default-lazy");
            while (c.nextTag() == XMLStreamConstants.START_ELEMENT) {
                switch (c.name()) {
                    case "include" -> parseInclude(c, resource);
                    case "alias" -> parseAlias(c);
                    case "bean" -> addBean(parseBean(c, true, defaultLazy));
                    default -> throw c.error("Only <include>, <alias>, and <bean> are allowed in <beans>");
                }
            }
            if (!"beans".equals(c.name())) throw c.error("Expected </beans>");
            while (reader.hasNext()) {
                int event = reader.next();
                c.observe(event);
                if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.DTD
                        || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw c.error("Unexpected content after </beans>");
                }
            }
        } catch (XMLStreamException e) {
            throw xmlFailure(resource, reader, e);
        } finally {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // The caller owns the underlying input and is authoritative for closure.
            }
        }
    }

    private BeanException xmlFailure(Resource resource, XMLStreamReader reader, XMLStreamException error) {
        if (error.getCause() instanceof BeanException bean) return bean;
        SourceLocation location = reader == null ? new SourceLocation(resource.display(), 1, 1)
                : new SourceLocation(resource.display(), reader.getLocation().getLineNumber(),
                reader.getLocation().getColumnNumber());
        return new BeanException(location, "Invalid XML: " + error.getMessage(), error);
    }

    private void parseInclude(Cursor c, Resource current) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("file", "resource"));
        String file = blankToNull(attr(c, "file"));
        String resource = blankToNull(attr(c, "resource"));
        if ((file == null) == (resource == null)) {
            throw new BeanException(location, "<include> requires exactly one of file or resource");
        }
        c.requireEmpty("include");
        if (file != null) {
            if (!fileIncludesEnabled) throw new BeanException(location, "File includes are disabled");
            if (current.kind() != Kind.FILE) {
                throw new BeanException(location, "file includes are allowed only from file-backed XML");
            }
            Path included = current.file().getParent().resolve(file).normalize().toAbsolutePath();
            included = requireWithinFileRoot(included, location);
            parseResource(new Resource(Kind.FILE, fileKey(included), included, included, null));
        } else {
            if (!classpathIncludesEnabled) throw new BeanException(location, "Classpath includes are disabled");
            if (current.kind() == Kind.INLINE) {
                throw new BeanException(location, "Includes are not allowed for inline XML; load a Path or classpath resource");
            }
            String included = resource.startsWith("/") ? normalizeClasspath(resource)
                    : resolveClasspath(current, resource);
            requireWithinClasspathRoot(included, location);
            parseResource(new Resource(Kind.CLASSPATH, "classpath:" + included,
                    Path.of("classpath").resolve(included), null, included));
        }
    }

    private static String resolveClasspath(Resource current, String child) {
        if (current.kind() != Kind.CLASSPATH) return normalizeClasspath(child);
        int slash = current.classpathName().lastIndexOf('/');
        String parent = slash < 0 ? "" : current.classpathName().substring(0, slash + 1);
        return normalizeClasspath(parent + child);
    }

    private void parseAlias(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("name", "alias"));
        String name = attr(c, "name");
        String alias = attr(c, "alias");
        requireNonBlank(name, location, "<alias> requires name");
        requireNonBlank(alias, location, "<alias> requires alias");
        c.requireEmpty("alias");
        if (beans.containsKey(alias) || aliases.containsKey(alias) || externalNames.contains(alias)) {
            throw new BeanException(location, "Duplicate bean/alias name '" + alias + "'");
        }
        aliases.put(alias, name);
    }

    private void addBean(BeanDef bean) {
        if (beans.size() + externalNames.size() >= limits.maxBeans()) {
            throw new BeanException(bean.location(), "Bean limit exceeded: " + limits.maxBeans());
        }
        if (beans.containsKey(bean.id()) || aliases.containsKey(bean.id()) || externalNames.contains(bean.id())) {
            throw new BeanException(bean.location(), "Duplicate bean/alias name '" + bean.id() + "'");
        }
        beans.put(bean.id(), bean);
    }

    private record ParsedArguments(String signature, List<ArgumentDef> values) {}

    private BeanDef parseBean(Cursor c, boolean topLevel, boolean defaultLazy) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("id", "class", "init-method", "destroy-method", "auto-close",
                "scope", "ownership", "lazy", "depends-on", "replaces",
                "if-property", "if-value", "unless-property", "unless-value", "match-if-missing"));
        String id = blankToNull(attr(c, "id"));
        String className = attr(c, "class");
        if (topLevel) requireNonBlank(id, location, "Top-level <bean> requires id");
        if (!topLevel && id != null) throw new BeanException(location, "Nested <bean> cannot declare id");
        requireNonBlank(className, location, "<bean> requires class");
        String scopeText = blankToNull(attr(c, "scope"));
        if (!topLevel && scopeText != null) throw new BeanException(location, "Nested <bean> cannot declare scope");
        String scopeName = scopeText == null ? "singleton" : scopeText;
        Scope scope = switch (scopeName) {
            case "singleton" -> Scope.SINGLETON;
            case "prototype" -> Scope.PROTOTYPE;
            default -> Scope.CUSTOM;
        };
        if (scope == Scope.CUSTOM && !scopeName.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw new BeanException(location, "Invalid custom scope name '" + scopeName + "'");
        }
        String ownershipText = blankToNull(attr(c, "ownership"));
        if (!topLevel && ownershipText != null) {
            throw new BeanException(location, "Nested <bean> cannot declare ownership");
        }
        Ownership ownership;
        if (!topLevel) {
            ownership = Ownership.INHERIT;
        } else if (ownershipText == null) {
            ownership = scope == Scope.PROTOTYPE ? Ownership.CALLER : Ownership.CONTEXT;
        } else {
            ownership = switch (ownershipText) {
                case "context" -> Ownership.CONTEXT;
                case "caller" -> Ownership.CALLER;
                case "external" -> Ownership.EXTERNAL;
                default -> throw new BeanException(location,
                        "Attribute ownership must be context, caller, or external");
            };
        }
        if (scope == Scope.SINGLETON && ownership == Ownership.CALLER) {
            throw new BeanException(location, "Caller ownership is valid only for prototype beans");
        }
        if (scope == Scope.CUSTOM && ownership != Ownership.CONTEXT) {
            throw new BeanException(location, "Custom-scoped beans are owned by their registered BeanScope");
        }
        String lazyText = attr(c, "lazy");
        if (!topLevel && lazyText != null) throw new BeanException(location, "Nested <bean> cannot declare lazy");
        if ((scope == Scope.PROTOTYPE || scope == Scope.CUSTOM) && lazyText != null) {
            throw new BeanException(location, "Attribute lazy is not valid for prototype or custom-scoped beans");
        }
        boolean lazy = scope != Scope.SINGLETON || bool(lazyText, defaultLazy, location, "lazy");
        List<String> dependsOn = splitNames(attr(c, "depends-on"), location);
        String replaces = blankToNull(attr(c, "replaces"));
        if (!topLevel && replaces != null) throw new BeanException(location, "Nested <bean> cannot declare replaces");
        ConditionDef condition = parseCondition(c, topLevel, location);
        String init = blankToNull(attr(c, "init-method"));
        String destroy = blankToNull(attr(c, "destroy-method"));
        AutoClosePolicy autoClosePolicy = parseAutoClose(attr(c, "auto-close"), location);
        ParsedArguments constructor = null;
        FactoryDef factory = null;
        List<InjectionDef> injections = new ArrayList<>();
        while (c.nextTag() == XMLStreamConstants.START_ELEMENT) {
            switch (c.name()) {
                case "constructor" -> {
                    if (constructor != null) throw c.error("Duplicate <constructor>");
                    if (factory != null) throw c.error("A bean cannot have both <constructor> and <factory>");
                    constructor = parseConstructor(c);
                }
                case "factory" -> {
                    if (factory != null) throw c.error("Duplicate <factory>");
                    if (constructor != null) throw c.error("A bean cannot have both <constructor> and <factory>");
                    factory = parseFactory(c);
                }
                case "property" -> injections.add(parseProperty(c));
                case "call" -> injections.add(parseCall(c));
                case "init" -> {
                    if (init != null) throw c.error("Init method specified more than once");
                    init = parseMethodElement(c, "init");
                }
                case "destroy" -> {
                    if (destroy != null) throw c.error("Destroy method specified more than once");
                    destroy = parseMethodElement(c, "destroy");
                }
                default -> throw c.error("Unknown element <" + c.name() + "> in <bean>");
            }
        }
        if (!"bean".equals(c.name())) throw c.error("Expected </bean>");
        String constructorSignature = constructor == null ? null : constructor.signature();
        List<ArgumentDef> constructorArgs = constructor == null ? List.of() : constructor.values();
        return new BeanDef(id, className, factory, constructorSignature, constructorArgs,
                List.copyOf(injections), init, destroy, autoClosePolicy, scope, scopeName, ownership, lazy,
                List.copyOf(dependsOn), replaces, condition, location);
    }

    private static ConditionDef parseCondition(Cursor c, boolean topLevel, SourceLocation location) {
        String ifProperty = blankToNull(attr(c, "if-property"));
        String ifValue = attr(c, "if-value");
        String unlessProperty = blankToNull(attr(c, "unless-property"));
        String unlessValue = attr(c, "unless-value");
        String missingText = blankToNull(attr(c, "match-if-missing"));
        if (!topLevel && (ifProperty != null || ifValue != null || unlessProperty != null
                || unlessValue != null || missingText != null)) {
            throw new BeanException(location, "Nested <bean> cannot declare activation conditions");
        }
        if (ifProperty != null && unlessProperty != null) {
            throw new BeanException(location, "A bean cannot declare both if-property and unless-property");
        }
        if (ifValue != null && ifProperty == null) {
            throw new BeanException(location, "if-value requires if-property");
        }
        if (unlessValue != null && unlessProperty == null) {
            throw new BeanException(location, "unless-value requires unless-property");
        }
        if (unlessProperty != null && missingText != null) {
            throw new BeanException(location, "match-if-missing is valid only with if-property; "
                    + "unless-property already matches a missing property");
        }
        if (ifProperty == null && unlessProperty == null) {
            if (missingText != null) throw new BeanException(location,
                    "match-if-missing requires if-property or unless-property");
            return null;
        }
        boolean matchIfMissing = bool(missingText, false, location, "match-if-missing");
        return new ConditionDef(ifProperty != null ? ifProperty : unlessProperty,
                ifProperty != null ? ifValue : unlessValue, unlessProperty != null, matchIfMissing, location);
    }

    private static AutoClosePolicy parseAutoClose(String value, SourceLocation location) {
        if (value == null || value.isBlank() || "fallback".equals(value)) return AutoClosePolicy.FALLBACK;
        return switch (value) {
            case "before" -> AutoClosePolicy.BEFORE;
            case "after" -> AutoClosePolicy.AFTER;
            case "never" -> AutoClosePolicy.NEVER;
            default -> throw new BeanException(location,
                    "Attribute auto-close must be fallback, before, after, or never");
        };
    }

    private FactoryDef parseFactory(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("class", "bean", "method", "signature"));
        String owner = blankToNull(attr(c, "class"));
        String bean = blankToNull(attr(c, "bean"));
        String method = attr(c, "method");
        String signature = blankToNull(attr(c, "signature"));
        if (owner != null && bean != null) throw new BeanException(location, "<factory> cannot specify both class and bean");
        requireNonBlank(method, location, "<factory> requires method");
        List<ArgumentDef> args = parseArguments(c, "factory");
        return new FactoryDef(owner, bean, method, signature, args, location);
    }

    private ParsedArguments parseConstructor(Cursor c) throws XMLStreamException {
        requireOnlyAttributes(c, Set.of("signature"));
        String signature = blankToNull(attr(c, "signature"));
        return new ParsedArguments(signature, parseArguments(c, "constructor"));
    }

    private CallDef parseCall(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("method", "signature"));
        String method = attr(c, "method");
        String signature = blankToNull(attr(c, "signature"));
        requireNonBlank(method, location, "<call> requires method");
        return new CallDef(method, signature, parseArguments(c, "call"), location);
    }

    private List<ArgumentDef> parseArguments(Cursor c, String container) throws XMLStreamException {
        List<ArgumentDef> args = new ArrayList<>();
        boolean selected = false;
        boolean positional = false;
        Set<String> names = new LinkedHashSet<>();
        Set<Integer> indexes = new LinkedHashSet<>();
        while (c.nextTag() == XMLStreamConstants.START_ELEMENT) {
            if (!"arg".equals(c.name())) throw c.error("Only <arg> is allowed in <" + container + ">");
            SourceLocation location = c.location();
            String name = blankToNull(attr(c, "name"));
            String indexText = blankToNull(attr(c, "index"));
            if (name != null && indexText != null) {
                throw new BeanException(location, "<arg> cannot specify both name and index");
            }
            Integer index = null;
            if (indexText != null) {
                try {
                    index = Integer.valueOf(indexText);
                } catch (NumberFormatException e) {
                    throw new BeanException(location, "Argument index must be a non-negative integer", e);
                }
                if (index < 0) throw new BeanException(location, "Argument index must be a non-negative integer");
            }
            boolean hasSelector = name != null || index != null;
            selected |= hasSelector;
            positional |= !hasSelector;
            if (selected && positional) {
                throw new BeanException(location,
                        "Executable arguments must be entirely positional or entirely name/index selected");
            }
            if (name != null && !names.add(name)) throw new BeanException(location, "Duplicate argument name '" + name + "'");
            if (index != null && !indexes.add(index)) throw new BeanException(location, "Duplicate argument index " + index);
            ValueDef value = parseContainerValue(c, "arg", Set.of("name", "index", "value", "ref", "type"));
            args.add(new ArgumentDef(name, index, value, location));
        }
        if (!container.equals(c.name())) throw c.error("Expected </" + container + ">");
        return List.copyOf(args);
    }

    private PropertyDef parseProperty(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        String name = attr(c, "name");
        requireNonBlank(name, location, "<property> requires name");
        ValueDef value = parseContainerValue(c, "property", Set.of("name", "value", "ref", "type"));
        return new PropertyDef(name, value, location);
    }

    private String parseMethodElement(Cursor c, String element) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("method"));
        String method = attr(c, "method");
        requireNonBlank(method, location, "<" + element + "> requires method");
        c.requireEmpty(element);
        return method;
    }

    private ValueDef parseContainerValue(Cursor c, String container, Set<String> allowedAttributes)
            throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, allowedAttributes);
        String literal = attr(c, "value");
        String ref = attr(c, "ref");
        String type = blankToNull(attr(c, "type"));
        if (literal != null && ref != null) throw new BeanException(location, "Specify value or ref, not both");
        if (ref != null && type != null) throw new BeanException(location, "type is only valid with a literal value");
        if (literal != null || ref != null) {
            c.requireEmpty(container);
            return literal != null ? new Literal(literal, type, location) : new Ref(ref, location);
        }
        if (type != null) throw new BeanException(location, "type requires value");
        int event = c.nextTag();
        if (event != XMLStreamConstants.START_ELEMENT) {
            throw new BeanException(location, "<" + container + "> requires value, ref, or one nested value");
        }
        ValueDef value = parseValueElement(c);
        if (c.nextTag() != XMLStreamConstants.END_ELEMENT || !container.equals(c.name())) {
            throw c.error("<" + container + "> must contain exactly one nested value");
        }
        return value;
    }

    private ValueDef parseValueElement(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        return switch (c.name()) {
            case "value" -> parseLiteralElement(c);
            case "ref" -> parseRefElement(c);
            case "supplier" -> parseSupplier(c);
            case "optional-ref" -> parseOptionalRef(c);
            case "constant" -> parseConstant(c);
            case "bean" -> new NestedBean(parseBean(c, false, false), location);
            case "list" -> parseList(c);
            case "set" -> parseSet(c);
            case "map" -> parseMap(c);
            case "properties" -> parseProperties(c);
            case "array" -> parseArray(c);
            case "optional" -> parseOptional(c);
            case "null" -> {
                requireOnlyAttributes(c, EMPTY);
                c.requireEmpty("null");
                yield new NullValue(location);
            }
            default -> throw c.error("Unknown value element <" + c.name() + ">");
        };
    }

    private Literal parseLiteralElement(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("type"));
        String type = blankToNull(attr(c, "type"));
        String text = c.elementText();
        return new Literal(text, type, location);
    }

    private Ref parseRefElement(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("bean"));
        String bean = attr(c, "bean");
        requireNonBlank(bean, location, "<ref> requires bean");
        c.requireEmpty("ref");
        return new Ref(bean, location);
    }

    private SupplierRef parseSupplier(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("ref"));
        String bean = attr(c, "ref");
        requireNonBlank(bean, location, "<supplier> requires ref");
        c.requireEmpty("supplier");
        return new SupplierRef(bean, location);
    }

    private OptionalRef parseOptionalRef(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("bean"));
        String bean = attr(c, "bean");
        requireNonBlank(bean, location, "<optional-ref> requires bean");
        c.requireEmpty("optional-ref");
        return new OptionalRef(bean, location);
    }

    private ConstantValue parseConstant(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("class", "field"));
        String owner = attr(c, "class");
        String field = attr(c, "field");
        requireNonBlank(owner, location, "<constant> requires class");
        requireNonBlank(field, location, "<constant> requires field");
        c.requireEmpty("constant");
        return new ConstantValue(owner, field, location);
    }

    private ListValue parseList(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("immutable"));
        boolean immutable = bool(attr(c, "immutable"), false, location, "immutable");
        return new ListValue(parseValueList(c, "list"), immutable, location);
    }

    private SetValue parseSet(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("immutable"));
        boolean immutable = bool(attr(c, "immutable"), false, location, "immutable");
        return new SetValue(parseValueList(c, "set"), immutable, location);
    }

    private List<ValueDef> parseValueList(Cursor c, String element) throws XMLStreamException {
        List<ValueDef> values = new ArrayList<>();
        while (c.nextTag() == XMLStreamConstants.START_ELEMENT) values.add(parseValueElement(c));
        if (!element.equals(c.name())) throw c.error("Expected </" + element + ">");
        return List.copyOf(values);
    }

    private MapValue parseMap(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("immutable"));
        boolean immutable = bool(attr(c, "immutable"), false, location, "immutable");
        List<MapEntry> entries = new ArrayList<>();
        while (c.nextTag() == XMLStreamConstants.START_ELEMENT) {
            if (!"entry".equals(c.name())) throw c.error("Only <entry> is allowed in <map>");
            entries.add(parseMapEntry(c));
        }
        if (!"map".equals(c.name())) throw c.error("Expected </map>");
        return new MapValue(List.copyOf(entries), immutable, location);
    }

    private MapEntry parseMapEntry(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("key", "key-ref", "key-type", "value", "value-ref", "value-type"));
        String key = attr(c, "key");
        String keyRef = attr(c, "key-ref");
        String keyType = blankToNull(attr(c, "key-type"));
        String value = attr(c, "value");
        String valueRef = attr(c, "value-ref");
        String valueType = blankToNull(attr(c, "value-type"));
        boolean attributeForm = key != null || keyRef != null || value != null || valueRef != null;
        if (attributeForm) {
            if ((key == null) == (keyRef == null)) throw new BeanException(location, "Map entry requires exactly one of key or key-ref");
            if ((value == null) == (valueRef == null)) throw new BeanException(location, "Map entry requires exactly one of value or value-ref");
            if (keyRef != null && keyType != null) throw new BeanException(location, "key-type is only valid with key");
            if (valueRef != null && valueType != null) throw new BeanException(location, "value-type is only valid with value");
            c.requireEmpty("entry");
            ValueDef k = key != null ? new Literal(key, keyType, location) : new Ref(keyRef, location);
            ValueDef v = value != null ? new Literal(value, valueType, location) : new Ref(valueRef, location);
            return new MapEntry(k, v, location);
        }
        if (keyType != null || valueType != null) throw new BeanException(location, "key-type/value-type require attribute-form entry");
        if (c.nextTag() != XMLStreamConstants.START_ELEMENT) throw new BeanException(location, "Map entry requires a key and value");
        ValueDef k = parseValueElement(c);
        if (c.nextTag() != XMLStreamConstants.START_ELEMENT) throw new BeanException(location, "Map entry requires a second value");
        ValueDef v = parseValueElement(c);
        if (c.nextTag() != XMLStreamConstants.END_ELEMENT || !"entry".equals(c.name())) {
            throw c.error("Map entry must contain exactly two value elements");
        }
        return new MapEntry(k, v, location);
    }

    private PropertiesValue parseProperties(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("immutable"));
        boolean immutable = bool(attr(c, "immutable"), false, location, "immutable");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        while (c.nextTag() == XMLStreamConstants.START_ELEMENT) {
            if (!"property".equals(c.name())) throw c.error("Only <property> is allowed in <properties>");
            SourceLocation itemLocation = c.location();
            requireOnlyAttributes(c, Set.of("name", "value"));
            String name = attr(c, "name");
            String value = attr(c, "value");
            requireNonBlank(name, itemLocation, "Properties entry requires name");
            if (value == null) throw new BeanException(itemLocation, "Properties entry requires value");
            c.requireEmpty("property");
            if (values.putIfAbsent(name, value) != null) {
                throw new BeanException(itemLocation, "Duplicate properties key '" + name + "'");
            }
        }
        if (!"properties".equals(c.name())) throw c.error("Expected </properties>");
        return new PropertiesValue(Map.copyOf(values), immutable, location);
    }

    private ArrayValue parseArray(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, Set.of("component-type"));
        String componentType = blankToNull(attr(c, "component-type"));
        List<ValueDef> values = new ArrayList<>();
        while (c.nextTag() == XMLStreamConstants.START_ELEMENT) values.add(parseValueElement(c));
        if (!"array".equals(c.name())) throw c.error("Expected </array>");
        return new ArrayValue(componentType, List.copyOf(values), location);
    }

    private OptionalValue parseOptional(Cursor c) throws XMLStreamException {
        SourceLocation location = c.location();
        requireOnlyAttributes(c, EMPTY);
        int event = c.nextTag();
        if (event == XMLStreamConstants.END_ELEMENT) return new OptionalValue(null, location);
        ValueDef value = parseValueElement(c);
        if (c.nextTag() != XMLStreamConstants.END_ELEMENT || !"optional".equals(c.name())) {
            throw c.error("<optional> accepts at most one value");
        }
        return new OptionalValue(value, location);
    }

    private static List<String> splitNames(String input, SourceLocation location) {
        if (input == null || input.isBlank()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : input.split("[\\s,]+")) {
            if (!part.isBlank() && !result.add(part)) {
                throw new BeanException(location, "Duplicate depends-on entry '" + part + "'");
            }
        }
        return List.copyOf(result);
    }

    private static boolean bool(String value, boolean fallback, SourceLocation location, String attribute) {
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new BeanException(location, "Attribute " + attribute + " must be true or false");
    }

    private Resource inlineResource(String sourceName) {
        String value = sourceName == null || sourceName.isBlank() ? "inline.xml" : sourceName;
        Path display;
        try {
            display = Path.of(value);
        } catch (RuntimeException ignored) {
            display = Path.of("inline.xml");
        }
        return new Resource(Kind.INLINE, "inline:" + value, display, null, null);
    }

    private Path requireWithinFileRoot(Path candidate, SourceLocation location) {
        if (effectiveFileRoot == null) return candidate.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        Path root = effectiveFileRoot.toAbsolutePath().normalize();
        try {
            root = root.toRealPath();
            if (Files.exists(normalized)) {
                normalized = normalized.toRealPath();
            } else if (normalized.getParent() != null && Files.exists(normalized.getParent())) {
                normalized = normalized.getParent().toRealPath().resolve(normalized.getFileName()).normalize();
            }
        } catch (IOException e) {
            throw new BeanException(location, "Cannot validate XML include path: " + e.getMessage(), e);
        }
        if (!normalized.startsWith(root)) {
            throw new BeanException(location, "File XML source escapes include root " + root + ": " + normalized);
        }
        return normalized;
    }

    private void requireWithinClasspathRoot(String resource, SourceLocation location) {
        String root = effectiveClasspathRoot == null ? "" : effectiveClasspathRoot;
        if (!root.isEmpty() && !resource.equals(root) && !resource.startsWith(root + "/")) {
            throw new BeanException(location, "Classpath XML source escapes include root '" + root + "': " + resource);
        }
    }

    private static String classpathParent(String resource) {
        int slash = resource.lastIndexOf('/');
        return slash < 0 ? "" : resource.substring(0, slash);
    }

    private static String normalizeClasspathRoot(String root) {
        String trimmed = Objects.requireNonNull(root, "classpathRoot").trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) return "";
        String normalized = normalizeClasspath(trimmed);
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private final class LimitedInputStream extends FilterInputStream {
        private final Resource resource;
        private long documentUnits;

        private LimitedInputStream(InputStream input, Resource resource) {
            super(input);
            this.resource = resource;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) account(count);
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            if (skipped > 0) account(skipped);
            return skipped;
        }

        @Override
        public void close() {
            // Caller or outer try-with-resources owns the underlying stream.
        }

        private void account(long count) throws IOException {
            documentUnits += count;
            totalInputUnits += count;
            if (documentUnits > limits.maxDocumentBytes()) {
                throw new IOException("XML document byte limit exceeded for " + resource.display()
                        + ": " + limits.maxDocumentBytes());
            }
            if (totalInputUnits > limits.maxTotalBytes()) {
                throw new IOException("Aggregate XML byte limit exceeded: " + limits.maxTotalBytes());
            }
        }
    }

    private final class LimitedReader extends FilterReader {
        private final Resource resource;
        private long documentUnits;

        private LimitedReader(Reader input, Resource resource) {
            super(input);
            this.resource = resource;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override
        public int read(char[] chars, int offset, int length) throws IOException {
            int count = super.read(chars, offset, length);
            if (count > 0) account(count);
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            if (skipped > 0) account(skipped);
            return skipped;
        }

        @Override
        public void close() {
            // Caller owns the underlying reader.
        }

        private void account(long count) throws IOException {
            documentUnits += count;
            totalInputUnits += count;
            if (documentUnits > limits.maxDocumentBytes()) {
                throw new IOException("XML document character limit exceeded for " + resource.display()
                        + ": " + limits.maxDocumentBytes());
            }
            if (totalInputUnits > limits.maxTotalBytes()) {
                throw new IOException("Aggregate XML character limit exceeded: " + limits.maxTotalBytes());
            }
        }
    }

    private static String fileKey(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static String normalizeClasspath(String resource) {
        String value = resource.startsWith("/") ? resource.substring(1) : resource;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (parts.isEmpty()) throw new IllegalArgumentException("Classpath resource escapes root: " + resource);
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("Empty classpath resource");
        return String.join("/", parts);
    }

    private static String attr(Cursor c, String name) {
        return c.reader().getAttributeValue(null, name);
    }

    private static void requireElement(Cursor c, String name) {
        if (!name.equals(c.name())) throw c.error("Expected <" + name + ">");
    }

    private void requireOnlyAttributes(Cursor c, Set<String> allowed) {
        if (c.reader().getAttributeCount() > limits.maxAttributesPerElement()) {
            throw c.error("Attribute limit exceeded: " + limits.maxAttributesPerElement());
        }
        if (c.reader().getNamespaceURI() != null && !c.reader().getNamespaceURI().isEmpty()) {
            throw c.error("XML namespaces are not supported");
        }
        for (int i = 0; i < c.reader().getAttributeCount(); i++) {
            if (c.reader().getAttributeNamespace(i) != null && !c.reader().getAttributeNamespace(i).isEmpty()) {
                throw c.error("Namespaced attributes are not supported");
            }
            String name = c.reader().getAttributeLocalName(i);
            if (!allowed.contains(name)) throw c.error("Unknown attribute '" + name + "' on <" + c.name() + ">");
        }
    }

    private static void requireNonBlank(String value, SourceLocation location, String message) {
        if (value == null || value.isBlank()) throw new BeanException(location, message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void secure(XMLInputFactory factory, Path source) {
        setRequired(factory, XMLInputFactory.SUPPORT_DTD, false, source);
        setRequired(factory, "javax.xml.stream.isSupportingExternalEntities", false, source);
        setRequired(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false, source);
        setRequired(factory, XMLInputFactory.IS_NAMESPACE_AWARE, true, source);
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) -> {
            throw new XMLStreamException("External entity resolution is disabled");
        });
    }

    private static void setRequired(XMLInputFactory factory, String property, Object value, Path source) {
        try {
            factory.setProperty(property, value);
            Object actual = factory.getProperty(property);
            if (!Objects.equals(actual, value)) {
                throw new BeanException(new SourceLocation(source, 1, 1),
                        "XML provider refused required security property " + property);
            }
        } catch (IllegalArgumentException e) {
            throw new BeanException(new SourceLocation(source, 1, 1),
                    "XML provider does not support required security property " + property, e);
        }
    }

    private final class Cursor {
        private final XMLStreamReader reader;
        private final Path source;
        private int depth;
        private long miscTextUnits;

        private Cursor(XMLStreamReader reader, Path source) {
            this.reader = reader;
            this.source = source;
        }

        XMLStreamReader reader() { return reader; }
        String name() { return reader.getLocalName(); }
        SourceLocation location() {
            return new SourceLocation(source, reader.getLocation().getLineNumber(), reader.getLocation().getColumnNumber());
        }
        BeanException error(String message) { return new BeanException(location(), message); }

        void moveToRoot() throws XMLStreamException {
            while (reader.hasNext()) {
                int event = reader.next();
                observe(event);
                if (event == XMLStreamConstants.START_ELEMENT) {
                    onStart();
                    return;
                }
            }
            throw new BeanException(new SourceLocation(source, 1, 1), "Empty XML document");
        }

        int nextTag() throws XMLStreamException {
            while (reader.hasNext()) {
                int event = reader.next();
                observe(event);
                if (event == XMLStreamConstants.START_ELEMENT) {
                    onStart();
                    return event;
                }
                if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    return event;
                }
                if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && !reader.isWhiteSpace()) {
                    throw error("Unexpected text content");
                }
            }
            return XMLStreamConstants.END_DOCUMENT;
        }

        void requireEmpty(String element) throws XMLStreamException {
            int event = nextTag();
            if (event != XMLStreamConstants.END_ELEMENT || !element.equals(name())) {
                throw error("<" + element + "> must be empty");
            }
        }

        String elementText() throws XMLStreamException {
            StringBuilder text = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                observe(event);
                if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    return text.toString();
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    onStart();
                    throw error("<value> may contain text only");
                }
                if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                        || event == XMLStreamConstants.SPACE) {
                    if (text.length() + reader.getTextLength() > limits.maxTextLength()) {
                        throw error("Text length limit exceeded: " + limits.maxTextLength());
                    }
                    text.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                }
            }
            throw error("Unexpected end of XML inside text value");
        }

        private void onStart() {
            elements++;
            depth++;
            if (elements > limits.maxElements()) throw error("XML element limit exceeded: " + limits.maxElements());
            if (depth > limits.maxDepth()) throw error("XML depth limit exceeded: " + limits.maxDepth());
        }

        void observe(int event) {
            rejectDangerous(event);
            if (event == XMLStreamConstants.COMMENT || event == XMLStreamConstants.SPACE
                    || ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                    && reader.isWhiteSpace())) {
                miscTextUnits += reader.getTextLength();
                if (miscTextUnits > limits.maxMiscTextLength()) {
                    throw error("XML comment/whitespace limit exceeded: " + limits.maxMiscTextLength());
                }
            }
        }

        private void rejectDangerous(int event) {
            if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                throw error("DTD and entity declarations are disabled");
            }
        }
    }
}
