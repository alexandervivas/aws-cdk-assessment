package com.tikkie.person.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreatePersonIT {

    private static final System.Logger LOG = System.getLogger(CreatePersonIT.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EVENT_SCHEMA = Path.of("../docs/events/person-created.v1.json");
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(60);
    private static final String APPLICATION_JSON = "application/json";
    private static final String PROBLEM_JSON = "application/problem+json";

    // Fictional. houseNumber, houseNumberAddition and countryCode are left out of the PII check: values this short
    // can occur by chance in a timestamp, an id or the account id.
    private static final String FIRST_NAME = "Fenna";
    private static final String LAST_NAME = "van Oosterhout";
    private static final String PHONE_NUMBER = "+31 6 12345678";
    private static final String PHONE_NUMBER_E164 = "+31612345678";
    private static final String STREET = "Keizersgracht";
    private static final String POSTAL_CODE = "1015 CJ";
    private static final String CITY = "Amsterdam";
    private static final List<String> PERSONAL_VALUES = List.of(
            FIRST_NAME, LAST_NAME, PHONE_NUMBER, PHONE_NUMBER_E164, STREET, POSTAL_CODE, CITY);

    private static E2eEnvironment environment;
    private static SigV4Client client;
    private static PersonCreatedQueue queue;

    @BeforeAll
    static void requireDeployedService() {
        environment = E2eEnvironment.requireDeployedService(System.getenv());
        client = new SigV4Client(environment.region());
        queue = new PersonCreatedQueue(environment.queueUrl(), environment.region());
    }

    @AfterAll
    static void closeQueue() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void createReturns201WithTheLocationOfTheNewPerson() throws IOException {
        HttpResponse<String> response = post(jsonHeaders(newKey()), person(FIRST_NAME));

        assertThat(response.statusCode()).isEqualTo(201);
        String personId = body(response).path("personId").asText();
        assertThat(UUID.fromString(personId)).isNotNull();
        assertThat(response.headers().firstValue("Location")).hasValue("/person/" + personId);
        assertThat(response.headers().firstValue("Idempotent-Replayed")).isEmpty();
    }

    @Test
    void replayReturnsTheSameBodyMarkedAsReplayed() throws IOException {
        Map<String, String> headers = jsonHeaders(newKey());
        HttpResponse<String> first = post(headers, person(FIRST_NAME));

        HttpResponse<String> replay = post(headers, person(FIRST_NAME));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(body(replay)).isEqualTo(body(first));
    }

    @Test
    void keyReusedWithAnotherPayloadReturns422() throws IOException {
        Map<String, String> headers = jsonHeaders(newKey());
        assertThat(post(headers, person(FIRST_NAME)).statusCode()).isEqualTo(201);

        HttpResponse<String> response = post(headers, person("Maartje"));

        assertProblem(response, 422, "/problems/idempotency-key-reused");
    }

    @Test
    void invalidPhoneNumberReturns400WithFieldErrors() throws IOException {
        HttpResponse<String> response = post(jsonHeaders(newKey()), person(FIRST_NAME, "+31 6 1"));

        assertProblem(response, 400, "/problems/validation-error");
        assertThat(body(response).path("errors").findValuesAsText("field")).contains("phoneNumber");
    }

    @Test
    void missingIdempotencyKeyReturns400() throws IOException {
        HttpResponse<String> response = post(Map.of("Content-Type", APPLICATION_JSON), person(FIRST_NAME));

        assertProblem(response, 400, "/problems/validation-error");
        assertThat(body(response).path("errors").findValuesAsText("field")).contains("Idempotency-Key");
    }

    @Test
    void lowerCaseIdempotencyKeyHeaderIsAccepted() throws IOException {
        HttpResponse<String> response = post(
                Map.of("Content-Type", APPLICATION_JSON, "idempotency-key", newKey()), person(FIRST_NAME));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void jsonWithACharsetParameterIsAccepted() throws IOException {
        HttpResponse<String> response = post(
                Map.of("Content-Type", "application/json; charset=utf-8", "Idempotency-Key", newKey()),
                person(FIRST_NAME));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void plainTextReturns415() throws IOException {
        HttpResponse<String> response = post(
                Map.of("Content-Type", "text/plain", "Idempotency-Key", newKey()), person(FIRST_NAME));

        assertProblem(response, 415, "/problems/unsupported-media-type");
    }

    // ADR 0012 expects 415 from the handler, but API Gateway may apply its own default content type first. The
    // test records what the gateway does and only requires a client error: a created person would be a bug.
    @Test
    void missingContentTypeIsRejected() throws IOException {
        HttpResponse<String> response = post(Map.of("Idempotency-Key", newKey()), person(FIRST_NAME));

        LOG.log(System.Logger.Level.INFO, "POST /person without Content-Type returned {0}", response.statusCode());
        assertThat(response.statusCode()).isBetween(400, 499);
    }

    @Test
    void publishesAThinPersonCreatedEvent() throws IOException {
        HttpResponse<String> response = post(jsonHeaders(newKey()), person(FIRST_NAME));
        assertThat(response.statusCode()).isEqualTo(201);
        String personId = body(response).path("personId").asText();

        String event = queue.awaitEventFor(personId, EVENT_TIMEOUT)
                .orElseThrow(() -> new AssertionError("No PersonCreated within " + EVENT_TIMEOUT));

        assertThat(JSON.readTree(event).at("/detail/data/personId").asText()).isEqualTo(personId);
        assertThat(schemaErrors(event)).isEmpty();
        assertThat(PERSONAL_VALUES).allSatisfy(value -> assertThat(event).doesNotContain(value));
    }

    private static HttpResponse<String> post(Map<String, String> headers, String body) {
        return client.post(environment.personUri(), headers, body);
    }

    private static Map<String, String> jsonHeaders(String idempotencyKey) {
        return Map.of("Content-Type", APPLICATION_JSON, "Idempotency-Key", idempotencyKey);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String person(String firstName) {
        return person(firstName, PHONE_NUMBER);
    }

    private static String person(String firstName, String phoneNumber) {
        return """
                {"firstName":"%s","lastName":"%s","phoneNumber":"%s","address":{"street":"%s","houseNumber":"112",
                 "houseNumberAddition":"B","postalCode":"%s","city":"%s","countryCode":"NL"}}
                """.formatted(firstName, LAST_NAME, phoneNumber, STREET, POSTAL_CODE, CITY);
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String type) throws IOException {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith(PROBLEM_JSON));
        assertThat(body(response).path("type").asText()).isEqualTo(type);
        assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    }

    private static List<String> schemaErrors(String event) throws IOException {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(EVENT_SCHEMA));
        // Draft 2020-12 treats "format" as an annotation unless assertions are switched on.
        return schema.validate(JSON.readTree(event),
                        context -> context.executionConfig(c -> c.formatAssertionsEnabled(true)))
                .stream()
                .map(Error::getMessage)
                .toList();
    }
}
