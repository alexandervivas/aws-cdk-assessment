package com.tikkie.person.domain;

import static java.util.stream.Collectors.joining;

import java.util.List;

public final class ValidationException extends RuntimeException {

    private final List<FieldError> errors;

    public ValidationException(List<FieldError> errors) {
        super(describe(errors));
        this.errors = List.copyOf(errors);
    }

    public static ValidationException of(String field, String code, String message) {
        return new ValidationException(List.of(new FieldError(field, code, message)));
    }

    public List<FieldError> errors() {
        return errors;
    }

    // The message reaches logs, so it names fields and codes only, never rejected values.
    private static String describe(List<FieldError> errors) {
        return errors.stream()
                .map(error -> error.field() + ": " + error.code())
                .collect(joining(", ", "Invalid fields: ", ""));
    }
}
