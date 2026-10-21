package com.tikkie.person.adapter.out.dynamodb;

import com.tikkie.person.adapter.out.dynamodb.PersonItemMapper.StoredKey;
import com.tikkie.person.application.CallerId;
import com.tikkie.person.application.CreateOutcome;
import com.tikkie.person.application.IdempotencyKey;
import com.tikkie.person.application.PersonRepository;
import com.tikkie.person.application.RequestHash;
import com.tikkie.person.domain.Person;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public final class DynamoDbPersonRepository implements PersonRepository {

    private static final int MAX_ATTEMPTS = 3;
    private static final long FIRST_BACKOFF_MIN_MILLIS = 20;
    private static final long FIRST_BACKOFF_MAX_MILLIS = 80;

    private static final int KEY_PUT = 1;
    private static final String NONE = "None";
    private static final String UNKNOWN = "Unknown";
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final Set<String> TRANSIENT = Set.of(
            "TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded", "RequestLimitExceeded");

    private final DynamoDbClient client;
    private final String personsTable;
    private final String idempotencyKeysTable;
    private final Clock clock;

    // The caller owns the client and closes it.
    public DynamoDbPersonRepository(
            DynamoDbClient client, String personsTable, String idempotencyKeysTable, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.personsTable = Objects.requireNonNull(personsTable, "personsTable");
        this.idempotencyKeysTable = Objects.requireNonNull(idempotencyKeysTable, "idempotencyKeysTable");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // Branches on the cancellation reasons (index 0 is the person put, 1 the key put). A taken key is resolved
    // from the key item, which carries personId and createdAt, so a replay never reads the Persons table.
    // Transient reasons are retried after a backoff; anything else (ValidationError, a personId collision)
    // would fail again, so it fails at once. A conflicting writer may have stored the key by the time retries
    // run out, so the key is read once more before giving up. Never log the request: its toString() prints
    // personal data.
    @Override
    public CreateOutcome create(
            Person person, CallerId caller, IdempotencyKey key, RequestHash hash, String correlationId) {
        Instant now = clock.instant();
        TransactWriteItemsRequest request = createRequest(person, caller, key, hash, correlationId, now);
        TransactionCanceledException lastCancellation = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                backOff(attempt);
            }
            try {
                client.transactWriteItems(request);
                return new CreateOutcome.Inserted();
            } catch (TransactionCanceledException cancellation) {
                lastCancellation = cancellation;
                List<String> reasons = reasonCodes(cancellation);
                if (keyWasTaken(reasons)) {
                    Optional<StoredKey> stored = findLiveKey(caller, key, now);
                    if (stored.isPresent()) {
                        return outcomeFor(stored.get(), person, hash);
                    }
                } else if (!isTransient(reasons)) {
                    throw failure(cancellation);
                }
            }
        }
        Optional<StoredKey> stored = findLiveKey(caller, key, now);
        if (stored.isPresent()) {
            return outcomeFor(stored.get(), person, hash);
        }
        throw failure(lastCancellation);
    }

    private TransactWriteItemsRequest createRequest(
            Person person, CallerId caller, IdempotencyKey key, RequestHash hash, String correlationId, Instant now) {
        Put personPut = Put.builder()
                .tableName(personsTable)
                .item(PersonItemMapper.personItem(person, correlationId))
                .conditionExpression("attribute_not_exists(personId)")
                .build();
        Put keyPut = Put.builder()
                .tableName(idempotencyKeysTable)
                .item(PersonItemMapper.keyItem(caller, key, hash, person))
                .conditionExpression("attribute_not_exists(idempotencyKey) OR expiresAt <= :now")
                .expressionAttributeValues(Map.of(":now", epochSeconds(now)))
                .build();
        // The token makes an SDK retry after a lost response a no-op that reports success. The request is built
        // once per call because DynamoDB rejects a reused token whose parameters changed.
        return TransactWriteItemsRequest.builder()
                .clientRequestToken(person.id().toString())
                .transactItems(TransactWriteItem.builder().put(personPut).build(),
                        TransactWriteItem.builder().put(keyPut).build())
                .build();
    }

    // An expired key counts as absent even before DynamoDB's TTL sweep removes it, so the window is exactly 24 h.
    private Optional<StoredKey> findLiveKey(CallerId caller, IdempotencyKey key, Instant now) {
        GetItemResponse response = client.getItem(get -> get.tableName(idempotencyKeysTable)
                .key(PersonItemMapper.keyLookup(caller, key))
                .consistentRead(true));
        return Optional.of(response)
                .filter(GetItemResponse::hasItem)
                .map(found -> PersonItemMapper.storedKey(found.item()))
                .filter(stored -> !stored.isExpiredAt(now));
    }

    // A key holding our own candidate id means an earlier attempt of this very call committed.
    private static CreateOutcome outcomeFor(StoredKey stored, Person candidate, RequestHash hash) {
        if (stored.personId().equals(candidate.id())) {
            return new CreateOutcome.Inserted();
        }
        return stored.requestHash().equals(hash)
                ? new CreateOutcome.ReplayOf(stored.personId(), stored.createdAt())
                : new CreateOutcome.Conflict();
    }

    private static AttributeValue epochSeconds(Instant instant) {
        return AttributeValue.fromN(String.valueOf(instant.getEpochSecond()));
    }

    private static List<String> reasonCodes(TransactionCanceledException cancellation) {
        return cancellation.cancellationReasons().stream()
                .map(CancellationReason::code)
                .map(code -> code == null ? UNKNOWN : code)
                .toList();
    }

    private static boolean keyWasTaken(List<String> reasons) {
        return reasons.size() > KEY_PUT && CONDITIONAL_CHECK_FAILED.equals(reasons.get(KEY_PUT));
    }

    private static boolean isTransient(List<String> reasons) {
        return reasons.stream().anyMatch(TRANSIENT::contains)
                && reasons.stream().allMatch(reason -> NONE.equals(reason) || TRANSIENT.contains(reason));
    }

    // Jittered and doubling, so concurrent writers that collided do not collide again in lockstep.
    private static void backOff(int attempt) {
        long factor = 1L << (attempt - 2);
        long millis = ThreadLocalRandom.current()
                .nextLong(FIRST_BACKOFF_MIN_MILLIS * factor, FIRST_BACKOFF_MAX_MILLIS * factor + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("idempotent create was interrupted", interrupted);
        }
    }

    // Reason codes only: the SDK exception stays the cause, and its message holds no item data.
    private static IllegalStateException failure(TransactionCanceledException cancellation) {
        return new IllegalStateException("idempotent create failed: " + reasonCodes(cancellation), cancellation);
    }
}
