package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import org.junit.jupiter.api.Test;

class AddressTest {

    @Test
    void rejectsControlCharactersInFreeText() {
        UnvalidatedAddress withControls =
                new UnvalidatedAddress("Dam\nrak", "1\u00002", "I\tI", "1012 LG", "Amster\u0007dam", "NL");

        assertThatThrownBy(() -> Address.parse(withControls, CountryCode.NL))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactlyInAnyOrder(
                                tuple("address.street", "invalid_characters"),
                                tuple("address.houseNumber", "invalid_characters"),
                                tuple("address.houseNumberAddition", "invalid_characters"),
                                tuple("address.city", "invalid_characters")));
    }

    @Test
    void stripsEdgeWhitespaceIncludingNewlinesAndNoBreakSpaces() {
        Address address = Address.parse(new UnvalidatedAddress(
                "Damrak\n", "1", "\u00A0", "1012 LG", "Amsterdam\u00A0", "NL"), CountryCode.NL);

        assertThat(address.street().reveal()).isEqualTo("Damrak");
        assertThat(address.houseNumberAddition()).isEmpty();
        assertThat(address.city().reveal()).isEqualTo("Amsterdam");
    }

    @Test
    void acceptsEveryFieldAtItsNamedLimit() {
        Address address = Address.parse(new UnvalidatedAddress("x".repeat(Address.MAX_STREET),
                "1".repeat(Address.MAX_HOUSE_NUMBER), "A".repeat(Address.MAX_ADDITION), "10115",
                "y".repeat(Address.MAX_CITY), "DE"), CountryCode.parse("address.countryCode", "DE"));

        assertThat(address.city().reveal()).hasSize(Address.MAX_CITY);
    }
}
