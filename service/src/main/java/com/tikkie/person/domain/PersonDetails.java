package com.tikkie.person.domain;

import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.util.Objects;

public record PersonDetails(PersonName firstName, PersonName lastName, PhoneNumber phoneNumber, Address address) {

    public static PersonDetails parse(UnvalidatedPerson input) {
        UnvalidatedAddress rawAddress = Objects.requireNonNullElse(input.address(), UnvalidatedAddress.EMPTY);
        FieldErrors errors = new FieldErrors();

        CountryCode country = errors.capture(() -> CountryCode.parse("address.countryCode", rawAddress.countryCode()));
        PersonName firstName = errors.capture(() -> PersonName.parse("firstName", input.firstName()));
        PersonName lastName = errors.capture(() -> PersonName.parse("lastName", input.lastName()));
        Sensitive<String> street = errors.capture(() -> Address.parseText("address.street", rawAddress.street(), 200));
        Sensitive<String> houseNumber = errors.capture(() -> Address.parseText(
                "address.houseNumber", rawAddress.houseNumber(), 10));
        Sensitive<String> addition = errors.capture(() -> Address.parseOptionalText(
                "address.houseNumberAddition", rawAddress.houseNumberAddition(), 10));
        Sensitive<String> city = errors.capture(() -> Address.parseText("address.city", rawAddress.city(), 100));
        // Phone and postal code rules depend on the country, so an invalid country is reported alone.
        PhoneNumber phone = country == null ? null
                : errors.capture(() -> PhoneNumber.parse("phoneNumber", input.phoneNumber(), country));
        PostalCode postalCode = country == null ? null
                : errors.capture(() -> PostalCode.parse("address.postalCode", rawAddress.postalCode(), country));
        errors.throwIfAny();

        Address address = new Address(street, houseNumber, addition, postalCode, city, country);
        return new PersonDetails(firstName, lastName, phone, address);
    }

    @Override
    public String toString() {
        return "PersonDetails[***]";
    }
}
