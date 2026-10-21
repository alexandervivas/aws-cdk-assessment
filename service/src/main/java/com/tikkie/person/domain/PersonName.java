package com.tikkie.person.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record PersonName(Sensitive<String> value) {

    static final int MAX_NAME = 100;
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{M} '’-]+");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    public PersonName {
        Objects.requireNonNull(value, "value");
        String name = value.reveal();
        if (!name.equals(FreeText.strip(name))) {
            throw new IllegalArgumentException("name must be trimmed and NFC-normalised");
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME + " characters");
        }
        if (!isAllowed(name)) {
            throw new IllegalArgumentException("name contains characters that are not allowed");
        }
    }

    public static PersonName parse(String field, String raw) {
        String name = FreeText.strip(raw);
        if (name.isEmpty()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME) {
            throw ValidationException.of(field, "too_long", "must be at most " + MAX_NAME + " characters");
        }
        if (!isAllowed(name)) {
            throw ValidationException.of(field, "invalid_characters",
                    "may contain only letters, spaces, apostrophes and hyphens");
        }
        return new PersonName(Sensitive.of(name));
    }

    private static boolean isAllowed(String name) {
        return ALLOWED.matcher(name).matches() && LETTER.matcher(name).find();
    }

    @Override
    public String toString() {
        return "PersonName[***]";
    }
}
