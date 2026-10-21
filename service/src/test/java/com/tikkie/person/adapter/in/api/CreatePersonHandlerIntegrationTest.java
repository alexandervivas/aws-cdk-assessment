package com.tikkie.person.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.RequestIdentity;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkie.person.StandardOutput;
import com.tikkie.person.StandardOutput.Captured;
import com.tikkie.person.adapter.out.dynamodb.DynamoDbLocal;
import com.tikkie.person.adapter.out.dynamodb.DynamoDbPersonRepository;
import com.tikkie.person.application.CreatePersonUseCase;
import com.tikkie.person.application.Sha256RequestFingerprinter;
import com.tikkie.person.domain.PersonId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.lambda.powertools.logging.log4j.internal.Log4jLoggingManager;

// Logs and EMF metrics are read from stdout. EMF writes there only when AWS_EMF_ENVIRONMENT=Local, which surefire
// sets; anywhere else it looks for a CloudWatch agent and the metric assertions would have nothing to read.
@ResourceLock(Resources.SYSTEM_OUT)
class CreatePersonHandlerIntegrationTest {

    private static final String CALLER_ARN = "arn:aws:sts::111122223333:assumed-role/person-api-client/session-1";
    private static final String BODY = """
            {"firstName":"Anna","lastName":"de Vries","phoneNumber":"+31 6 12345678",
             "address":{"street":"Damrak","houseNumber":"1","postalCode":"1012 LG",
                        "city":"Amsterdam","countryCode":"NL"}}
            """;
    private static final List<String> PERSONAL_DATA = List.of("Anna", "de Vries", "+31 6 12345678",
            "+31612345678", "Damrak", "Rokin", "1012 LG", "1012LG", "Amsterdam");
    private static final List<String> GUARDED_LOGGERS =
            List.of("software.amazon.awssdk", "org.apache.http", "software.amazon.cloudwatchlogs.emf");
    private static final ObjectMapper READER = new ObjectMapper();

    private static DynamoDbClient client;
    private static DynamoDbLocal.Tables tables;
    private static CreatePersonHandler handler;

