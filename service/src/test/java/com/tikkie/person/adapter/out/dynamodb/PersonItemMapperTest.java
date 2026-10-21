package com.tikkie.person.adapter.out.dynamodb;

import static com.tikkie.person.adapter.out.dynamodb.SamplePeople.CALLER;
import static com.tikkie.person.adapter.out.dynamodb.SamplePeople.hashOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tikkie.person.adapter.out.dynamodb.PersonItemMapper.StoredKey;
import com.tikkie.person.application.IdempotencyKey;
import com.tikkie.person.application.RequestHash;
import com.tikkie.person.domain.Person;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class PersonItemMapperTest {

    private static final IdempotencyKey KEY = IdempotencyKey.parse("0b6f5a3e-8c1d-4e2f-9a7b-3c4d5e6f7a8b");

    @Test
    void personItemHoldsNormalisedValuesWithDynamoDbTypes() {
        Person person = SamplePeople.withAddition("III");

        assertThat(PersonItemMapper.personItem(person, "corr-1")).isEqualTo(Map.of(
                "personId", s(person.id().toString()),
                "firstName", s("Anna"),
                "lastName", s("de Vries"),
                "phoneNumber", s("+31612345678"),
                "address", AttributeValue.fromM(Map.of(
                        "street", s("Damrak"),
                        "houseNumber", s("1"),
                        "houseNumberAddition", s("III"),
                        "postalCode", s("1012 LG"),
                        "city", s("Amsterdam"),
                        "countryCode", s("NL"))),
                "createdAt", s(SamplePeople.CREATED_AT.toString()),
                "correlationId", s("corr-1"),
                "schemaVersion", AttributeValue.fromN("1")));
    }

    @Test
    void personItemOmitsAnAbsentAddition() {
        Map<String, AttributeValue> address = PersonItemMapper.personItem(SamplePeople.person(), "corr-1")
                .get("address").m();

        assertThat(address).containsOnlyKeys("street", "houseNumber", "postalCode", "city", "countryCode");
    }

    @Test
    void keyItemIsScopedToTheCallerAndExpiresOneDayAfterCreation() {
        Person person = SamplePeople.person();
        RequestHash hash = hashOf(person);

        assertThat(PersonItemMapper.keyItem(CALLER, KEY, hash, person)).isEqualTo(Map.of(
                "idempotencyKey", s("arn:aws:iam::111122223333:user/integration-client|" + KEY),
                "callerId", s("arn:aws:iam::111122223333:user/integration-client"),
                "requestHash", s(hash.hex()),
                "personId", s(person.id().toString()),
                "createdAt", s(SamplePeople.CREATED_AT.toString()),
                "expiresAt", AttributeValue.fromN(String.valueOf(SamplePeople.CREATED_AT.getEpochSecond() + 86_400))));
    }

    @Test
    void aFreshKeyItemExpiresInTheFuture() {
        Person person = SamplePeople.person();

        String expiresAt = PersonItemMapper.keyItem(CALLER, KEY, hashOf(person), person)
                .get("expiresAt").n();

        assertThat(Long.parseLong(expiresAt)).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void storedKeyReadsBackWhatKeyItemWrote() {
        Person person = SamplePeople.person();
        RequestHash hash = hashOf(person);

        StoredKey stored = PersonItemMapper.storedKey(PersonItemMapper.keyItem(CALLER, KEY, hash, person));

        assertThat(stored).isEqualTo(new StoredKey(hash, person.id(), person.createdAt(),
                Instant.ofEpochSecond(person.createdAt().getEpochSecond() + 86_400)));
    }

    @Test
    void aStoredKeyExpiresExactlyAtItsExpiresAt() {
        Person person = SamplePeople.person();
        StoredKey stored = PersonItemMapper.storedKey(
                PersonItemMapper.keyItem(CALLER, KEY, hashOf(person), person));

        assertThat(stored.isExpiredAt(stored.expiresAt().minusSeconds(1))).isFalse();
        assertThat(stored.isExpiredAt(stored.expiresAt())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestHash", "personId", "createdAt", "expiresAt"})
    void aKeyItemMissingAnAttributeFailsWithAValueFreeMessage(String attribute) {
        Map<String, AttributeValue> item = new HashMap<>(freshKeyItem());
        item.remove(attribute);

        assertThatIllegalStateException()
                .isThrownBy(() -> PersonItemMapper.storedKey(item))
                .withMessage("idempotency key item lacks " + attribute);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestHash", "personId", "createdAt", "expiresAt"})
    void aKeyItemWithAMalformedAttributeFailsWithAValueFreeMessage(String attribute) {
        Map<String, AttributeValue> item = new HashMap<>(freshKeyItem());
        item.put(attribute, attribute.equals("expiresAt") ? AttributeValue.fromN("1.5") : s("1-1-1-1-1"));

        assertThatIllegalStateException()
                .isThrownBy(() -> PersonItemMapper.storedKey(item))
                .withMessage("idempotency key item has a malformed " + attribute)
                .withNoCause();
    }

    private static Map<String, AttributeValue> freshKeyItem() {
        Person person = SamplePeople.person();
        return PersonItemMapper.keyItem(CALLER, KEY, hashOf(person), person);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
