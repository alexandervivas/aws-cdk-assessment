package com.tikkie.person.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FreeTextTest {

    @Test
    void stripsLongInternalWhitespaceInLinearTime() {
        String text = "x" + " ".repeat(40_000) + "y";

        String stripped = assertTimeoutPreemptively(Duration.ofMillis(200), () -> FreeText.strip(text));

        assertThat(stripped).isEqualTo(text);
    }

    @Test
    void stripsOnlyTheEdges() {
        assertThat(FreeText.strip("\u00A0\n Damrak 1 \u2028\t")).isEqualTo("Damrak 1");
    }

    @Test
    void stripsNullToEmpty() {
        assertThat(FreeText.strip(null)).isEmpty();
        assertThat(FreeText.strip(" \u3000 ")).isEmpty();
    }
}
