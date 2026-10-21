package com.tikkie.person.application;

import static com.tikkie.person.application.PersonFixtures.CALLER;
import static com.tikkie.person.application.PersonFixtures.KEY;
import static com.tikkie.person.application.PersonFixtures.PERSON;
import static com.tikkie.person.application.PersonFixtures.personWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tikkie.person.application.CreatePersonResult.Created;
import com.tikkie.person.application.CreatePersonResult.Replayed;
import com.tikkie.person.domain.PersonId;
import com.tikkie.person.domain.UnvalidatedPerson;
import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import com.tikkie.person.domain.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CreatePersonUseCaseTest {

    private static final Instant FIRST_INSTANT = Instant.parse("2026-01-15T09:30:00Z");
    private static final PersonId FIRST_ID = new PersonId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final PersonId SECOND_ID = new PersonId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final RequestFingerprinter FINGERPRINTER = new HmacRequestFingerprinter();

    private final InMemoryPersonRepository repository = new InMemoryPersonRepository();
    private final Queue<PersonId> ids = new ArrayDeque<>(List.of(FIRST_ID, SECOND_ID));
    private final CreatePersonUseCase useCase = useCaseAt(FIRST_INSTANT);

    @Test
    void createsPersonWithTheClockInstantTheSuppliedIdAndTheCorrelationId() {
        assertThat(useCase.create(command(PERSON, "corr-1"))).isEqualTo(new Created(FIRST_ID, FIRST_INSTANT));
        assertThat(repository.writes()).singleElement().satisfies(write -> {
            assertThat(write.person().id()).isEqualTo(FIRST_ID);
            assertThat(write.correlationId()).isEqualTo("corr-1");
        });
    }

    @Test
    void passesTheKeyedFingerprintOfTheCanonicalRequestToTheRepository() {
        useCase.create(command(PERSON, "corr-1"));

        assertThat(repository.writes()).singleElement().satisfies(write -> assertThat(write.hash())
                .isEqualTo(new RequestHash("65b1679a729e0bbcb5fe67c2845e490a6c3f0937cdfeb13cef84ccd9702bee48")));
    }

    @Test
    void replayReturnsTheOriginalIdAndCreationTime() {
        useCase.create(command(PERSON, "corr-1"));

        CreatePersonResult replay = useCaseAt(FIRST_INSTANT.plusSeconds(300)).create(command(PERSON, "corr-2"));

        assertThat(replay).isEqualTo(new Replayed(FIRST_ID, FIRST_INSTANT));
        assertThat(repository.writes()).hasSize(1);
    }

    @Test
    void whitespaceAndPhoneFormattingVariantReplays() {
        useCase.create(command(PERSON, "corr-1"));
        UnvalidatedPerson variant = new UnvalidatedPerson(" Anna ", "de Vries", "06 12345678",
                new UnvalidatedAddress(" Damrak", "1", "III", "1012lg", "Amsterdam ", "NL"));

        assertThat(useCase.create(command(variant, "corr-2"))).isEqualTo(new Replayed(FIRST_ID, FIRST_INSTANT));
    }

    @Test
    void sameKeyWithoutTheAdditionIsAConflict() {
        useCase.create(command(PERSON, "corr-1"));
        UnvalidatedPerson withoutAddition = personWith("houseNumberAddition", null);

        assertThatThrownBy(() -> useCase.create(command(withoutAddition, "corr-2")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(repository.writes()).hasSize(1);
    }

    @Test
    void creationTimeIsTruncatedToMillisecondsSoAReplayReturnsTheSameValue() {
        Instant withNanos = Instant.parse("2026-01-15T09:30:00.123456789Z");

        CreatePersonResult created = useCaseAt(withNanos).create(command(PERSON, "corr-1"));

        assertThat(created.createdAt()).isEqualTo(Instant.parse("2026-01-15T09:30:00.123Z"));
        assertThat(repository.writes()).singleElement()
                .satisfies(write -> assertThat(write.person().createdAt()).isEqualTo(created.createdAt()));
    }

    @Test
    void sameDetailsUnderADifferentKeyCreateASecondPerson() {
        useCase.create(command(PERSON, "corr-1"));
        IdempotencyKey otherKey = IdempotencyKey.parse("5d2c7e1a-3b4f-4a6d-8e9c-0f1a2b3c4d5e");

        CreatePersonResult second = useCase.create(new CreatePersonCommand(CALLER, otherKey, PERSON, "corr-2"));

        assertThat(second).isEqualTo(new Created(SECOND_ID, FIRST_INSTANT));
        assertThat(repository.writes()).hasSize(2);
    }

    @Test
    void twoCallersUsingTheSameKeyAndPayloadCreateTwoPersons() {
        CallerId otherCaller = CallerId.of("arn:aws:iam::111122223333:user/other-client");
        useCase.create(command(PERSON, "corr-1"));

        CreatePersonResult second = useCase.create(new CreatePersonCommand(otherCaller, KEY, PERSON, "corr-2"));

        assertThat(second).isEqualTo(new Created(SECOND_ID, FIRST_INSTANT));
        assertThat(repository.writes()).hasSize(2);
    }

    @Test
    void sameRoleOnAnotherStsSessionReplays() {
        CallerId newSession = CallerId.of("arn:aws:sts::111122223333:assumed-role/person-api-client/session-2");
        useCase.create(command(PERSON, "corr-1"));

        CreatePersonResult retry = useCase.create(new CreatePersonCommand(newSession, KEY, PERSON, "corr-2"));

        assertThat(retry).isEqualTo(new Replayed(FIRST_ID, FIRST_INSTANT));
    }

    @Test
    void concurrentCallsWithTheSameKeyCreateOnePersonAndReplayTheRest() throws Exception {
        int callers = 32;
        CreatePersonUseCase concurrentUseCase =
                new CreatePersonUseCase(repository, FINGERPRINTER, Clock.fixed(FIRST_INSTANT, ZoneOffset.UTC),
                        PersonId::random);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreatePersonResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return concurrentUseCase.create(command(PERSON, "corr-1"));
                }));
            }
            start.countDown();
            List<CreatePersonResult> results = new ArrayList<>();
            for (Future<CreatePersonResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(Created.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(Replayed.class::isInstance).hasSize(callers - 1);
            assertThat(results).extracting(CreatePersonResult::personId).containsOnly(results.getFirst().personId());
            assertThat(repository.writes()).hasSize(1);
        }
    }

    @Test
    void invalidPayloadIsRejectedBeforeTheRepositoryIsCalled() {
        UnvalidatedPerson invalid = personWith("firstName", "");

        assertThatThrownBy(() -> useCase.create(command(invalid, "corr-1"))).isInstanceOf(ValidationException.class);
        assertThat(repository.calls()).isZero();
    }

    private CreatePersonUseCase useCaseAt(Instant instant) {
        return new CreatePersonUseCase(repository, FINGERPRINTER, Clock.fixed(instant, ZoneOffset.UTC), ids::remove);
    }

    private static CreatePersonCommand command(UnvalidatedPerson person, String correlationId) {
        return new CreatePersonCommand(CALLER, KEY, person, correlationId);
    }
}
