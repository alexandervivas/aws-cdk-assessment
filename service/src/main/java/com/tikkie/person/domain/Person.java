package com.tikkie.person.domain;

import java.time.Instant;
import java.util.Objects;

public record Person(PersonId id, PersonDetails details, Instant createdAt) {

    public Person {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public String toString() {
        return "Person[id=" + id + ", createdAt=" + createdAt + "]";
    }
}
