package com.tikkie.person.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.tikkie.person.domain.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyTest {

    private static final String KEY = "0b6f5a3e-8c1d-4e2f-9a7b-3c4d5e6f7a8b";

    @ParameterizedTest
    @ValueSource(strings = {KEY, "0B6F5A3E-8C1D-4E2F-9A7B-3C4D5E6F7A8B"})
    void acceptsCanonicalUuidInEitherCase(String raw) {
        assertThat(IdempotencyKey.parse(raw).value()).isEqualTo(UUID.fromString(KEY));
    }

    @Test
    void printsAsTheUuid() {
        assertThat(IdempotencyKey.parse(KEY)).hasToString(KEY);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectsMissingKeyAsRequired(String raw) {
        assertRejectedWith(raw, "required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "not-a-uuid", "0b6f5a3e8c1d4e2f9a7b3c4d5e6f7a8b", " " + KEY})
    void rejectsNonCanonicalKeyAsInvalidFormatWithoutEchoingIt(String raw) {
        assertRejectedWith(raw, "invalid_format");
    }

    private static void assertRejectedWith(String raw, String code) {
        assertThatThrownBy(() -> IdempotencyKey.parse(raw)).isInstanceOfSatisfying(ValidationException.class, e -> {
            assertThat(e.errors()).extracting("field", "code").containsExactly(tuple("Idempotency-Key", code));
            if (raw != null && !raw.isBlank()) {
                assertThat(e.getMessage() + e.errors().getFirst().message()).doesNotContain(raw.strip());
            }
        });
    }
}
