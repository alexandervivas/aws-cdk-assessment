package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class SensitiveTest {

    @Test
    void masksValueInToString() {
        assertThat(Sensitive.of("Jane Example").toString()).isEqualTo("***");
    }

    @Test
    void masksValueInStringConcatenation() {
        String logLine = "value=" + Sensitive.of("Jane Example");

        assertThat(logLine).isEqualTo("value=***").doesNotContain("Jane");
    }

    @Test
    void revealsWrappedValue() {
        assertThat(Sensitive.of("Jane Example").reveal()).isEqualTo("Jane Example");
    }

    @Test
    void equalsAndHashCodeFollowWrappedValue() {
        assertThat(Sensitive.of("a"))
                .isEqualTo(Sensitive.of("a"))
                .hasSameHashCodeAs(Sensitive.of("a"))
                .isNotEqualTo(Sensitive.of("b"));
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> Sensitive.of(null));
    }
}
