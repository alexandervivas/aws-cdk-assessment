package com.tikkie.person.adapter.out.dynamodb;

import static com.tikkie.person.adapter.out.dynamodb.SamplePeople.CALLER;
import static com.tikkie.person.adapter.out.dynamodb.SamplePeople.CREATED_AT;
import static com.tikkie.person.adapter.out.dynamodb.SamplePeople.hashOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tikkie.person.adapter.out.dynamodb.DynamoDbLocal.Tables;
import com.tikkie.person.application.CallerId;
import com.tikkie.person.application.CreateOutcome;
import com.tikkie.person.application.IdempotencyKey;
import com.tikkie.person.application.RequestHash;
import com.tikkie.person.domain.Person;
import com.tikkie.person.domain.PersonId;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class DynamoDbPersonRepositoryTest {

    private static final IdempotencyKey KEY = IdempotencyKey.parse("0b6f5a3e-8c1d-4e2f-9a7b-3c4d5e6f7a8b");
    private static final Duration KEY_WINDOW = Duration.ofHours(24);

    private static final String NONE = "None";
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final String TRANSACTION_CONFLICT = "TransactionConflict";
    private static final String THROTTLING_ERROR = "ThrottlingError";
    private static final String VALIDATION_ERROR = "ValidationError";

    private final DynamoDbClient local = DynamoDbLocal.client();
    private final Tables tables = DynamoDbLocal.createTables(local);
    private final InterceptingClient client = new InterceptingClient(local);
    private final DynamoDbPersonRepository repository = repositoryAt(SamplePeople.CLOCK);

    @AfterEach
    void assertPersonsNeverReadAndClose() {
        try {
            assertThat(client.readTables).doesNotContain(tables.persons());
        } finally {
            client.close();
        }
    }

    @Test
    void insertWritesThePersonAndTheKeyItem() {
        Person person = SamplePeople.person();
        RequestHash hash = hashOf(person);

        assertThat(repository.create(person, CALLER, KEY, hash, "corr-1")).isEqualTo(new CreateOutcome.Inserted());

        assertThat(personItem(person.id())).isEqualTo(PersonItemMapper.personItem(person, "corr-1"));
        assertThat(keyItem(CALLER)).isEqualTo(PersonItemMapper.keyItem(CALLER, KEY, hash, person));
        assertThat(client.readTables).isEmpty();
    }

    @Test
    void theCandidatePersonIdIsTheClientRequestToken() {
        Person person = SamplePeople.person();

        repository.create(person, CALLER, KEY, hashOf(person), "corr-1");

        assertThat(client.transactions).singleElement()
                .extracting(TransactWriteItemsRequest::clientRequestToken).isEqualTo(person.id().toString());
    }

    @Test
    void sameKeyAndHashReplaysTheOriginalPerson() {
        Person original = SamplePeople.person();
        RequestHash hash = hashOf(original);
        repository.create(original, CALLER, KEY, hash, "corr-1");
        Person retry = new Person(PersonId.random(), original.details(), original.createdAt().plusSeconds(60));

        CreateOutcome outcome = repository.create(retry, CALLER, KEY, hash, "corr-2");

        assertThat(outcome).isEqualTo(new CreateOutcome.ReplayOf(original.id(), original.createdAt()));
        assertThat(itemCount(tables.persons())).isEqualTo(1);
        assertThat(client.readTables).containsExactly(tables.idempotencyKeys());
    }

    @Test
    void sameKeyWithDifferentHashConflicts() {
        Person original = SamplePeople.person();
        repository.create(original, CALLER, KEY, hashOf(original), "corr-1");
        Person other = SamplePeople.withAddition("III");

        CreateOutcome outcome = repository.create(other, CALLER, KEY, hashOf(other), "corr-2");

        assertThat(outcome).isEqualTo(new CreateOutcome.Conflict());
        assertThat(itemCount(tables.persons())).isEqualTo(1);
        assertThat(client.readTables).containsExactly(tables.idempotencyKeys());
    }

    @Test
    void sameKeyFromTwoCallersCreatesTwoPersons() {
        CallerId otherCaller = CallerId.of("arn:aws:iam::111122223333:user/other-client");
        Person first = SamplePeople.person();
        Person second = new Person(PersonId.random(), first.details(), first.createdAt());
        RequestHash hash = hashOf(first);

        assertThat(repository.create(first, CALLER, KEY, hash, "corr-1")).isEqualTo(new CreateOutcome.Inserted());
        assertThat(repository.create(second, otherCaller, KEY, hash, "corr-2")).isEqualTo(new CreateOutcome.Inserted());

        assertThat(itemCount(tables.persons())).isEqualTo(2);
        assertThat(itemCount(tables.idempotencyKeys())).isEqualTo(2);
    }

    @Test
    void aTransactionConflictIsRetriedAfterABackoff() {
        client.cancelNextWith(NONE, TRANSACTION_CONFLICT);
        Person person = SamplePeople.person();
        long start = System.nanoTime();

        CreateOutcome outcome = repository.create(person, CALLER, KEY, hashOf(person), "corr-1");

        assertThat(outcome).isEqualTo(new CreateOutcome.Inserted());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(20));
        assertThat(client.transactions).hasSize(2);
        assertThat(client.readTables).isEmpty();
    }

    @Test
    void exhaustedRetriesFailWithOnlyTheReasonCodesAndTheSdkCause() {
        client.cancelNextWith(NONE, THROTTLING_ERROR);
        client.cancelNextWith(NONE, THROTTLING_ERROR);
        client.cancelNextWith(NONE, THROTTLING_ERROR);
        Person person = SamplePeople.withAddition("III");

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.create(person, CALLER, KEY, hashOf(person), "corr-1"))
                .withMessage("idempotent create failed: [None, ThrottlingError]")
                .withCauseInstanceOf(TransactionCanceledException.class)
                .satisfies(e -> assertThat(stackTraceOf(e))
                        .doesNotContain("Anna", "de Vries", "+31612345678", "Damrak", "1012 LG", "Amsterdam", "III"));
        assertThat(client.transactions).hasSize(3);
        assertThat(itemCount(tables.persons())).isZero();
    }

    @Test
    void exhaustedRetriesReplayAKeyStoredMeanwhile() {
        Person original = SamplePeople.person();
        RequestHash hash = hashOf(original);
        repository.create(original, CALLER, KEY, hash, "corr-1");
        client.cancelNextWith(NONE, TRANSACTION_CONFLICT);
        client.cancelNextWith(NONE, TRANSACTION_CONFLICT);
        client.cancelNextWith(NONE, TRANSACTION_CONFLICT);

        CreateOutcome outcome = repository.create(SamplePeople.person(), CALLER, KEY, hash, "corr-2");

        assertThat(outcome).isEqualTo(new CreateOutcome.ReplayOf(original.id(), original.createdAt()));
        assertThat(client.readTables).containsExactly(tables.idempotencyKeys());
    }

    @Test
    void exhaustedRetriesWithNoKeyStoredStillFail() {
        client.cancelNextWith(NONE, TRANSACTION_CONFLICT);
        client.cancelNextWith(NONE, TRANSACTION_CONFLICT);
        client.cancelNextWith(NONE, TRANSACTION_CONFLICT);
        Person person = SamplePeople.person();

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.create(person, CALLER, KEY, hashOf(person), "corr-1"))
                .withMessage("idempotent create failed: [None, TransactionConflict]");
        assertThat(client.transactions).hasSize(3);
        assertThat(client.readTables).containsExactly(tables.idempotencyKeys());
    }

    @ParameterizedTest
    @CsvSource(value = {"null, null", "None, null"}, nullValues = "null")
    void aMissingReasonCodeFailsFastAsUnknown(String personPutCode, String keyPutCode) {
        client.cancelNextWith(personPutCode, keyPutCode);
        Person person = SamplePeople.person();

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.create(person, CALLER, KEY, hashOf(person), "corr-1"))
                .withMessageContaining("Unknown")
                .withCauseInstanceOf(TransactionCanceledException.class);
        assertThat(client.transactions).hasSize(1);
    }

    @Test
    void aValidationErrorFailsFastWithoutReadingTheKey() {
        client.cancelNextWith(NONE, VALIDATION_ERROR);
        Person person = SamplePeople.person();

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.create(person, CALLER, KEY, hashOf(person), "corr-1"))
                .withMessage("idempotent create failed: [None, ValidationError]");
        assertThat(client.transactions).hasSize(1);
        assertThat(client.readTables).isEmpty();
    }

    @Test
    void aPersonIdCollisionFailsFast() {
        Person existing = SamplePeople.person();
        // Written directly: resending the same token with other parameters is rejected before any condition runs.
        local.putItem(put -> put.tableName(tables.persons()).item(PersonItemMapper.personItem(existing, "corr-1")));
        RequestHash hash = hashOf(existing);

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.create(existing, CALLER, KEY, hash, "corr-2"))
                .withMessage("idempotent create failed: [ConditionalCheckFailed, None]");
        assertThat(client.transactions).hasSize(1);
        assertThat(client.readTables).isEmpty();
    }

    @Test
    void aKeyIsHonouredUntilItsLastSecond() {
        Person original = SamplePeople.person();
        RequestHash hash = hashOf(original);
        repository.create(original, CALLER, KEY, hash, "corr-1");

        CreateOutcome outcome = repositoryAt(clockAt(CREATED_AT.plus(KEY_WINDOW).minusSeconds(1)))
                .create(SamplePeople.person(), CALLER, KEY, hash, "corr-2");

        assertThat(outcome).isEqualTo(new CreateOutcome.ReplayOf(original.id(), original.createdAt()));
    }

    @Test
    void anExpiredKeyIsOverwrittenByANewInsert() {
        Person original = SamplePeople.person();
        RequestHash hash = hashOf(original);
        repository.create(original, CALLER, KEY, hash, "corr-1");
        Person later = SamplePeople.person();

        CreateOutcome outcome = repositoryAt(clockAt(CREATED_AT.plus(KEY_WINDOW)))
                .create(later, CALLER, KEY, hash, "corr-2");

        assertThat(outcome).isEqualTo(new CreateOutcome.Inserted());
        assertThat(keyItem(CALLER).get("personId").s()).isEqualTo(later.id().toString());
        assertThat(itemCount(tables.persons())).isEqualTo(2);
    }

    @Test
    void anExpiredKeyReadAfterACancellationCountsAsAbsent() {
        Person original = SamplePeople.person();
        RequestHash hash = hashOf(original);
        repository.create(original, CALLER, KEY, hash, "corr-1");
        client.cancelNextWith(NONE, CONDITIONAL_CHECK_FAILED);

        CreateOutcome outcome = repositoryAt(clockAt(CREATED_AT.plus(KEY_WINDOW)))
                .create(SamplePeople.person(), CALLER, KEY, hash, "corr-2");

        assertThat(outcome).isEqualTo(new CreateOutcome.Inserted());
        assertThat(client.readTables).containsExactly(tables.idempotencyKeys());
    }

    @Test
    void aRetryAfterALostResponseReportsInserted() {
        client.loseNextResponse();
        Person person = SamplePeople.person();

        CreateOutcome outcome = repository.create(person, CALLER, KEY, hashOf(person), "corr-1");

        assertThat(outcome).isEqualTo(new CreateOutcome.Inserted());
        assertThat(itemCount(tables.persons())).isEqualTo(1);
    }

    @Test
    void aMalformedKeyItemFailsWithoutReplaying() {
        Person person = SamplePeople.person();
        RequestHash hash = hashOf(person);
        Map<String, AttributeValue> keyItem = new HashMap<>(PersonItemMapper.keyItem(CALLER, KEY, hash, person));
        keyItem.remove("personId");
        local.putItem(put -> put.tableName(tables.idempotencyKeys()).item(keyItem));

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.create(person, CALLER, KEY, hash, "corr-1"))
                .withMessage("idempotency key item lacks personId");
        assertThat(itemCount(tables.persons())).isZero();
    }

    private DynamoDbPersonRepository repositoryAt(Clock clock) {
        return new DynamoDbPersonRepository(client, tables.persons(), tables.idempotencyKeys(), clock);
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private Map<String, AttributeValue> personItem(PersonId id) {
        return local.getItem(get -> get.tableName(tables.persons())
                .key(Map.of("personId", AttributeValue.fromS(id.toString())))).item();
    }

    private Map<String, AttributeValue> keyItem(CallerId caller) {
        return local.getItem(get -> get.tableName(tables.idempotencyKeys())
                .key(PersonItemMapper.keyLookup(caller, KEY))).item();
    }

    private int itemCount(String table) {
        return local.scan(scan -> scan.tableName(table)).count();
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static final class InterceptingClient implements DynamoDbClient {

        private final DynamoDbClient delegate;
        private final List<String> readTables = new CopyOnWriteArrayList<>();
        private final List<TransactWriteItemsRequest> transactions = new CopyOnWriteArrayList<>();
        private final Queue<List<String>> scriptedCancellations = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean loseNextResponse = new AtomicBoolean();

        InterceptingClient(DynamoDbClient delegate) {
            this.delegate = delegate;
        }

        void cancelNextWith(String personPutCode, String keyPutCode) {
            scriptedCancellations.add(Arrays.asList(personPutCode, keyPutCode));
        }

        void loseNextResponse() {
            loseNextResponse.set(true);
        }

        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            transactions.add(request);
            List<String> codes = scriptedCancellations.poll();
            if (codes != null) {
                throw TransactionCanceledException.builder()
                        .message("Transaction cancelled")
                        .cancellationReasons(codes.stream()
                                .map(code -> CancellationReason.builder().code(code).build())
                                .toList())
                        .build();
            }
            if (loseNextResponse.getAndSet(false)) {
                delegate.transactWriteItems(request);
                // The resend carries no token, the worst case: DynamoDB cannot recognise it, so the repository must.
                return delegate.transactWriteItems(request.toBuilder().clientRequestToken(null).build());
            }
            return delegate.transactWriteItems(request);
        }

        @Override
        public GetItemResponse getItem(GetItemRequest request) {
            readTables.add(request.tableName());
            return delegate.getItem(request);
        }

        @Override
        public String serviceName() {
            return delegate.serviceName();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
