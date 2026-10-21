package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PersonIdTest {

    private static final String FIELD = "personId";

    @Test
    void acceptsUpperCaseCanonicalForm() {
        PersonId id = PersonId.random();

        assertThat(PersonId.parse(FIELD, id.toString().toUpperCase())).isEqualTo(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "not-a-uuid", "1-1-1-1-1", "0f8fad5b-d9cb-469f-a165-70867728950", " 0f8fad5b-d9cb-469f-a165-70867728950e"})
    void rejectsMalformedOrNonCanonicalInput(String raw) {
        assertThatThrownBy(() -> PersonId.parse(FIELD, raw))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(tuple(FIELD, "invalid_format")));
    }
}
