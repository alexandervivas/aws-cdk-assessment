package com.tikkie.person.adapter.out.dynamodb;

import static com.tikkie.person.adapter.out.dynamodb.SamplePeople.hashOf;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.adapter.out.dynamodb.DynamoDbLocal.Tables;
import com.tikkie.person.application.CreateOutcome;
import com.tikkie.person.application.IdempotencyKey;
import com.tikkie.person.application.RequestHash;
import com.tikkie.person.domain.Person;
import com.tikkie.person.domain.PersonId;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class DynamoDbConcurrentDuplicatesTest {

    private record Resolved(PersonId personId, Instant createdAt) {
    }

    private final DynamoDbClient client = DynamoDbLocal.client();
    private final Tables tables = DynamoDbLocal.createTables(client);
    private final DynamoDbPersonRepository repository =
            new DynamoDbPersonRepository(client, tables.persons(), tables.idempotencyKeys(), SamplePeople.CLOCK);

    @AfterEach
    void closeClient() {
        client.close();
    }

    // DynamoDB Local serialises transactions, so the loser always sees ConditionalCheckFailed on the key and
    // replays. This proves convergence to one person; the TransactionConflict retry path is covered by the
    // scripted cancellations in DynamoDbPersonRepositoryTest.
    @RepeatedTest(5)
    void sameKeyAndHashAtOnceCreateOnePerson() throws Exception {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        Person first = SamplePeople.person();
        Person second = new Person(PersonId.random(), first.details(), first.createdAt().plusSeconds(1));
        RequestHash hash = hashOf(first);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Resolved> a = pool.submit(() -> createAfter(start, first, key, hash));
            Future<Resolved> b = pool.submit(() -> createAfter(start, second, key, hash));
            start.countDown();

            assertThat(a.get(10, SECONDS)).isEqualTo(b.get(10, SECONDS));
        }
        assertThat(client.scan(scan -> scan.tableName(tables.persons())).count()).isEqualTo(1);
        assertThat(client.scan(scan -> scan.tableName(tables.idempotencyKeys())).count()).isEqualTo(1);
    }

    private Resolved createAfter(CountDownLatch start, Person person, IdempotencyKey key, RequestHash hash)
            throws InterruptedException {
        start.await();
        return switch (repository.create(person, SamplePeople.CALLER, key, hash, "corr-" + person.id())) {
            case CreateOutcome.Inserted inserted -> new Resolved(person.id(), person.createdAt());
            case CreateOutcome.ReplayOf replay -> new Resolved(replay.personId(), replay.createdAt());
            case CreateOutcome.Conflict conflict -> throw new AssertionError("same hash must never conflict");
        };
    }
}
