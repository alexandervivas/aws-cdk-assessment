package com.tikkie.person.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public record PostalCode(Sensitive<String> value) {

    private static final int MAX_LENGTH = 20;
    private static final Pattern DUTCH = Pattern.compile("[1-9][0-9]{3} ?[A-Z]{2}");

    public static PostalCode parse(String field, String raw, CountryCode country) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        if (code.length() > MAX_LENGTH) {
            throw ValidationException.of(field, "too_long", "must be at most " + MAX_LENGTH + " characters");
        }
        if (country.value().equals("NL")) {
            return new PostalCode(Sensitive.of(normaliseDutch(field, code)));
        }
        return new PostalCode(Sensitive.of(code));
    }

    private static String normaliseDutch(String field, String code) {
        if (!DUTCH.matcher(code).matches()) {
            throw ValidationException.of(field, "invalid_format", "must look like 1234 AB");
        }
        String compact = code.replace(" ", "");
        return compact.substring(0, 4) + " " + compact.substring(4);
    }
}
