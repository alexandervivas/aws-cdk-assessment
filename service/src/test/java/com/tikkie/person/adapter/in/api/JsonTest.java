package com.tikkie.person.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    @Test
    void readsAJsonStringIntoATextField() throws Exception {
        assertThat(Json.MAPPER.readValue("{\"text\": \"Damrak\"}", Sample.class)).isEqualTo(new Sample("Damrak"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "-1", "1.5", "1e3", "true", "false"})
    void rejectsANumberOrBooleanInATextField(String scalar) {
        assertThatExceptionOfType(JsonProcessingException.class)
                .isThrownBy(() -> Json.MAPPER.readValue("{\"text\": " + scalar + "}", Sample.class));
    }

    @Test
    void rejectsAnUnknownProperty() {
        assertThatExceptionOfType(JsonProcessingException.class)
                .isThrownBy(() -> Json.MAPPER.readValue("{\"text\": \"a\", \"other\": \"b\"}", Sample.class));
    }

    record Sample(String text) {
    }
}
