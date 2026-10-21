package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;
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
            "10115,       DE, 10115",
            "'sw1a 1aa',  GB, 'sw1a 1aa'",
            "12345678901234567890, DE, 12345678901234567890"})
    void acceptsAndNormalises(String raw, String country, String expected) {
        assertThat(PostalCode.parse(FIELD, raw, country(country)).value().reveal()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "'0123 AB',               NL, invalid_format",
            "'1012 L',                NL, invalid_format",
            "12345,                   NL, invalid_format",
            "'1012  LG',              NL, invalid_format",
            "'1012 ß',                NL, invalid_format",
            "'1012 ıa',               NL, invalid_format",
            "'١٠١٢ LG',               NL, invalid_format",
            "'1012 \u212AA',           NL, invalid_format",
            "'101\t15',               DE, invalid_characters",
            "'   ',                   NL, required",
            ",                        DE, required",
            "123456789012345678901,   DE, too_long"})
    void rejectsInvalidPostalCodes(String raw, String country, String code) {
        assertThatThrownBy(() -> PostalCode.parse(FIELD, raw, country(country)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple(FIELD, code)));
    }

    private static CountryCode country(String raw) {
        return CountryCode.parse("address.countryCode", raw);
    }

    @Test
    void countryIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> PostalCode.parse(FIELD, "1012 LG", null));
    }
}
