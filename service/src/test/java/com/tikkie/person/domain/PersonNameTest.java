package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.text.Normalizer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PersonNameTest {

    private static final String FIELD = "firstName";

    @ParameterizedTest
    @ValueSource(strings = {
            "Anna", "de Vries", "O'Brien", "O’Brien", "Müller",
            "van der Berg", "Jean-Luc", "Zoë", "李"})
    void acceptsRealWorldNames(String raw) {
        assertThat(PersonName.parse(FIELD, raw).value().reveal()).isEqualTo(raw);
    }

    @Test
    void decomposedUnicodeEqualsComposed() {
        String composed = "Müller";
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo(composed);
        assertThat(PersonName.parse(FIELD, decomposed)).isEqualTo(PersonName.parse(FIELD, composed));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(PersonName.parse(FIELD, "  Anna ").value().reveal()).isEqualTo("Anna");
    }

    @Test
    void stripsSurroundingNoBreakSpaces() {
        assertThat(PersonName.parse(FIELD, "\u00A0Anna\u00A0").value().reveal()).isEqualTo("Anna");
    }

    @Test
    void acceptsExactlyMaximumLength() {
        assertThat(PersonName.parse(FIELD, "a".repeat(100)).value().reveal()).hasSize(100);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidNames(String raw, String expectedCode) {
        assertThatThrownBy(() -> PersonName.parse(FIELD, raw))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.errors())
                                .extracting(FieldError::field, FieldError::code)
                                .containsExactly(tuple(FIELD, expectedCode)));
    }

    static Stream<Arguments> invalidNames() {
        return Stream.of(
                Arguments.of(null, "required"),
                Arguments.of("", "required"),
                Arguments.of("   ", "required"),
                Arguments.of("\u00A0", "required"),
                Arguments.of("a".repeat(101), "too_long"),
                Arguments.of("Anna1", "invalid_characters"),
                Arguments.of("<script>", "invalid_characters"),
                Arguments.of("---", "invalid_characters"));
    }

    @Test
    void usesGivenFieldPathInErrors() {
        assertThatThrownBy(() -> PersonName.parse("lastName", null))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.errors().getFirst().field()).isEqualTo("lastName"));
    }

    @Test
    void constructorRejectsDisallowedCharacters() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PersonName(Sensitive.of("Anna1")));
    }

    @Test
    void masksValueInToString() {
        assertThat(PersonName.parse(FIELD, "Anna").toString())
                .isEqualTo("PersonName[***]");
    }
}
