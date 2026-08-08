package io.github.simpledi;

@FunctionalInterface
public interface ValueConverter<T> {
    T convert(String value, ConversionContext context) throws Exception;
}
