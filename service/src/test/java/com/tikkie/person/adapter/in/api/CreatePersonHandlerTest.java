package com.tikkie.person.adapter.in.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.RequestIdentity;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.Subsegment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkie.person.StandardOutput;
import com.tikkie.person.StandardOutput.Captured;
import com.tikkie.person.application.CallerId;
import com.tikkie.person.application.CreatePersonCommand;
import com.tikkie.person.application.CreatePersonResult;
import com.tikkie.person.application.CreatePersonUseCase;
import com.tikkie.person.application.IdempotencyConflictException;
import com.tikkie.person.application.IdempotencyKey;
import com.tikkie.person.domain.FieldError;
import com.tikkie.person.domain.PersonId;
import com.tikkie.person.domain.UnvalidatedPerson;
import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;
import com.tikkie.person.domain.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.metrics.model.MetricUnit;

class CreatePersonHandlerTest {

    private static final String CALLER_ARN = "arn:aws:sts::111122223333:assumed-role/person-api-client/session-1";
    private static final String KEY = "0b6f5a3e-8c1d-4e2f-9a7b-3c4d5e6f7a8b";
    private static final PersonId PERSON_ID = PersonId.parse("personId", "3f1c2b4a-5d6e-4f70-8a9b-0c1d2e3f4a5b");
    private static final Instant CREATED_AT = Instant.parse("2026-10-21T09:15:30.123Z");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String BODY = """
            {
              "firstName": "Anna",
              "lastName": "de Vries",
              "phoneNumber": "+31 6 12345678",
              "address": {
                "street": "Damrak",
                "houseNumber": "1",
                "houseNumberAddition": "III",
                "postalCode": "1012 LG",
                "city": "Amsterdam",
                "countryCode": "NL"
              }
            }
            """;
    private static final UnvalidatedPerson PERSON = new UnvalidatedPerson("Anna", "de Vries", "+31 6 12345678",
            new UnvalidatedAddress("Damrak", "1", "III", "1012 LG", "Amsterdam", "NL"));
    private static final ObjectMapper READER = new ObjectMapper();

    private final CreatePersonUseCase useCase = mock(CreatePersonUseCase.class);
    private final Metrics metrics = mock(Metrics.class);
    private final CreatePersonHandler handler = new CreatePersonHandler(useCase, metrics);

    @BeforeEach
    void createsByDefault() {
        when(useCase.create(any())).thenReturn(new CreatePersonResult.Created(PERSON_ID, CREATED_AT));
    }

