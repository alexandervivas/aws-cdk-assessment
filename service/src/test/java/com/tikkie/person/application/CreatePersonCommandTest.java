package com.tikkie.person.application;

import static com.tikkie.person.application.PersonFixtures.CALLER;
import static com.tikkie.person.application.PersonFixtures.KEY;
import static com.tikkie.person.application.PersonFixtures.PERSON;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CreatePersonCommandTest {

    @Test
    void toStringHidesThePerson() {
        assertThat(new CreatePersonCommand(CALLER, KEY, PERSON, "corr-1").toString())
                .contains(KEY.toString(), "corr-1")
                .doesNotContain("person-api-client", "Anna", "Vries", "12345678", "Damrak", "1012", "Amsterdam");
    }
}
