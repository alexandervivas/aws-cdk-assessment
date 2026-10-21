package com.tikkie.person.application;

import com.tikkie.person.domain.UnvalidatedPerson;
import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import java.util.List;

final class PersonFixtures {

    static final CallerId CALLER = CallerId.of("arn:aws:sts::111122223333:assumed-role/person-api-client/session-1");
    static final IdempotencyKey KEY = IdempotencyKey.parse("0b6f5a3e-8c1d-4e2f-9a7b-3c4d5e6f7a8b");
    private static final List<String> FIELDS = List.of("firstName", "lastName", "phoneNumber", "street",
            "houseNumber", "houseNumberAddition", "postalCode", "city", "countryCode");
    static final UnvalidatedPerson PERSON = personWith("firstName", "Anna");

    private PersonFixtures() {
    }

    static UnvalidatedPerson personWith(String field, String value) {
        String[] v = {"Anna", "de Vries", "+31 6 12345678", "Damrak", "1", "III", "1012 LG", "Amsterdam", "NL"};
        v[FIELDS.indexOf(field)] = value;
        return new UnvalidatedPerson(v[0], v[1], v[2], new UnvalidatedAddress(v[3], v[4], v[5], v[6], v[7], v[8]));
    }
}
