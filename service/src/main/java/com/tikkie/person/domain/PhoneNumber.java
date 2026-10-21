package com.tikkie.person.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber;

public record PhoneNumber(Sensitive<String> e164) {

    private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

    // The default region only applies without a "+" prefix; an international number keeps its own country.
    public static PhoneNumber parse(String field, String raw, CountryCode defaultRegion) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        Phonenumber.PhoneNumber number = parseOrNull(raw, defaultRegion);
        if (number == null || !PHONE_NUMBERS.isValidNumber(number)) {
            throw ValidationException.of(field, "invalid_phone_number", "must be a valid phone number");
        }
        return new PhoneNumber(Sensitive.of(PHONE_NUMBERS.format(number, PhoneNumberFormat.E164)));
    }

    private static Phonenumber.PhoneNumber parseOrNull(String raw, CountryCode defaultRegion) {
        try {
            return PHONE_NUMBERS.parse(raw, defaultRegion.value());
        } catch (NumberParseException e) {
            return null;
        }
    }
}
