package com.tikkie.person.application;

import com.tikkie.person.domain.PersonId;
import java.time.Instant;

public sealed interface CreatePersonResult permits CreatePersonResult.Created, CreatePersonResult.Replayed {

    PersonId personId();

    Instant createdAt();

    record Created(PersonId personId, Instant createdAt) implements CreatePersonResult {
    }

    record Replayed(PersonId personId, Instant createdAt) implements CreatePersonResult {
    }
}
