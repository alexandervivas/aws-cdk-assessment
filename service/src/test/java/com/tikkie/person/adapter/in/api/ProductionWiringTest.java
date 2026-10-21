package com.tikkie.person.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkie.person.StandardOutput;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.metrics.model.MetricUnit;

class ProductionWiringTest {

    private static final Map<String, String> ENVIRONMENT = Map.of(
            "AWS_REGION", "eu-west-1",
            "PERSONS_TABLE", "sentinel-persons",
            "IDEMPOTENCY_KEYS_TABLE", "sentinel-keys",
            "POWERTOOLS_SERVICE_NAME", "sentinel-service");

    @Test
    void wiresAUseCaseFromTheEnvironment() {
        try (DynamoDbClient client = ProductionWiring.dynamoDbClient(ENVIRONMENT::get)) {
            assertThat(ProductionWiring.useCase(ENVIRONMENT::get, client)).isNotNull();
        }
    }

    @Test
    void missingVariableIsNamedWithoutAnyValue() {
        for (String name : ENVIRONMENT.keySet()) {
            Map<String, String> incomplete = new HashMap<>(ENVIRONMENT);
            incomplete.put(name, " ");

            assertThatThrownBy(() -> wire(incomplete::get))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name)
                    .hasMessageNotContaining("sentinel")
                    .hasMessageNotContaining("eu-west-1");
        }
    }

    @Test
    void handlerWithoutConfigurationFailsFast() {
        assertThatThrownBy(CreatePersonHandler::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Missing required environment variable");
    }

    // The worst case is a call started just before the 6 s budget ends, plus its 2 s call timeout.
    @Test
    void dynamoDbCallsHaveABoundedLatencyBudget() {
        try (DynamoDbClient client = ProductionWiring.dynamoDbClient(ENVIRONMENT::get)) {
            ClientOverrideConfiguration configuration = client.serviceClientConfiguration().overrideConfiguration();

            assertThat(configuration.apiCallTimeout()).contains(Duration.ofSeconds(2));
            assertThat(configuration.apiCallAttemptTimeout()).contains(Duration.ofSeconds(1));
            assertThat(configuration.retryStrategy()).get()
                    .satisfies(strategy -> assertThat(strategy.maxAttempts()).isEqualTo(2));
            assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.EU_WEST_1);
        }
        assertThat(ProductionWiring.REQUEST_BUDGET).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void metricsCarryTheServiceNameTheLogsUse() throws Exception {
        assumeTrue("Local".equals(System.getenv("AWS_EMF_ENVIRONMENT")), "EMF must write to stdout");
        Metrics metrics = ProductionWiring.metrics(ENVIRONMENT::get);

        String output = StandardOutput.capture(() -> {
            metrics.addMetric("WiringProbe", 1, MetricUnit.COUNT);
            metrics.flush();
            return null;
        }).output();

        JsonNode line = new ObjectMapper().readTree(output.lines().filter(text -> text.contains("WiringProbe"))
                .findFirst().orElseThrow());
        assertThat(line.get("Service").asText()).isEqualTo("sentinel-service");
        assertThat(line.at("/_aws/CloudWatchMetrics/0/Namespace").asText()).isEqualTo("PersonService");
    }

    private static void wire(Function<String, String> environment) {
        try (DynamoDbClient client = ProductionWiring.dynamoDbClient(environment)) {
            ProductionWiring.useCase(environment, client);
            ProductionWiring.metrics(environment);
        }
    }
}
