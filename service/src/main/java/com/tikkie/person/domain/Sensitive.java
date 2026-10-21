package com.tikkie.person.domain;

import java.util.Objects;

public final class Sensitive<T> {

    private static final String MASK = "***";

    private final T value;

    private Sensitive(T value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static <T> Sensitive<T> of(T value) {
        return new Sensitive<>(value);
    }

    public T reveal() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Sensitive<?> that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return MASK;
    }
}
