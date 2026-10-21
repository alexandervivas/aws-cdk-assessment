package com.tikkie.person.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record CountryCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}");
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    public static final CountryCode NL = new CountryCode("NL");

    public CountryCode {
        Objects.requireNonNull(value, "value");
        if (!isIsoCode(value)) {
            throw new IllegalArgumentException("not an ISO 3166-1 alpha-2 country code");
        }
    }

    public static CountryCode parse(String field, String raw) {
        if (FreeText.strip(raw).isEmpty()) {
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

    private static boolean isIsoCode(String value) {
        return FORMAT.matcher(value).matches() && ISO_COUNTRIES.contains(value);
    }
}
