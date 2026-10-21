package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "Damrak\uD800",
            "\uDC00Damrak",
            "Dam\u200Brak",
            "Damrak\u200B",
            "Dam\u200Drak",
            "\uFEFFDamrak",
            "Dam\u00ADrak",
            "Dam\u202Erak",
            "Dam\u2028rak",
            "Dam\u2029rak"})
    void rejectsSurrogatesFormatAndLineSeparatorCharactersInFreeText(String street) {
        UnvalidatedAddress invisible =
                new UnvalidatedAddress(street, "1", null, "SW1A 1AA", "London", "GB");

        assertThatThrownBy(() -> Address.parse(invisible, CountryCode.parse("address.countryCode", "GB")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple("address.street", "invalid_characters")));
    }

    @Test
    void rejectsFormatCharactersInForeignPostalCode() {
        UnvalidatedAddress invisible =
                new UnvalidatedAddress("Main Street", "1", null, "SW1A\u200B1AA", "London", "GB");

        assertThatThrownBy(() -> Address.parse(invisible, CountryCode.parse("address.countryCode", "GB")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple("address.postalCode", "invalid_characters")));
    }

    @Test
    void stripsTrailingLineSeparatorBeforeCheckingCharacters() {
        Address address = Address.parse(new UnvalidatedAddress(
                "Damrak\u2028", "1", null, "1012 LG", "Amsterdam\u2029", "NL"), CountryCode.NL);

        assertThat(address.street().reveal()).isEqualTo("Damrak");
        assertThat(address.city().reveal()).isEqualTo("Amsterdam");
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
                "1".repeat(Address.MAX_HOUSE_NUMBER), "A".repeat(Address.MAX_ADDITION),
                "1".repeat(PostalCode.MAX_POSTAL_CODE), "y".repeat(Address.MAX_CITY), "DE"),
                CountryCode.parse("address.countryCode", "DE"));

        assertThat(address.street().reveal()).hasSize(Address.MAX_STREET);
        assertThat(address.houseNumber().reveal()).hasSize(Address.MAX_HOUSE_NUMBER);
        assertThat(address.houseNumberAddition().orElseThrow().reveal()).hasSize(Address.MAX_ADDITION);
        assertThat(address.postalCode().value().reveal()).hasSize(PostalCode.MAX_POSTAL_CODE);
        assertThat(address.city().reveal()).hasSize(Address.MAX_CITY);
    }

    @ParameterizedTest
    @MethodSource("oneCharacterOverTheLimit")
    void rejectsFreeTextOneCharacterOverItsLimit(String field, UnvalidatedAddress raw) {
        assertThatThrownBy(() -> Address.parse(raw, CountryCode.NL))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple(field, "too_long")));
    }

    static Stream<Arguments> oneCharacterOverTheLimit() {
        return Stream.of(
                Arguments.of("address.street", address("x".repeat(201), "1", "A", "Amsterdam")),
                Arguments.of("address.houseNumber", address("Damrak", "1".repeat(11), "A", "Amsterdam")),
                Arguments.of("address.houseNumberAddition", address("Damrak", "1", "A".repeat(11), "Amsterdam")),
                Arguments.of("address.city", address("Damrak", "1", "A", "y".repeat(101))));
    }

    private static UnvalidatedAddress address(String street, String houseNumber, String addition, String city) {
        return new UnvalidatedAddress(street, houseNumber, addition, "1012 LG", city, "NL");
    }
}
