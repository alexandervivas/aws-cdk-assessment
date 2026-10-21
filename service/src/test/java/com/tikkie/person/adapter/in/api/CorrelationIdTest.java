package com.tikkie.person.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CorrelationIdTest {

    private static final UUID GENERATED = UUID.fromString("7d0f2c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
    private static final Supplier<UUID> IDS = () -> GENERATED;

    @ParameterizedTest
    @ValueSource(strings = {"req-123", "a.b_c-D", "0"})
    void keepsAWellFormedClientValue(String raw) {
        assertThat(CorrelationId.fromHeader(raw, IDS).value()).isEqualTo(raw);
    }

    @Test
    void keepsAValueOfExactlySixtyFourCharacters() {
        String longest = "a".repeat(64);

        assertThat(CorrelationId.fromHeader(longest, IDS).value()).isEqualTo(longest);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a\nb", "a\rb", "<x>", "a b", "\"}", "ä", " req-123"})
    void generatesANewValueForAMissingOrUnsafeClientValue(String raw) {
        assertThat(CorrelationId.fromHeader(raw, IDS).value()).isEqualTo(GENERATED.toString());
    }

    @Test
    void generatesANewValueForSixtyFiveCharacters() {
        assertThat(CorrelationId.fromHeader("a".repeat(65), IDS).value()).isEqualTo(GENERATED.toString());
    }

    @Test
    void rejectsAnUnsafeValueInTheConstructor() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CorrelationId("a\nb"));
    }
}
