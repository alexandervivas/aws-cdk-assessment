package com.tikkie.person.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Values captured after a failure are null; call throwIfAny() before using any of them.
public final class FieldErrors {

    private final List<FieldError> errors = new ArrayList<>();

    public <T> T capture(Supplier<T> parser) {
        try {
            return parser.get();
        } catch (ValidationException e) {
            errors.addAll(e.errors());
            return null;
        }
    }

    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}
