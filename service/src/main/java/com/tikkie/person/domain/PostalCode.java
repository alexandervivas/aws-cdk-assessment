package com.tikkie.person.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PostalCode(Sensitive<String> value) {

    static final int MAX_POSTAL_CODE = 20;
    private static final Pattern DUTCH = Pattern.compile("[1-9][0-9]{3} ?[A-Z]{2}");
    private static final Pattern ASCII = Pattern.compile("\\p{ASCII}+");

    public PostalCode {
        Objects.requireNonNull(value, "value");
    }

    public static PostalCode parse(String field, String raw, CountryCode country) {
        Objects.requireNonNull(country, "country");
        if (!CountryCode.NL.equals(country)) {
            return new PostalCode(Sensitive.of(FreeText.parse(field, raw, MAX_POSTAL_CODE)));
        }
        // Trim without NFC: normalising first would turn the Kelvin sign into an ASCII "K".
        String code = FreeText.trim(raw);
        if (code.isEmpty()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        // Upper-casing turns "ß" into "SS" and "ı" into "I", so only ASCII input may reach the pattern.
        if (!ASCII.matcher(code).matches() || !DUTCH.matcher(code.toUpperCase(Locale.ROOT)).matches()) {
            throw ValidationException.of(field, "invalid_format", "must look like 1234 AB");
        }
        String compact = code.toUpperCase(Locale.ROOT).replace(" ", "");
        return new PostalCode(Sensitive.of(compact.substring(0, 4) + " " + compact.substring(4)));
    }
}
