package com.tikkie.person.domain;

import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.util.Objects;
import java.util.Optional;

public record Address(
        Sensitive<String> street,
        Sensitive<String> houseNumber,
        Optional<Sensitive<String>> houseNumberAddition,
        PostalCode postalCode,
        Sensitive<String> city,
        CountryCode countryCode) {

    static final int MAX_STREET = 200;
    static final int MAX_HOUSE_NUMBER = 10;
    static final int MAX_ADDITION = 10;
    static final int MAX_CITY = 100;

    public Address {
        Objects.requireNonNull(street, "street");
        Objects.requireNonNull(houseNumber, "houseNumber");
        Objects.requireNonNull(houseNumberAddition, "houseNumberAddition");
        Objects.requireNonNull(postalCode, "postalCode");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(countryCode, "countryCode");
    }

    // A null country means the caller has already reported it; the postal code depends on it, so it is skipped.
    static Address parse(UnvalidatedAddress raw, CountryCode country) {
        FieldErrors errors = new FieldErrors();
        Sensitive<String> street = errors.capture(() -> text("address.street", raw.street(), MAX_STREET));
        Sensitive<String> houseNumber =
                errors.capture(() -> text("address.houseNumber", raw.houseNumber(), MAX_HOUSE_NUMBER));
        Optional<Sensitive<String>> addition = errors.capture(() -> optionalText(
                "address.houseNumberAddition", raw.houseNumberAddition(), MAX_ADDITION));
        Sensitive<String> city = errors.capture(() -> text("address.city", raw.city(), MAX_CITY));
        PostalCode postalCode = country == null
                ? null
                : errors.capture(() -> PostalCode.parse("address.postalCode", raw.postalCode(), country));
        errors.throwIfAny();
        return country == null ? null : new Address(street, houseNumber, addition, postalCode, city, country);
    }

    private static Sensitive<String> text(String field, String raw, int maxLength) {
        return Sensitive.of(FreeText.parse(field, raw, maxLength));
    }

    private static Optional<Sensitive<String>> optionalText(String field, String raw, int maxLength) {
        return FreeText.strip(raw).isEmpty() ? Optional.empty() : Optional.of(text(field, raw, maxLength));
    }

    @Override
    public String toString() {
        return "Address[***]";
    }
}
