package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Named.named;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RecordInvariantsTest {

    private static final Sensitive<String> TEXT = Sensitive.of("x");
    private static final Optional<Sensitive<String>> NO_ADDITION = Optional.empty();
    private static final PostalCode POSTAL = new PostalCode(Sensitive.of("1012 LG"));
    private static final CountryCode NL = CountryCode.NL;
    private static final Address ADDRESS = new Address(TEXT, TEXT, NO_ADDITION, POSTAL, TEXT, NL);
    private static final PersonName NAME = new PersonName(Sensitive.of("Anna"));
    private static final PhoneNumber PHONE = new PhoneNumber(Sensitive.of("+31612345678"));
    private static final PersonDetails DETAILS = new PersonDetails(NAME, NAME, PHONE, ADDRESS);
    private static final PersonId ID = new PersonId(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"));
    private static final Instant CREATED_AT = Instant.parse("2026-10-21T10:15:30Z");

    @ParameterizedTest
    @MethodSource("constructorsWithOneNullComponent")
    void recordsCannotBeBuiltWithANullComponent(ThrowingCallable construction) {
        assertThatNullPointerException().isThrownBy(construction);
    }

    static Stream<Named<ThrowingCallable>> constructorsWithOneNullComponent() {
        return Stream.of(
                named("PhoneNumber.e164", () -> new PhoneNumber(null)),
                named("PostalCode.value", () -> new PostalCode(null)),
                named("Address.street", () -> new Address(null, TEXT, NO_ADDITION, POSTAL, TEXT, NL)),
                named("Address.houseNumber", () -> new Address(TEXT, null, NO_ADDITION, POSTAL, TEXT, NL)),
                named("Address.houseNumberAddition", () -> new Address(TEXT, TEXT, null, POSTAL, TEXT, NL)),
                named("Address.postalCode", () -> new Address(TEXT, TEXT, NO_ADDITION, null, TEXT, NL)),
                named("Address.city", () -> new Address(TEXT, TEXT, NO_ADDITION, POSTAL, null, NL)),
                named("Address.countryCode", () -> new Address(TEXT, TEXT, NO_ADDITION, POSTAL, TEXT, null)),
                named("PersonDetails.firstName", () -> new PersonDetails(null, NAME, PHONE, ADDRESS)),
                named("PersonDetails.lastName", () -> new PersonDetails(NAME, null, PHONE, ADDRESS)),
                named("PersonDetails.phoneNumber", () -> new PersonDetails(NAME, NAME, null, ADDRESS)),
                named("PersonDetails.address", () -> new PersonDetails(NAME, NAME, PHONE, null)),
                named("FieldError.field", () -> new FieldError(null, "required", "must not be blank")),
                named("FieldError.code", () -> new FieldError("firstName", null, "must not be blank")),
                named("FieldError.message", () -> new FieldError("firstName", "required", null)),
                named("Person.id", () -> new Person(null, DETAILS, CREATED_AT)),
                named("Person.details", () -> new Person(ID, null, CREATED_AT)),
                named("Person.createdAt", () -> new Person(ID, DETAILS, null)),
                named("PersonName.value", () -> new PersonName(null)),
                named("PersonId.value", () -> new PersonId(null)),
                named("CountryCode.value", () -> new CountryCode(null)));
    }
}
