package com.tikkie.person.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record CountryCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}");
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    public static CountryCode parse(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        if (!FORMAT.matcher(raw).matches()) {
            throw ValidationException.of(field, "invalid_format", "must be two upper-case letters");
        }
        if (!ISO_COUNTRIES.contains(raw)) {
            throw ValidationException.of(field, "unknown_country", "must be an ISO 3166-1 alpha-2 country code");
        }
        return new CountryCode(raw);
    }
}
