package com.tikkie.person.adapter.out.dynamodb;

import com.tikkie.person.application.CallerId;
import com.tikkie.person.application.CanonicalRequest;
import com.tikkie.person.application.HmacRequestFingerprinter;
import com.tikkie.person.application.RequestHash;
import com.tikkie.person.domain.Person;
import com.tikkie.person.domain.PersonDetails;
import com.tikkie.person.domain.PersonId;
import com.tikkie.person.domain.UnvalidatedPerson;
import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

final class SamplePeople {

    static final CallerId CALLER = CallerId.of("arn:aws:iam::111122223333:user/integration-client");

    // Near the real time so expiresAt lies in the future and DynamoDB Local's TTL never removes a key mid-test.
    static final Clock CLOCK = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    static final Instant CREATED_AT = CLOCK.instant();

    private SamplePeople() {
    }

    static Person person() {
        return withAddition(null);
    }

    static Person withAddition(String houseNumberAddition) {
        return new Person(PersonId.random(), details(houseNumberAddition), CREATED_AT);
    }

    static RequestHash hashOf(Person person) {
        return new HmacRequestFingerprinter().fingerprint(CanonicalRequest.of(person.details()));
    }

    private static PersonDetails details(String houseNumberAddition) {
        return PersonDetails.parse(new UnvalidatedPerson("Anna", "de Vries", "+31612345678",
                new UnvalidatedAddress("Damrak", "1", houseNumberAddition, "1012 LG", "Amsterdam", "NL")));
    }
}
