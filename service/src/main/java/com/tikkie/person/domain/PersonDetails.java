package com.tikkie.person.domain;

import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.util.Objects;

public record PersonDetails(PersonName firstName, PersonName lastName, PhoneNumber phoneNumber, Address address) {

    public PersonDetails {
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(phoneNumber, "phoneNumber");
        Objects.requireNonNull(address, "address");
    }

    public static PersonDetails parse(UnvalidatedPerson input) {
        Objects.requireNonNull(input, "input");
        UnvalidatedAddress rawAddress = Objects.requireNonNullElse(input.address(), UnvalidatedAddress.EMPTY);
        FieldErrors errors = new FieldErrors();

        PersonName firstName = errors.capture(() -> PersonName.parse("firstName", input.firstName()));
        PersonName lastName = errors.capture(() -> PersonName.parse("lastName", input.lastName()));
        CountryCode country =
                errors.capture(() -> CountryCode.parse("address.countryCode", rawAddress.countryCode()));
        Address address = errors.capture(() -> Address.parse(rawAddress, country));
        // The phone's default region is the address country; without one, the country error stands alone.
        PhoneNumber phone = country == null
                ? null
                : errors.capture(() -> PhoneNumber.parse("phoneNumber", input.phoneNumber(), country));
        errors.throwIfAny();

        return new PersonDetails(firstName, lastName, phone, address);
    }

    @Override
    public String toString() {
        return "PersonDetails[***]";
    }
}