    @Test
    void createdReturns201WithLocationAndBody() throws Exception {
        APIGatewayProxyResponseEvent response = handle(post(BODY));

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getHeaders())
                .containsEntry("Location", "/person/" + PERSON_ID)
                .containsEntry("Content-Type", "application/json")
                .containsEntry("X-Correlation-Id", "req-123")
                .doesNotContainKey("Idempotent-Replayed");
        JsonNode body = READER.readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable().containsExactly("personId", "createdAt");
        assertThat(body.get("personId").asText()).isEqualTo(PERSON_ID.toString());
        assertThat(body.get("createdAt").asText()).isEqualTo("2026-10-21T09:15:30.123Z");
    }

    // Proves only the mapping of a Replayed result; the 3c integration test proves byte equality end to end.
    @Test
    void replayReturnsTheSameBodyMarkedAsReplayed() {
        APIGatewayProxyResponseEvent created = handle(post(BODY));
        when(useCase.create(any())).thenReturn(new CreatePersonResult.Replayed(PERSON_ID, CREATED_AT));

        APIGatewayProxyResponseEvent replayed = handle(post(BODY));

        assertThat(replayed.getStatusCode()).isEqualTo(201);
        assertThat(replayed.getBody()).isEqualTo(created.getBody());
        assertThat(replayed.getHeaders())
                .containsEntry("Idempotent-Replayed", "true")
                .containsEntry("Location", "/person/" + PERSON_ID);
    }

    @Test
    void commandCarriesCallerKeyPersonAndCorrelationId() {
        handle(post(BODY));

        CreatePersonCommand command = capturedCommand();
        assertThat(command.caller()).isEqualTo(CallerId.of(CALLER_ARN));
        assertThat(command.idempotencyKey()).isEqualTo(IdempotencyKey.parse(KEY));
        assertThat(command.correlationId()).isEqualTo("req-123");
        assertThat(command.person()).isEqualTo(PERSON);
    }

    @Test
    void missingAddressIsPassedOnForTheDomainToReport() {
        handle(post("{\"firstName\":\"Anna\"}"));

        assertThat(capturedCommand().person()).isEqualTo(new UnvalidatedPerson("Anna", null, null, null));
    }

    @Test
    void headerNamesAreCaseInsensitive() {
        Map<String, String> headers = Map.of(
                "content-type", "application/json", "idempotency-key", KEY, "x-correlation-id", "req-lower");

        APIGatewayProxyResponseEvent response = handle(request(BODY, headers));

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getHeaders()).containsEntry("X-Correlation-Id", "req-lower");
    }

    @Test
    void headersOnlyInMultiValueHeadersAreRead() {
        APIGatewayProxyRequestEvent request = request(BODY, null).withMultiValueHeaders(Map.of(
                "Content-Type", List.of("application/json"),
                "IDEMPOTENCY-KEY", List.of(KEY),
                "X-Correlation-Id", List.of("req-multi")));

        APIGatewayProxyResponseEvent response = handle(request);

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getHeaders()).containsEntry("X-Correlation-Id", "req-multi");
    }

    @Test
    void singleValueHeadersWinOverMultiValueHeaders() {
        APIGatewayProxyRequestEvent request = request(BODY, Map.of(
                "Content-Type", "application/json", "idempotency-key", KEY, "x-correlation-id", "req-single"))
                .withMultiValueHeaders(Map.of(
                        "Idempotency-Key", List.of("not-a-uuid"), "X-Correlation-Id", List.of("req-multi")));

        APIGatewayProxyResponseEvent response = handle(request);

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getHeaders()).containsEntry("X-Correlation-Id", "req-single");
    }

    @Test
    void canonicalCasingWinsOverOtherCasings() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("idempotency-key", "not-a-uuid");
        headers.put("x-correlation-id", "req-lower");
        headers.put("Content-Type", "application/json");
        headers.put("Idempotency-Key", KEY);
        headers.put("X-Correlation-Id", "req-canonical");

        APIGatewayProxyResponseEvent response = handle(request(BODY, headers));

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getHeaders()).containsEntry("X-Correlation-Id", "req-canonical");
    }

    @Test
    void otherCasingsResolveTheSameWayWhateverTheirOrder() {
        Map<String, String> oneOrder = new LinkedHashMap<>();
        oneOrder.put("Content-Type", "application/json");
        oneOrder.put("x-correlation-id", "req-lower");
        oneOrder.put("X-CORRELATION-ID", "req-upper");
        Map<String, String> otherOrder = new LinkedHashMap<>();
        otherOrder.put("Content-Type", "application/json");
        otherOrder.put("X-CORRELATION-ID", "req-upper");
        otherOrder.put("x-correlation-id", "req-lower");

        String first = handle(request(BODY, oneOrder)).getHeaders().get("X-Correlation-Id");
        String second = handle(request(BODY, otherOrder)).getHeaders().get("X-Correlation-Id");

        assertThat(first).isIn("req-lower", "req-upper").isEqualTo(second);
    }

    @Test
    void multiValueHeadersUseTheLastValue() {
        APIGatewayProxyRequestEvent request = request(BODY, null).withMultiValueHeaders(Map.of(
                "Content-Type", List.of("application/json"),
                "Idempotency-Key", List.of("not-a-uuid", KEY),
                "X-Correlation-Id", List.of("req-first", "req-last")));

        APIGatewayProxyResponseEvent response = handle(request);

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getHeaders()).containsEntry("X-Correlation-Id", "req-last");
    }

    @Test
    void aNullSingleValueCountsAsAbsent() {
        Map<String, String> headers = headers("req-123");
        headers.put("Idempotency-Key", null);
        APIGatewayProxyRequestEvent request = request(BODY, headers)
                .withMultiValueHeaders(Map.of("Idempotency-Key", List.of(KEY)));

        assertThat(handle(request).getStatusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aNullOrEmptyMultiValueListCountsAsAbsent(List<String> values) throws Exception {
        Map<String, String> headers = headers("req-123");
        headers.remove("Idempotency-Key");
        Map<String, List<String>> multiValueHeaders = new HashMap<>();
        multiValueHeaders.put("Idempotency-Key", values);

        JsonNode problem = assertProblem(handle(request(BODY, headers).withMultiValueHeaders(multiValueHeaders)),
                400, "/problems/validation-error");

        assertThat(problem.get("errors").get(0).get("code").asText()).isEqualTo("required");
    }

    @Test
    void generatesACorrelationIdWhenTheClientSendsNone() {
        Map<String, String> headers = headers("req-123");
        headers.remove("X-Correlation-Id");

        APIGatewayProxyResponseEvent response = handle(request(BODY, headers));

        assertThat(response.getHeaders().get("X-Correlation-Id")).matches(UUID_PATTERN);
    }

    @Test
    void replacesAnUnsafeCorrelationIdInsteadOfEchoingIt() {
        APIGatewayProxyResponseEvent response = handle(request(BODY, headers("a\nb")));

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getHeaders().get("X-Correlation-Id")).matches(UUID_PATTERN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "application/json", "Application/JSON", "application/json; charset=utf-8", "application/json;charset=UTF-8"
    })
    void acceptsJsonWhateverItsCaseOrParameters(String contentType) {
        Map<String, String> headers = headers("req-123");
        headers.put("Content-Type", contentType);

        assertThat(handle(request(BODY, headers)).getStatusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"text/plain", "application/xml", "application/jsonx", "application/problem+json"})
    void otherContentTypesAreUnsupported(String contentType) throws Exception {
        Map<String, String> headers = headers("req-123");
        headers.put("Content-Type", contentType);

        assertProblem(handle(request(BODY, headers)), 415, "/problems/unsupported-media-type");
        verifyNoInteractions(useCase);
    }

    @Test
    void contentTypeIsCheckedBeforeTheBody() throws Exception {
        Map<String, String> headers = headers("req-123");
        headers.put("Content-Type", "text/plain");

        assertProblem(handle(request("not json", headers)), 415, "/problems/unsupported-media-type");
    }

    @Test
    void base64EncodedBodyIsDecoded() {
        APIGatewayProxyRequestEvent request = post(Base64.getEncoder().encodeToString(BODY.getBytes(UTF_8)))
                .withIsBase64Encoded(true);

        assertThat(handle(request).getStatusCode()).isEqualTo(201);
        assertThat(capturedCommand().person()).isEqualTo(PERSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "%%%", "e"})
    void invalidBase64IsAMalformedRequest(String body) throws Exception {
        assertProblem(handle(post(body).withIsBase64Encoded(true)), 400, "/problems/malformed-request");
        verifyNoInteractions(useCase);
    }

    @Test
    void base64BodyThatIsNotUtf8IsAMalformedRequest() throws Exception {
        byte[] notUtf8 = {
            '{', '"', 'f', 'i', 'r', 's', 't', 'N', 'a', 'm', 'e', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'
        };
        APIGatewayProxyRequestEvent request = post(Base64.getEncoder().encodeToString(notUtf8))
                .withIsBase64Encoded(true);

        assertProblem(handle(request), 400, "/problems/malformed-request");
        verifyNoInteractions(useCase);
    }

    @Test
    void missingIdempotencyKeyIsAValidationError() throws Exception {
        Map<String, String> headers = headers("req-123");
        headers.remove("Idempotency-Key");

        JsonNode problem = assertProblem(handle(request(BODY, headers)), 400, "/problems/validation-error");

        assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("Idempotency-Key");
        assertThat(problem.get("errors").get(0).get("code").asText()).isEqualTo("required");
        verifyNoInteractions(useCase);
    }

    @Test
    void nonUuidIdempotencyKeyIsAValidationError() throws Exception {
        Map<String, String> headers = headers("req-123");
        headers.put("Idempotency-Key", "not-a-uuid");

        JsonNode problem = assertProblem(handle(request(BODY, headers)), 400, "/problems/validation-error");

        assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("Idempotency-Key");
        assertThat(problem.get("errors").get(0).get("code").asText()).isEqualTo("invalid_format");
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "{\"firstName\": \"Zwanenburg",
        "{\"firstName\": \"Anna\", \"Zwanenburg\": \"x\"}",
        "[\"Zwanenburg\"]",
        "\"Zwanenburg\"",
        "null",
        "{\"firstName\": \"Anna\"} Zwanenburg",
        "{\"firstName\": \"Anna\", \"firstName\": \"Zwanenburg\"}",
        "{\"address\": [\"Zwanenburg\"]}",
        "{\"firstName\": 123}",
        "{\"firstName\": true}",
        "{\"address\": {\"houseNumber\": 1}}"
    })
    void unreadableBodyIsAMalformedRequestWithAGenericDetail(String body) throws Exception {
        APIGatewayProxyResponseEvent response = handle(post(body));

        JsonNode problem = assertProblem(response, 400, "/problems/malformed-request");
        assertThat(problem.has("errors")).isFalse();
        assertThat(response.getBody()).doesNotContain("Zwanenburg").doesNotContain("Anna");
        verifyNoInteractions(useCase);
    }

    @Test
    void domainValidationErrorsAreListedExactly() throws Exception {
        List<FieldError> errors = List.of(
                new FieldError("firstName", "required", "must not be blank"),
                new FieldError("phoneNumber", "invalid_phone_number", "must be a valid phone number"),
                new FieldError("address.postalCode", "invalid_format", "must look like 1234 AB"));
        when(useCase.create(any())).thenThrow(new ValidationException(errors));

        JsonNode problem = assertProblem(handle(post(BODY)), 400, "/problems/validation-error");

        assertThat(READER.treeToValue(problem.get("errors"), FieldErrorView[].class)).containsExactly(
                new FieldErrorView("firstName", "required", "must not be blank"),
                new FieldErrorView("phoneNumber", "invalid_phone_number", "must be a valid phone number"),
                new FieldErrorView("address.postalCode", "invalid_format", "must look like 1234 AB"));
    }

    @Test
    void reusedKeyWithADifferentPayloadIs422() throws Exception {
        when(useCase.create(any())).thenThrow(new IdempotencyConflictException());

        assertProblem(handle(post(BODY)), 422, "/problems/idempotency-key-reused");
    }

    @Test
    void unexpectedFailureIs500WithoutItsMessage() throws Exception {
        when(useCase.create(any())).thenThrow(new RuntimeException("boom: Anna"));

        APIGatewayProxyResponseEvent response = handle(post(BODY));

        assertProblem(response, 500, "/problems/internal-error");
        assertThat(response.getBody()).doesNotContain("boom").doesNotContain("Anna").contains("req-123");
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void unexpectedFailureLogsOnlyTheClassNamesOfItsCauseChain() throws Exception {
        when(useCase.create(any()))
                .thenThrow(new IllegalStateException("boom: Anna", new IllegalArgumentException("Damrak")));

        String output = StandardOutput.capture(() -> handle(post(BODY))).output();

        JsonNode errorLine = jsonLines(output).stream()
                .filter(line -> line.get("level").asText().equals("ERROR")).findFirst().orElseThrow();
        assertThat(errorLine.get("exception")).containsExactly(
                READER.getNodeFactory().textNode("java.lang.IllegalStateException"),
                READER.getNodeFactory().textNode("java.lang.IllegalArgumentException"));
        assertThat(output).doesNotContain("boom").doesNotContain("Anna").doesNotContain("Damrak");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "none", value = {
        "created, PersonCreated", "replayed, IdempotentReplay", "validation_failed, ValidationFailed",
        "conflict, none", "malformed, none", "unsupported_media_type, none", "error, UnexpectedError"
    })
    @ResourceLock(Resources.SYSTEM_OUT)
    void eachRequestIsLoggedWithItsOutcomeAndCountedWhenItMatters(String outcome, String metric) throws Exception {
        APIGatewayProxyRequestEvent request = requestEndingIn(outcome);

        String output = StandardOutput.capture(() -> handle(request)).output();

        assertThat(jsonLines(output).stream().filter(line -> line.has("outcome")))
                .singleElement().satisfies(line -> assertThat(line.get("outcome").asText()).isEqualTo(outcome));
        if (metric == null) {
            verify(metrics, never()).addMetric(anyString(), anyDouble(), any(MetricUnit.class));
        } else {
            verify(metrics).addMetric(metric, 1, MetricUnit.COUNT);
            verify(metrics).flush();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void aTelemetryFailureStillReturnsTheResponse() throws Exception {
        doThrow(new IllegalStateException("flush failed")).when(metrics).flush();

        Captured<APIGatewayProxyResponseEvent> handled = StandardOutput.capture(() -> handle(post(BODY)));

        assertThat(handled.result().getStatusCode()).isEqualTo(201);
        assertThat(handled.result().getHeaders()).containsEntry("X-Correlation-Id", "req-123");
        assertThat(READER.readTree(handled.result().getBody()).get("personId").asText())
                .isEqualTo(PERSON_ID.toString());
        assertThat(handled.output().lines().filter(line -> line.contains("Telemetry failed")))
                .singleElement().asString()
                .contains("java.lang.IllegalStateException").doesNotContain("flush failed");
    }

    @Test
    void unexpectedFailureMarksTheSubsegmentAsAFaultWithoutItsMessage() {
        when(useCase.create(any())).thenThrow(new IllegalStateException("boom: Anna"));

        Subsegment subsegment = useCaseSubsegment(() -> handle(post(BODY)));

        assertThat(subsegment.isFault()).isTrue();
        assertThat(subsegment.getAnnotations()).containsEntry("exception", "java.lang.IllegalStateException");
        assertThat(subsegment.getCause().getExceptions()).isEmpty();
    }

    @Test
    void rejectedRequestIsNotAFault() {
        when(useCase.create(any())).thenThrow(new IdempotencyConflictException());

        Subsegment subsegment = useCaseSubsegment(() -> handle(post(BODY)));

        assertThat(subsegment.isFault()).isFalse();
        assertThat(subsegment.getAnnotations()).doesNotContainKey("exception");
    }

    @Test
    void illegalArgumentFromBelowIs500() throws Exception {
        when(useCase.create(any())).thenThrow(new IllegalArgumentException("bad: Anna"));

        APIGatewayProxyResponseEvent response = handle(post(BODY));

        assertProblem(response, 500, "/problems/internal-error");
        assertThat(response.getBody()).doesNotContain("bad").doesNotContain("Anna");
    }

    @Test
    void missingCallerIdentityIs500() throws Exception {
        APIGatewayProxyRequestEvent request = post(BODY);
        request.getRequestContext().getIdentity().setUserArn(null);

        assertProblem(handle(request), 500, "/problems/internal-error");
        verifyNoInteractions(useCase);
    }

    @Test
    void missingRequestContextIs500() throws Exception {
        assertProblem(handle(post(BODY).withRequestContext(null)), 500, "/problems/internal-error");
        verifyNoInteractions(useCase);
    }

    @Test
    void failureBeforeTheCorrelationIdIsKnownIs500WithAFreshOne() throws Exception {
        APIGatewayProxyResponseEvent response = handle(null);

        assertProblem(response, 500, "/problems/internal-error");
        assertThat(response.getHeaders().get("X-Correlation-Id")).matches(UUID_PATTERN);
    }

    private JsonNode assertProblem(APIGatewayProxyResponseEvent response, int status, String type) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders())
                .containsEntry("Content-Type", "application/problem+json")
                .containsKey("X-Correlation-Id")
                .doesNotContainKey("Location");
        JsonNode problem = READER.readTree(response.getBody());
        assertThat(problem.get("type").asText()).isEqualTo(type);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asText()).isNotBlank();
        assertThat(problem.get("detail").asText()).isNotBlank();
        assertThat(problem.get("correlationId").asText()).isEqualTo(response.getHeaders().get("X-Correlation-Id"));
        return problem;
    }

    private APIGatewayProxyRequestEvent requestEndingIn(String outcome) {
        switch (outcome) {
            case "replayed" -> when(useCase.create(any()))
                    .thenReturn(new CreatePersonResult.Replayed(PERSON_ID, CREATED_AT));
            case "validation_failed" -> when(useCase.create(any())).thenThrow(new ValidationException(
                    List.of(new FieldError("firstName", "required", "must not be blank"))));
            case "conflict" -> when(useCase.create(any())).thenThrow(new IdempotencyConflictException());
            case "error" -> when(useCase.create(any())).thenThrow(new IllegalStateException("boom"));
            case "malformed" -> {
                return post("[]");
            }
            case "unsupported_media_type" -> {
                return post(BODY).withHeaders(Map.of("Content-Type", "text/plain"));
            }
            default -> {
            }
        }
        return post(BODY);
    }

    private static Subsegment useCaseSubsegment(Runnable action) {
        Segment segment = AWSXRay.beginSegment("handler-test");
        try {
            action.run();
            return segment.getSubsegments().getFirst();
        } finally {
            AWSXRay.clearTraceEntity();
        }
    }

    private static List<JsonNode> jsonLines(String output) throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : output.lines().filter(text -> text.startsWith("{")).toList()) {
            lines.add(READER.readTree(line));
        }
        return lines;
    }

    private CreatePersonCommand capturedCommand() {
        ArgumentCaptor<CreatePersonCommand> command = ArgumentCaptor.forClass(CreatePersonCommand.class);
        verify(useCase).create(command.capture());
        return command.getValue();
    }

    private APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent request) {
        return handler.handleRequest(request, null);
    }

    private static APIGatewayProxyRequestEvent post(String body) {
        return request(body, headers("req-123"));
    }

    private static Map<String, String> headers(String correlationId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Idempotency-Key", KEY);
        headers.put("X-Correlation-Id", correlationId);
        return headers;
    }

    private static APIGatewayProxyRequestEvent request(String body, Map<String, String> headers) {
        return new APIGatewayProxyRequestEvent()
                .withBody(body)
                .withHeaders(headers)
                .withRequestContext(new ProxyRequestContext()
                        .withIdentity(new RequestIdentity().withUserArn(CALLER_ARN)));
    }

    private record FieldErrorView(String field, String code, String message) {
    }
}
