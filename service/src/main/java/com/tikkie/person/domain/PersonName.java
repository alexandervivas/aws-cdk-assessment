package com.tikkie.person.domain;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

public record PersonName(Sensitive<String> value) {

    private static final int MAX_LENGTH = 100;
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{M} '’-]+");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    public PersonName {
        Objects.requireNonNull(value, "value");
    }

    public static PersonName parse(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        // NFC first, so a decomposed "u + combining diaeresis" equals the composed letter.
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            throw ValidationException.of(field, "too_long", "must be at most " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(name).matches() || !LETTER.matcher(name).find()) {
            throw ValidationException.of(field, "invalid_characters",
                    "may contain only letters, spaces, apostrophes and hyphens");
        }
        return new PersonName(Sensitive.of(name));
    }

    @Override
    public String toString() {
        return "PersonName[***]";
    }
}
