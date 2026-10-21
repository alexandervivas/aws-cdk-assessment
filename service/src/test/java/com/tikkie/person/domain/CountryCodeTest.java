package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CountryCodeTest {

    private static final String FIELD = "address.countryCode";

    @ParameterizedTest
    @ValueSource(strings = {"NL", "DE"})
    void acceptsIsoAlpha2Codes(String raw) {
        assertThat(CountryCode.parse(FIELD, raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @MethodSource("invalidCodes")
    void rejectsInvalidCodes(String raw, String expectedCode) {
        assertThatThrownBy(() -> CountryCode.parse(FIELD, raw))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.errors()).hasSize(1);
                    assertThat(e.errors().getFirst().field()).isEqualTo(FIELD);
                    assertThat(e.errors().getFirst().code()).isEqualTo(expectedCode);
                });
    }

    static Stream<Arguments> invalidCodes() {
        return Stream.of(
                Arguments.of(null, "required"),
                Arguments.of(" ", "required"),
                Arguments.of("nl", "invalid_format"),
                Arguments.of("NLD", "invalid_format"),
                Arguments.of("XX", "unknown_country"),
                Arguments.of("ZZ", "unknown_country"));
    }
}
