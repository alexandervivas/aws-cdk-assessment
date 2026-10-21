package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RecordInvariantsTest {

    private static final Sensitive<String> TEXT = Sensitive.of("x");
    private static final PostalCode POSTAL_CODE = PostalCode.parse("address.postalCode", "1012 LG", CountryCode.NL);

    @ParameterizedTest
    @MethodSource("constructorsWithANullComponent")
    void recordsCannotBeBuiltWithNullComponents(ThrowingCallable construction) {
        assertThatNullPointerException().isThrownBy(construction);
    }

    static Stream<ThrowingCallable> constructorsWithANullComponent() {
        return Stream.of(
                () -> new FieldError("firstName", "required", null),
                () -> new CountryCode(null),
                () -> new PhoneNumber(null),
                () -> new PostalCode(null),
                () -> new Address(TEXT, TEXT, null, POSTAL_CODE, TEXT, CountryCode.NL),
                () -> new Address(TEXT, TEXT, Optional.empty(), POSTAL_CODE, null, CountryCode.NL),
                () -> new PersonDetails(PersonName.parse("firstName", "Anna"), null, null, null));
    }
}
