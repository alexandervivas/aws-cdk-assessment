package com.tikkie.person.domain;

import java.util.Objects;

public record FieldError(String field, String code, String message) {

    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
