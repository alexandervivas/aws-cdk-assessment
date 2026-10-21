package com.tikkie.person.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Objects;
import java.util.regex.Pattern;

public record PhoneNumber(Sensitive<String> e164) {

    private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();
    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{1,14}");

    public PhoneNumber {
        Objects.requireNonNull(e164, "e164");
        if (!E164.matcher(e164.reveal()).matches()) {
            throw new IllegalArgumentException("not an E.164 phone number");
        }
    }

    // The default region only applies without a "+" prefix; an international number keeps its own country.
    // libphonenumber would map letters to keypad digits and drop extensions; E.164 can hold neither, so both fail.
    public static PhoneNumber parse(String field, String raw, CountryCode defaultRegion) {
        if (FreeText.strip(raw).isEmpty()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        Phonenumber.PhoneNumber number = LETTER.matcher(raw).find() ? null : parseOrNull(raw, defaultRegion);
        if (number == null || number.hasExtension() || !PHONE_NUMBERS.isValidNumber(number)) {
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
