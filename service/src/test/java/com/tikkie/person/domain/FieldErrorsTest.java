package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldErrorsTest {

    @Test
    void returnsParsedValueWhenValid() {
        FieldErrors errors = new FieldErrors();

        CountryCode country = errors.capture(() -> CountryCode.parse("address.countryCode", "NL"));

        assertThat(country).isEqualTo(new CountryCode("NL"));
        assertThatCode(errors::throwIfAny).doesNotThrowAnyException();
    }

    @Test
    void collectsEveryErrorIntoOneException() {
        FieldErrors errors = new FieldErrors();

        PersonName name = errors.capture(() -> PersonName.parse("firstName", ""));
        CountryCode country = errors.capture(() -> CountryCode.parse("address.countryCode", "XX"));

        assertThat(name).isNull();
        assertThat(country).isNull();
        assertThatThrownBy(errors::throwIfAny)
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).errors())
                .asInstanceOf(list(FieldError.class))
                .extracting(FieldError::field, FieldError::code)
                .containsExactly(
                        tuple("firstName", "required"),
                        tuple("address.countryCode", "unknown_country"));
    }

    @Test
    void exceptionMessageListsFieldsAndCodesButNotRejectedValue() {
        ValidationException exception = new ValidationException(List.of(
                new FieldError("firstName", "invalid_characters", "may contain only letters")));

        assertThat(exception.getMessage())
                .contains("firstName", "invalid_characters")
                .doesNotContain("may contain");
    }

    @Test
    void parseFailureMessageDoesNotContainRejectedValue() {
        assertThatThrownBy(() -> PersonName.parse("lastName", "Example42"))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> {
                    ValidationException exception = (ValidationException) e;
                    assertThat(exception.getMessage()).doesNotContain("Example42");
                    assertThat(exception.errors()).allSatisfy(error ->
                            assertThat(error.message()).doesNotContain("Example42"));
                });
    }

    @Test
    void errorListIsImmutable() {
        ValidationException exception = new ValidationException(new ArrayList<>(List.of(
                new FieldError("firstName", "required", "must not be blank"))));

        assertThatThrownBy(() -> exception.errors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
