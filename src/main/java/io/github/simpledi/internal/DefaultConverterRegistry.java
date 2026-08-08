package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.ConversionContext;
import io.github.simpledi.ConverterRegistry;
import io.github.simpledi.ValueConverter;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DefaultConverterRegistry implements ConverterRegistry {
    private final LinkedHashMap<Class<?>, ValueConverter<?>> converters;
    private final LinkedHashSet<Class<?>> builtIns;

    public DefaultConverterRegistry() {
        this.converters = new LinkedHashMap<>();
        this.builtIns = new LinkedHashSet<>();
        installDefaults();
        builtIns.addAll(converters.keySet());
    }

    public DefaultConverterRegistry(DefaultConverterRegistry source) {
        this.converters = new LinkedHashMap<>(source.converters);
        this.builtIns = new LinkedHashSet<>(source.builtIns);
    }

    @Override
    public <T> ConverterRegistry register(Class<T> type, ValueConverter<? extends T> converter) {
        if (type == null) throw new IllegalArgumentException("type");
        if (converter == null) throw new IllegalArgumentException("converter");
        converters.put(Types.wrap(type), converter);
        return this;
    }

    @Override
    public boolean hasConverter(Class<?> type) {
        return converters.containsKey(Types.wrap(type));
    }

    @Override
    public Map<Class<?>, ValueConverter<?>> converters() {
        return Collections.unmodifiableMap(converters);
    }

    public boolean isBuiltIn(Class<?> type) {
        return builtIns.contains(Types.wrap(type));
    }

    public Object convert(String value, Class<?> target, ConversionContext context) {
        Class<?> wrapped = Types.wrap(target);
        if (wrapped == String.class || target == Object.class) return value;
        if (wrapped == Class.class) return Types.load(value, context.classLoader(), context.location());
        if (wrapped.isEnum()) return enumValue(value, wrapped, context);

        ValueConverter<?> converter = converters.get(wrapped);
        if (converter == null) {
            throw new BeanException(context.location(), "No converter from String to " + target.getTypeName());
        }
        try {
            Object converted = converter.convert(value, context);
            if (converted == null) {
                throw new BeanException(context.location(), "Converter returned null for " + target.getTypeName());
            }
            if (!wrapped.isInstance(converted)) {
                throw new BeanException(context.location(), "Converter for " + target.getTypeName()
                        + " returned " + converted.getClass().getTypeName());
            }
            return converted;
        } catch (BeanException e) {
            throw e;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError fatal) throw fatal;
            String detail = context.sensitive() ? e.getClass().getSimpleName() : String.valueOf(e.getMessage());
            Throwable cause = context.sensitive() ? null : e;
            throw new BeanException(context.location(),
                    "Cannot convert " + rendered(value, context) + " to " + target.getTypeName() + ": " + detail, cause);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(String value, Class<?> type, ConversionContext context) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type, value);
        } catch (IllegalArgumentException e) {
            String constants = java.util.Arrays.stream(type.getEnumConstants())
                    .map(Object::toString).collect(java.util.stream.Collectors.joining(", "));
            throw new BeanException(context.location(), "Invalid " + type.getTypeName() + " value " + rendered(value, context)
                    + ". Expected one of: " + constants, context.sensitive() ? null : e);
        }
    }

    private static String rendered(String value, ConversionContext context) {
        return context.sensitive() ? "<redacted>" : "'" + value + "'";
    }

    public void clear() {
        converters.clear();
        builtIns.clear();
    }

    private void installDefaults() {
        register(Boolean.class, (v, c) -> {
            if ("true".equalsIgnoreCase(v)) return true;
            if ("false".equalsIgnoreCase(v)) return false;
            throw new IllegalArgumentException("expected true or false");
        });
        register(Byte.class, (v, c) -> Byte.valueOf(v));
        register(Short.class, (v, c) -> Short.valueOf(v));
        register(Integer.class, (v, c) -> Integer.valueOf(v));
        register(Long.class, (v, c) -> Long.valueOf(v));
        register(Float.class, (v, c) -> Float.valueOf(v));
        register(Double.class, (v, c) -> Double.valueOf(v));
        register(Character.class, (v, c) -> {
            if (v.length() != 1) throw new IllegalArgumentException("expected exactly one character");
            return v.charAt(0);
        });
        register(BigInteger.class, (v, c) -> new BigInteger(v));
        register(BigDecimal.class, (v, c) -> new BigDecimal(v));
        register(Path.class, (v, c) -> Path.of(v));
        register(File.class, (v, c) -> new File(v));
        register(URI.class, (v, c) -> URI.create(v));
        register(URL.class, (v, c) -> URI.create(v).toURL());
        register(Duration.class, (v, c) -> Duration.parse(v));
        register(Period.class, (v, c) -> Period.parse(v));
        register(Instant.class, (v, c) -> Instant.parse(v));
        register(LocalDate.class, (v, c) -> LocalDate.parse(v));
        register(LocalTime.class, (v, c) -> LocalTime.parse(v));
        register(LocalDateTime.class, (v, c) -> LocalDateTime.parse(v));
        register(OffsetTime.class, (v, c) -> OffsetTime.parse(v));
        register(OffsetDateTime.class, (v, c) -> OffsetDateTime.parse(v));
        register(ZonedDateTime.class, (v, c) -> ZonedDateTime.parse(v));
        register(Year.class, (v, c) -> Year.parse(v));
        register(YearMonth.class, (v, c) -> YearMonth.parse(v));
        register(MonthDay.class, (v, c) -> MonthDay.parse(v));
        register(Pattern.class, (v, c) -> Pattern.compile(v));
        register(UUID.class, (v, c) -> UUID.fromString(v));
        register(Charset.class, (v, c) -> Charset.forName(v));
        register(Locale.class, (v, c) -> Locale.forLanguageTag(v.replace('_', '-')));
        register(Currency.class, (v, c) -> Currency.getInstance(v));
        register(ZoneId.class, (v, c) -> ZoneId.of(v));
    }
}
