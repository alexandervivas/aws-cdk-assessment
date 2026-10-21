package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.time.Instant;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

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
        assertThat(address.houseNumberAdditionValue()).contains(Sensitive.of("III"));
        assertThat(address.postalCode().value().reveal()).isEqualTo("1012 LG");
        assertThat(address.city().reveal()).isEqualTo("Amsterdam");
        assertThat(address.countryCode()).isEqualTo(new CountryCode("NL"));
    }

    @Test
    void houseNumberAdditionIsOptional() {
        UnvalidatedAddress noAddition = new UnvalidatedAddress("Damrak", "1", " ", "1012 LG", "Amsterdam", "NL");

        PersonDetails details =
                PersonDetails.parse(new UnvalidatedPerson("Anna", "de Vries", "06 12345678", noAddition));

        assertThat(details.address().houseNumberAdditionValue()).isEmpty();
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
    void invalidCountrySkipsCountryDependentFields() {
        UnvalidatedAddress unknownCountry = new UnvalidatedAddress("Damrak", "1", null, "0000", "Amsterdam", "XX");

        assertRejectedWith(new UnvalidatedPerson("Anna", "de Vries", "12", unknownCountry),
                tuple("address.countryCode", "unknown_country"));
    }

    @Test
    void toStringsRevealNoPersonalData() {
        PersonDetails details = PersonDetails.parse(VALID);
        Person person = new Person(PersonId.random(), details, Instant.parse("2026-10-21T10:15:30Z"));

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

        assertThat(PersonId.parse(id.toString())).isEqualTo(id);
        assertThat(PersonId.random()).isNotEqualTo(id);
    }

    private static void assertRejectedWith(UnvalidatedPerson input, Tuple... expected) {
        assertThatThrownBy(() -> PersonDetails.parse(input))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactlyInAnyOrder(expected));
    }
}
