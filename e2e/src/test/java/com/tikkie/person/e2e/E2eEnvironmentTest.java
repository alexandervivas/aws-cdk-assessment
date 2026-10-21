package com.tikkie.person.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import software.amazon.awssdk.regions.Region;

class E2eEnvironmentTest {

    private static final String API_URL = "https://example.execute-api.eu-west-1.amazonaws.com/dev/";
    private static final String QUEUE_URL = "https://sqs.eu-west-1.amazonaws.com/e2e-queue";

    @Test
    void skipsWhenNothingIsConfigured() {
        assertThatThrownBy(() -> E2eEnvironment.requireDeployedService(Map.of()))
                .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void skipsWhenTheQueueUrlIsMissing() {
        assertThatThrownBy(() -> E2eEnvironment.requireDeployedService(Map.of("E2E_API_URL", API_URL)))
                .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void skipsWhenTheApiUrlIsBlank() {
        assertThatThrownBy(() -> E2eEnvironment.requireDeployedService(
                Map.of("E2E_API_URL", " ", "E2E_QUEUE_URL", QUEUE_URL)))
                .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void failsInsteadOfSkippingWhenConfiguredWithoutARegion() {
        assertThatThrownBy(() -> E2eEnvironment.requireDeployedService(
                Map.of("E2E_API_URL", API_URL, "E2E_QUEUE_URL", QUEUE_URL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_REGION");
    }

    @Test
    void targetsThePersonResourceOfTheStageUrl() {
        E2eEnvironment environment = E2eEnvironment.requireDeployedService(
                Map.of("E2E_API_URL", API_URL, "E2E_QUEUE_URL", QUEUE_URL, "AWS_REGION", "eu-west-1"));

        assertThat(environment.personUri())
                .isEqualTo(URI.create("https://example.execute-api.eu-west-1.amazonaws.com/dev/person"));
        assertThat(environment.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(environment.region()).isEqualTo(Region.EU_WEST_1);
    }

    @Test
    void acceptsAStageUrlWithoutATrailingSlash() {
        E2eEnvironment environment = E2eEnvironment.requireDeployedService(Map.of(
                "E2E_API_URL", "https://example.execute-api.eu-west-1.amazonaws.com/dev",
                "E2E_QUEUE_URL", QUEUE_URL, "AWS_REGION", "eu-west-1"));

        assertThat(environment.personUri())
                .isEqualTo(URI.create("https://example.execute-api.eu-west-1.amazonaws.com/dev/person"));
    }
}
