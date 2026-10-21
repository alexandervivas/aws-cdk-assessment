package com.tikkie.person.application;

import static com.tikkie.person.application.PersonFixtures.PERSON;
import static com.tikkie.person.application.PersonFixtures.personWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tikkie.person.domain.Address;
import com.tikkie.person.domain.PersonDetails;
import com.tikkie.person.domain.Sensitive;
import com.tikkie.person.domain.UnvalidatedPerson;
import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CanonicalRequestTest {

    @Test
    void joinsTheVersionTagAndNormalisedValuesInAFixedOrder() {
        assertThat(canonicalOf(PERSON).value()).isEqualTo(
                "v1\u001FAnna\u001Fde Vries\u001F+31612345678\u001FDamrak\u001F1\u001F1\u001FIII"
                        + "\u001F1012 LG\u001FAmsterdam\u001FNL");
    }

    @Test
    void encodesAsUtf8() {
        CanonicalRequest request = new CanonicalRequest("Zoë");

        assertThat(request.utf8()).containsExactly('Z', 'o', 0xC3, 0xAB);
    }

    @Test
    void rejectsTextThatIsNotValidUtf16InsteadOfAliasingIt() {
        PersonDetails details = PersonDetails.parse(PERSON);
        Address a = details.address();
        Address loneSurrogate = new Address(Sensitive.of("Dam\uD800rak"), a.houseNumber(), a.houseNumberAddition(),
                a.postalCode(), a.city(), a.countryCode());
        PersonDetails malformed = new PersonDetails(details.firstName(), details.lastName(), details.phoneNumber(),
                loneSurrogate);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CanonicalRequest.of(malformed))
                .withMessageNotContaining("Dam");
    }

    @Test
    void toStringRevealsNoValue() {
        assertThat(canonicalOf(PERSON).toString())
                .doesNotContain("Anna", "Vries", "612345678", "Damrak", "1012", "Amsterdam");
    }

    @Test
    void isEqualForFormattingVariantsOfTheSamePerson() {
        UnvalidatedPerson variant = new UnvalidatedPerson(" Anna", "de Vries ", "06 12345678",
                new UnvalidatedAddress("  Damrak ", " 1", "III ", "1012lg", " Amsterdam", "NL"));

        assertThat(canonicalOf(variant)).isEqualTo(canonicalOf(PERSON));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"firstName, Anne", "lastName, de Vrie", "phoneNumber, +31 6 12345679", "street, Rokin",
            "houseNumber, 2", "houseNumberAddition, II", "postalCode, 1012 LH", "city, Haarlem", "countryCode, BE"})
    void differsWhenOneFieldDiffers(String field, String value) {
        assertThat(canonicalOf(personWith(field, value))).isNotEqualTo(canonicalOf(PERSON));
    }

    @Test
    void fieldBoundariesCannotShiftBetweenFields() {
        UnvalidatedPerson shifted = new UnvalidatedPerson("Anna de", "Vries", "+31 6 12345678", PERSON.address());

        assertThat(canonicalOf(shifted)).isNotEqualTo(canonicalOf(PERSON));
    }

    @Test
    void absentAdditionDiffersFromEmptyAndFromPresentAddition() {
        PersonDetails present = PersonDetails.parse(PERSON);
        PersonDetails absent = withAddition(present, Optional.empty());
        PersonDetails empty = withAddition(present, Optional.of(Sensitive.of("")));

        assertThat(CanonicalRequest.of(absent))
                .isNotEqualTo(CanonicalRequest.of(present))
                .isNotEqualTo(CanonicalRequest.of(empty));
    }

    private static CanonicalRequest canonicalOf(UnvalidatedPerson person) {
        return CanonicalRequest.of(PersonDetails.parse(person));
    }

    private static PersonDetails withAddition(PersonDetails details, Optional<Sensitive<String>> addition) {
        Address a = details.address();
        Address address = new Address(a.street(), a.houseNumber(), addition, a.postalCode(), a.city(), a.countryCode());
        return new PersonDetails(details.firstName(), details.lastName(), details.phoneNumber(), address);
    }
}
