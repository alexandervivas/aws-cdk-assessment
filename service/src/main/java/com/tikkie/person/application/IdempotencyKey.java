package com.tikkie.person.application;

import com.tikkie.person.domain.ValidationException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record IdempotencyKey(UUID value) {

    static final String FIELD = "Idempotency-Key";

    // UUID.fromString also accepts short groups such as "1-1-1-1-1", so only the canonical 8-4-4-4-12 form is let in.
    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
    }

    public static IdempotencyKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.of(FIELD, "required", "must not be blank");
        }
        if (!CANONICAL.matcher(raw).matches()) {
            throw ValidationException.of(FIELD, "invalid_format", "must be a UUID");
        }
        return new IdempotencyKey(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
