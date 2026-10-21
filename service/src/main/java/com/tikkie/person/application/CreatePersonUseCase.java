package com.tikkie.person.application;

import com.tikkie.person.domain.Person;
import com.tikkie.person.domain.PersonDetails;
import com.tikkie.person.domain.PersonId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;

public final class CreatePersonUseCase {

    private final PersonRepository repository;
    private final RequestFingerprinter fingerprinter;
    private final Clock clock;
    private final Supplier<PersonId> ids;

    public CreatePersonUseCase(
            PersonRepository repository, RequestFingerprinter fingerprinter, Clock clock, Supplier<PersonId> ids) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public CreatePersonResult create(CreatePersonCommand command) {
        PersonDetails details = PersonDetails.parse(command.person());
        // Milliseconds match what any store keeps, so a replay returns exactly the createdAt of the original.
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        // Only a candidate: the repository decides atomically whether it is used or an earlier one replays.
        Person person = new Person(ids.get(), details, createdAt);
        RequestHash hash = fingerprinter.fingerprint(CanonicalRequest.of(details));
        CreateOutcome outcome = repository.create(person, command.caller(), command.idempotencyKey(), hash,
                command.correlationId());
        return switch (outcome) {
            case CreateOutcome.Inserted() -> new CreatePersonResult.Created(person.id(), person.createdAt());
            case CreateOutcome.ReplayOf replay -> new CreatePersonResult.Replayed(replay.personId(), replay.createdAt());
            case CreateOutcome.Conflict() -> throw new IdempotencyConflictException();
        };
    }
}
