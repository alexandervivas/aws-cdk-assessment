package com.tikkie.person.domain;

import java.text.Normalizer;
import java.util.Optional;

public record Address(
        Sensitive<String> street,
        Sensitive<String> houseNumber,
        Sensitive<String> houseNumberAddition,
        PostalCode postalCode,
        Sensitive<String> city,
        CountryCode countryCode) {

    public Optional<Sensitive<String>> houseNumberAdditionValue() {
        return Optional.ofNullable(houseNumberAddition);
    }

    static Sensitive<String> parseText(String field, String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw ValidationException.of(field, "too_long", "must be at most " + maxLength + " characters");
        }
        return Sensitive.of(text);
    }

    static Sensitive<String> parseOptionalText(String field, String raw, int maxLength) {
        return raw == null || raw.isBlank() ? null : parseText(field, raw, maxLength);
    }

    @Override
    public String toString() {
        return "Address[***]";
    }
}
