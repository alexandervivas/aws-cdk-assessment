package com.tikkie.person.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PersonId(UUID value) {

    // UUID.fromString also accepts short groups such as "1-1-1-1-1", so only the canonical 8-4-4-4-12 form is let in.
    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    public PersonId {
        Objects.requireNonNull(value, "value");
    }

    public static PersonId random() {
        return new PersonId(UUID.randomUUID());
    }

    public static PersonId parse(String field, String raw) {
        if (raw == null || !CANONICAL.matcher(raw).matches()) {
            throw ValidationException.of(field, "invalid_format", "must be a UUID");
        }
        return new PersonId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
