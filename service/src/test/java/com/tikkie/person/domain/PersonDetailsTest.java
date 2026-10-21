package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.time.Instant;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PersonDetailsTest {

    private static final UnvalidatedAddress ADDRESS =
            new UnvalidatedAddress(" Damrak ", "1", "III", "1012lg", "Amsterdam", "NL");
    private static final UnvalidatedPerson VALID =
            new UnvalidatedPerson("Anna", "de Vries", "06 12345678", ADDRESS);

    @Test
    void parsesAndNormalisesEveryField() {
        PersonDetails details = PersonDetails.parse(VALID);
        Address address = details.address();

        assertThat(details.firstName().value().reveal()).isEqualTo("Anna");
        assertThat(details.lastName().value().reveal()).isEqualTo("de Vries");
        assertThat(details.phoneNumber().e164().reveal()).isEqualTo("+31612345678");
        assertThat(address.street().reveal()).isEqualTo("Damrak");
        assertThat(address.houseNumber().reveal()).isEqualTo("1");
        assertThat(address.houseNumberAddition()).contains(Sensitive.of("III"));
        assertThat(address.postalCode().value().reveal()).isEqualTo("1012 LG");
        assertThat(address.city().reveal()).isEqualTo("Amsterdam");
        assertThat(address.countryCode()).isEqualTo(CountryCode.NL);
    }

    @Test
    void houseNumberAdditionIsOptional() {
        UnvalidatedAddress noAddition = new UnvalidatedAddress("Damrak", "1", " ", "1012 LG", "Amsterdam", "NL");

        PersonDetails details =
                PersonDetails.parse(new UnvalidatedPerson("Anna", "de Vries", "06 12345678", noAddition));

        assertThat(details.address().houseNumberAddition()).isEmpty();
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() {
        UnvalidatedAddress badAddress =
                new UnvalidatedAddress("x".repeat(201), "1".repeat(11), "A".repeat(11), "1012 LG", "", "NL");

        assertRejectedWith(new UnvalidatedPerson("Anna1", "de Vries", "06 12345678", badAddress),
                tuple("firstName", "invalid_characters"),
                tuple("address.street", "too_long"),
                tuple("address.houseNumber", "too_long"),
                tuple("address.houseNumberAddition", "too_long"),
                tuple("address.city", "required"));
    }

    @Test
    void reportsCountryDependentErrorsWhenCountryIsValid() {
        UnvalidatedAddress badPostalCode = new UnvalidatedAddress("Damrak", "1", null, "0123 AB", "Amsterdam", "NL");

        assertRejectedWith(new UnvalidatedPerson("Anna", "Example42", "12", badPostalCode),
                tuple("lastName", "invalid_characters"),
                tuple("phoneNumber", "invalid_phone_number"),
                tuple("address.postalCode", "invalid_format"));
    }

    @Test
    void missingAddressReportsEveryRequiredAddressFieldExceptTheCountryDependentPostalCode() {
        assertRejectedWith(new UnvalidatedPerson("Anna", "de Vries", "06 12345678", null),
                tuple("address.street", "required"),
                tuple("address.houseNumber", "required"),
                tuple("address.city", "required"),
                tuple("address.countryCode", "required"));
    }

    @Test
    void nullInputIsAProgrammingError() {
        assertThatNullPointerException().isThrownBy(() -> PersonDetails.parse(null));
    }

    @ParameterizedTest
    @CsvSource({
            "XX, '   ',                 unknown_country",
            "XX, 123456789012345678901, unknown_country",
            "nl, '',                    invalid_format",
            ",   123456789012345678901, required"})
    void invalidCountrySkipsCountryDependentFields(String country, String postalCode, String code) {
        UnvalidatedAddress address = new UnvalidatedAddress("Damrak", "1", null, postalCode, "Amsterdam", country);

        assertRejectedWith(new UnvalidatedPerson("Anna1", "de Vries", "12", address),
                tuple("firstName", "invalid_characters"),
                tuple("address.countryCode", code));
    }

    @Test
    void toStringsRevealNoPersonalData() {
        PersonDetails details = PersonDetails.parse(VALID);
        PersonId id = PersonId.parse("personId", "0f8fad5b-d9cb-469f-a165-70867728950e");
        Person person = new Person(id, details, Instant.parse("2026-10-21T10:15:30Z"));

        assertThat(person.toString())
                .isEqualTo("Person[id=" + person.id() + ", createdAt=2026-10-21T10:15:30Z]");
        for (String text : new String[] {
                person.toString(), details.toString(), details.address().toString(), VALID.toString(),
                details.phoneNumber().toString(), details.address().postalCode().toString()}) {
            assertThat(text).doesNotContain(
                    "Anna", "Vries", "12345678", "+31612345678", "Damrak", "Amsterdam", "1012");
        }
    }

    @Test
    void personIdRoundTripsThroughItsString() {
        PersonId id = PersonId.random();

        assertThat(PersonId.parse("personId", id.toString())).isEqualTo(id);
        assertThat(PersonId.random()).isNotEqualTo(id);
    }

    private static void assertRejectedWith(UnvalidatedPerson input, Tuple... expected) {
        assertThatThrownBy(() -> PersonDetails.parse(input))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactlyInAnyOrder(expected));
    }
}
