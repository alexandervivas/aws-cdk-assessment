package com.tikkie.person.adapter.out.dynamodb;

import com.tikkie.person.application.CallerId;
import com.tikkie.person.application.IdempotencyKey;
import com.tikkie.person.application.RequestHash;
import com.tikkie.person.domain.Address;
import com.tikkie.person.domain.Person;
import com.tikkie.person.domain.PersonDetails;
import com.tikkie.person.domain.PersonId;
import com.tikkie.person.domain.ValidationException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

final class PersonItemMapper {

    static final Duration KEY_TIME_TO_LIVE = Duration.ofHours(24);
    private static final String SCHEMA_VERSION = "1";

    record StoredKey(RequestHash requestHash, PersonId personId, Instant createdAt, Instant expiresAt) {

        boolean isExpiredAt(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }

    private PersonItemMapper() {
    }

    static Map<String, AttributeValue> personItem(Person person, String correlationId) {
        PersonDetails details = person.details();
        return Map.of(
                "personId", s(person.id().toString()),
                "firstName", s(details.firstName().value().reveal()),
                "lastName", s(details.lastName().value().reveal()),
                "phoneNumber", s(details.phoneNumber().e164().reveal()),
                "address", AttributeValue.fromM(addressMap(details.address())),
                "createdAt", s(person.createdAt().toString()),
                "correlationId", s(correlationId),
                "schemaVersion", AttributeValue.fromN(SCHEMA_VERSION));
    }

    static Map<String, AttributeValue> keyItem(CallerId caller, IdempotencyKey key, RequestHash hash, Person person) {
        Instant expiresAt = person.createdAt().plus(KEY_TIME_TO_LIVE);
        return Map.of(
                "idempotencyKey", s(scopedKey(caller, key)),
                "callerId", s(caller.value()),
                "requestHash", s(hash.hex()),
                "personId", s(person.id().toString()),
                "createdAt", s(person.createdAt().toString()),
                "expiresAt", AttributeValue.fromN(String.valueOf(expiresAt.getEpochSecond())));
    }

    static Map<String, AttributeValue> keyLookup(CallerId caller, IdempotencyKey key) {
        return Map.of("idempotencyKey", s(scopedKey(caller, key)));
    }

    // Keys are scoped per caller. "|" occurs in neither an IAM ARN nor a UUID, and CallerId rejects it.
    private static String scopedKey(CallerId caller, IdempotencyKey key) {
        return caller.value() + "|" + key;
    }

    static StoredKey storedKey(Map<String, AttributeValue> item) {
        String requestHash = text(item, "requestHash");
        String personId = text(item, "personId");
        String createdAt = text(item, "createdAt");
        String expiresAt = number(item, "expiresAt");
        return new StoredKey(
                parsed("requestHash", () -> new RequestHash(requestHash)),
                parsed("personId", () -> PersonId.parse("personId", personId)),
                parsed("createdAt", () -> Instant.parse(createdAt)),
                parsed("expiresAt", () -> Instant.ofEpochSecond(Long.parseLong(expiresAt))));
    }

    private static String text(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.s() == null) {
            throw new IllegalStateException("idempotency key item lacks " + name);
        }
        return value.s();
    }

    private static String number(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.n() == null) {
            throw new IllegalStateException("idempotency key item lacks " + name);
        }
        return value.n();
    }

    // No cause: parse exceptions echo the stored value, and the request hash is derived from personal data.
    private static <T> T parsed(String name, Supplier<T> parser) {
        try {
            return parser.get();
        } catch (IllegalArgumentException | DateTimeException | ValidationException malformed) {
            throw new IllegalStateException("idempotency key item has a malformed " + name);
        }
    }

    private static Map<String, AttributeValue> addressMap(Address address) {
        Map<String, AttributeValue> map = new HashMap<>();
        map.put("street", s(address.street().reveal()));
        map.put("houseNumber", s(address.houseNumber().reveal()));
        address.houseNumberAddition().ifPresent(addition -> map.put("houseNumberAddition", s(addition.reveal())));
        map.put("postalCode", s(address.postalCode().value().reveal()));
        map.put("city", s(address.city().reveal()));
        map.put("countryCode", s(address.countryCode().value()));
        return map;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
