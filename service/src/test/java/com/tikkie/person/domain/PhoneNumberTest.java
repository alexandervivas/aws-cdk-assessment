package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {

    private static final String FIELD = "phoneNumber";

    @ParameterizedTest
    @CsvSource({
            "'+31 6 12345678', NL, +31612345678",
            "'06 12345678',    NL, +31612345678",
            "'030 123456',     DE, +4930123456"})
    void storesE164(String raw, String region, String expected) {
        CountryCode country = CountryCode.parse("address.countryCode", region);

        assertThat(PhoneNumber.parse(FIELD, raw, country).e164().reveal()).isEqualTo(expected);
    }

    @Test
    void plusPrefixOverridesDefaultRegion() {
        assertThat(PhoneNumber.parse(FIELD, "+49 30 123456", CountryCode.NL).e164().reveal())
                .isEqualTo("+4930123456");
    }

    @ParameterizedTest
    @CsvSource({
            "12,               invalid_phone_number",
            "'+31 6 1234',     invalid_phone_number",
            "call me maybe,    invalid_phone_number",
            "'+1 800 FLOWERS', invalid_phone_number",
            "'+31 6 12345678 ext. 12', invalid_phone_number",
            "'+31 6 12345678#12',      invalid_phone_number",
            "'',               required",
            "'  ',             required",
            ",                 required",
            "'\u00A0',         required"})
    void rejectsInvalidNumbers(String raw, String code) {
        assertThatThrownBy(() -> PhoneNumber.parse(FIELD, raw, CountryCode.NL))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple(FIELD, code)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0612345678", "+0612345678", "+31 612345678", "+1234567890123456"})
    void constructorRejectsValuesThatAreNotE164WithoutEchoingThem(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PhoneNumber(Sensitive.of(value)))
                .withMessageNotContaining(value);
    }
}
