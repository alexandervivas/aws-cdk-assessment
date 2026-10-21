package com.tikkie.person.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkie.person.domain.FieldError;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemDetailsTest {

    private static final CorrelationId CORRELATION_ID = new CorrelationId("req-123");
    private static final ObjectMapper READER = new ObjectMapper();

    @Test
    void validationErrorListsEveryFieldError() throws Exception {
        List<FieldError> errors = List.of(
                new FieldError("firstName", "required", "must not be blank"),
                new FieldError("address.postalCode", "invalid_format", "must look like 1234 AB"));

        JsonNode json = READER.readTree(Json.write(ProblemDetails.validationError(errors, CORRELATION_ID)));

        assertThat(json.get("type").asText()).isEqualTo("/problems/validation-error");
        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("correlationId").asText()).isEqualTo("req-123");
        assertThat(json.get("errors")).hasSize(2);
        assertThat(json.get("errors").get(1).get("field").asText()).isEqualTo("address.postalCode");
        assertThat(json.get("errors").get(1).get("code").asText()).isEqualTo("invalid_format");
        assertThat(json.get("errors").get(1).get("message").asText()).isEqualTo("must look like 1234 AB");
    }

    @Test
    void otherProblemsOmitTheErrorsMember() throws Exception {
        JsonNode json = READER.readTree(Json.write(ProblemDetails.internalError(CORRELATION_ID)));

        assertThat(json.has("errors")).isFalse();
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "correlationId");
    }

    @Test
    void eachProblemHasAStableTypeAndStatus() {
        assertThat(ProblemDetails.malformedRequest(CORRELATION_ID))
                .extracting(ProblemDetails::type, ProblemDetails::status)
                .containsExactly("/problems/malformed-request", 400);
        assertThat(ProblemDetails.idempotencyKeyReused(CORRELATION_ID))
                .extracting(ProblemDetails::type, ProblemDetails::status)
                .containsExactly("/problems/idempotency-key-reused", 422);
        assertThat(ProblemDetails.internalError(CORRELATION_ID))
                .extracting(ProblemDetails::type, ProblemDetails::status)
                .containsExactly("/problems/internal-error", 500);
        assertThat(ProblemDetails.unsupportedMediaType(CORRELATION_ID))
                .extracting(ProblemDetails::type, ProblemDetails::status)
                .containsExactly("/problems/unsupported-media-type", 415);
    }
}
