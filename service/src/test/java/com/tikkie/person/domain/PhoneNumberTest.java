package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PhoneNumberTest {

    private static final String FIELD = "phoneNumber";
    private static final CountryCode NL = new CountryCode("NL");

    @ParameterizedTest
    @CsvSource({
            "'+31 6 12345678', NL, +31612345678",
            "'06 12345678',    NL, +31612345678",
            "'+49 30 123456',  NL, +4930123456"})
    void storesE164(String raw, String region, String expected) {
        assertThat(PhoneNumber.parse(FIELD, raw, new CountryCode(region)).e164().reveal()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "12,               invalid_phone_number",
            "'+31 6 1234',     invalid_phone_number",
            "call me maybe,    invalid_phone_number",
            "'',               required",
            "'  ',             required",
            ",                 required"})
    void rejectsInvalidNumbers(String raw, String code) {
        assertThatThrownBy(() -> PhoneNumber.parse(FIELD, raw, NL))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple(FIELD, code)));
    }
}