    @BeforeAll
    static void wireTheRealHandler() {
        assumeTrue("Local".equals(System.getenv("AWS_EMF_ENVIRONMENT")), "EMF metrics must go to stdout");
        client = DynamoDbLocal.client();
        tables = DynamoDbLocal.createTables(client);
        handler = handlerOver(client);
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void createReplayConflictAndValidationThroughTheRealHandler() throws Exception {
        String key = UUID.randomUUID().toString();

        APIGatewayProxyResponseEvent created = handle(key, BODY);
        APIGatewayProxyResponseEvent replayed = handle(key, BODY);
        Captured<APIGatewayProxyResponseEvent> conflict =
                StandardOutput.capture(() -> handle(key, BODY.replace("Damrak", "Rokin")));
        APIGatewayProxyResponseEvent invalid = handle(UUID.randomUUID().toString(), BODY.replace("1012 LG", "1012"));

        assertThat(created.getStatusCode()).isEqualTo(201);
        assertThat(replayed.getStatusCode()).isEqualTo(201);
        assertThat(replayed.getHeaders()).containsEntry("Idempotent-Replayed", "true")
                .containsEntry("Location", created.getHeaders().get("Location"));
        assertThat(replayed.getBody()).isEqualTo(created.getBody());
        assertThat(conflict.result().getStatusCode()).isEqualTo(422);
        assertThat(requestLine(conflict.output()).get("outcome").asText()).isEqualTo("conflict");
        assertThat(PERSONAL_DATA).noneMatch(conflict.output()::contains);
        assertThat(invalid.getStatusCode()).isEqualTo(400);
    }

    @Test
    void createLogsOneStructuredLineWithoutPersonalData() throws Exception {
        Captured<APIGatewayProxyResponseEvent> create =
                StandardOutput.capture(() -> handle(UUID.randomUUID().toString(), BODY));

        String personId = READER.readTree(create.result().getBody()).get("personId").asText();
        JsonNode line = requestLine(create.output());
        assertThat(line.get("outcome").asText()).isEqualTo("created");
        assertThat(line.get("correlationId").asText()).isEqualTo("integration-req-1");
        assertThat(line.get("personId").asText()).isEqualTo(personId);
        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(PERSONAL_DATA).noneMatch(create.output()::contains);
    }

    @Test
    void requestLineCarriesTheColdStartFlagThatSnapStartAlwaysReportsAsFalse() throws Exception {
        String output = StandardOutput.capture(() -> handle(UUID.randomUUID().toString(), BODY)).output();

        assertThat(requestLine(output).get("cold_start").isBoolean()).isTrue();
    }

    @Test
    void outcomesAreCountedAsEmbeddedMetrics() throws Exception {
        String key = UUID.randomUUID().toString();

        String create = StandardOutput.capture(() -> handle(key, BODY)).output();
        String replay = StandardOutput.capture(() -> handle(key, BODY)).output();
        String invalid = StandardOutput.capture(() -> handle(key, BODY.replace("NL", "XX"))).output();

        assertThat(metricNames(create)).contains("PersonCreated");
        assertThat(metricNames(replay)).contains("IdempotentReplay");
        assertThat(metricNames(invalid)).contains("ValidationFailed");
        assertThat(requestLine(replay).get("outcome").asText()).isEqualTo("replayed");
        assertThat(requestLine(invalid).get("outcome").asText()).isEqualTo("validation_failed");
        assertThat(PERSONAL_DATA).noneMatch((create + replay + invalid)::contains);
    }

    @Test
    void unexpectedFailureLogsNeitherItsMessageNorAStackTrace() throws Exception {
        CreatePersonHandler failing = handlerOver(new FailingClient());

        Captured<APIGatewayProxyResponseEvent> failure =
                StandardOutput.capture(() -> handle(failing, UUID.randomUUID().toString(), BODY));

        assertThat(failure.result().getStatusCode()).isEqualTo(500);
        assertThat(requestLine(failure.output()).get("outcome").asText()).isEqualTo("error");
        assertThat(metricNames(failure.output())).contains("UnexpectedError");
        assertThat(failure.output()).contains("java.lang.IllegalStateException")
                .doesNotContain("\"stack\"").doesNotContain(".java:");
        assertThat(PERSONAL_DATA).noneMatch(failure.output()::contains);
    }

    @Test
    void sdkLoggersStayQuietWhenPowertoolsRaisesEveryLevelToDebug() {
        new Log4jLoggingManager().setLogLevel(Level.DEBUG);
        String output;
        try {
            output = StandardOutput.capture(() -> {
                GUARDED_LOGGERS.forEach(name -> LoggerFactory.getLogger(name + ".probe").debug("sdk-level-probe"));
                LoggerFactory.getLogger(CreatePersonHandlerIntegrationTest.class).debug("app-level-probe");
                return handle(UUID.randomUUID().toString(), BODY);
            }).output();
        } finally {
            ((LoggerContext) LogManager.getContext(false)).reconfigure();
        }

        assertThat(output).contains("app-level-probe").doesNotContain("sdk-level-probe")
                .doesNotContain("Sending Request");
        assertThat(PERSONAL_DATA).noneMatch(output::contains);
    }

    @Test
    void metricsAreStampedWhenFlushedNotWhenTheHandlerWasBuilt() throws Exception {
        CreatePersonHandler builtEarlier = handlerOver(client);
        Thread.sleep(50);
        long before = System.currentTimeMillis();

        String output = StandardOutput.capture(() -> handle(builtEarlier, UUID.randomUUID().toString(), BODY))
                .output();

        JsonNode metricsLine = jsonLines(output).stream().filter(line -> line.has("_aws")).findFirst().orElseThrow();
        assertThat(metricsLine.at("/_aws/Timestamp").asLong()).isBetween(before, System.currentTimeMillis());
    }

    private static CreatePersonHandler handlerOver(DynamoDbClient dynamoDb) {
        DynamoDbPersonRepository repository = new DynamoDbPersonRepository(dynamoDb, tables.persons(),
                tables.idempotencyKeys(), Clock.systemUTC(), ProductionWiring.REQUEST_BUDGET);
        return new CreatePersonHandler(new CreatePersonUseCase(
                repository, new Sha256RequestFingerprinter(), Clock.systemUTC(), PersonId::random),
                ProductionWiring.metrics(System::getenv));
    }

    private static APIGatewayProxyResponseEvent handle(String idempotencyKey, String body) {
        return handle(handler, idempotencyKey, body);
    }

    private static APIGatewayProxyResponseEvent handle(
            CreatePersonHandler target, String idempotencyKey, String body) {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withBody(body)
                .withHeaders(Map.of("Content-Type", "application/json", "Idempotency-Key", idempotencyKey,
                        "X-Correlation-Id", "integration-req-1"))
                .withRequestContext(new ProxyRequestContext()
                        .withIdentity(new RequestIdentity().withUserArn(CALLER_ARN)));
        return target.handleRequest(request, null);
    }

    private static JsonNode requestLine(String output) throws Exception {
        List<JsonNode> lines = jsonLines(output).stream().filter(line -> line.has("outcome")).toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }

    private static List<String> metricNames(String output) throws Exception {
        List<String> names = new ArrayList<>();
        for (JsonNode line : jsonLines(output)) {
            JsonNode directive = line.at("/_aws/CloudWatchMetrics/0");
            if (!directive.isMissingNode()) {
                assertThat(directive.get("Namespace").asText()).isEqualTo("PersonService");
                directive.get("Metrics").forEach(metric -> names.add(metric.get("Name").asText()));
            }
        }
        return names;
    }

    private static List<JsonNode> jsonLines(String output) throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : output.lines().filter(text -> text.startsWith("{")).toList()) {
            lines.add(READER.readTree(line));
        }
        return lines;
    }

    private static final class FailingClient implements DynamoDbClient {

        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            throw new IllegalStateException("could not store Anna de Vries",
                    new IllegalArgumentException("Damrak 1, 1012 LG Amsterdam"));
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }
    }
}
