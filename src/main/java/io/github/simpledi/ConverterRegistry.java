package io.github.simpledi;

import java.util.Map;

/** Mutable registry copied into each BeanContext at load time. */
public interface ConverterRegistry {
    <T> ConverterRegistry register(Class<T> type, ValueConverter<? extends T> converter);

    boolean hasConverter(Class<?> type);

    Map<Class<?>, ValueConverter<?>> converters();
}
