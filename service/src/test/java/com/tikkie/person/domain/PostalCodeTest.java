package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PostalCodeTest {

    private static final String FIELD = "address.postalCode";

    @ParameterizedTest
    @CsvSource({
            "'1012 LG',   NL, '1012 LG'",
            "1012LG,      NL, '1012 LG'",
            "'1012 lg',   NL, '1012 LG'",
            "' 1012 LG ', NL, '1012 LG'",
            "10115,       DE, 10115"})
    void acceptsAndNormalises(String raw, String country, String expected) {
        assertThat(PostalCode.parse(FIELD, raw, new CountryCode(country)).value().reveal()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "'0123 AB',               NL, invalid_format",
            "'1012 L',                NL, invalid_format",
            "12345,                   NL, invalid_format",
            "'   ',                   NL, required",
            ",                        DE, required",
            "123456789012345678901,   DE, too_long"})
    void rejectsInvalidPostalCodes(String raw, String country, String code) {
        assertThatThrownBy(() -> PostalCode.parse(FIELD, raw, new CountryCode(country)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple(FIELD, code)));
    }
}
