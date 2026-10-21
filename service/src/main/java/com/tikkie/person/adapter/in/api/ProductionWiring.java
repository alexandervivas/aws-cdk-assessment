package com.tikkie.person.adapter.in.api;

import com.tikkie.person.adapter.out.dynamodb.DynamoDbPersonRepository;
import com.tikkie.person.application.CreatePersonUseCase;
import com.tikkie.person.application.Sha256RequestFingerprinter;
import com.tikkie.person.domain.PersonId;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.metrics.MetricsBuilder;

final class ProductionWiring {

    // No call starts after 6 s and none lasts beyond its 2 s timeout, so a request ends within about 8 s:
    // below the 10 s Lambda timeout, which is below API Gateway's 29 s.
    static final Duration REQUEST_BUDGET = Duration.ofSeconds(6);
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_ATTEMPTS = 2;
    private static final String METRICS_NAMESPACE = "PersonService";

    private ProductionWiring() {
    }

    static CreatePersonUseCase useCase(Function<String, String> environment, DynamoDbClient client) {
        String personsTable = required(environment, "PERSONS_TABLE");
        String idempotencyKeysTable = required(environment, "IDEMPOTENCY_KEYS_TABLE");
        Clock clock = Clock.systemUTC();
        DynamoDbPersonRepository repository = new DynamoDbPersonRepository(
                client, personsTable, idempotencyKeysTable, clock, REQUEST_BUDGET);
        return new CreatePersonUseCase(repository, new Sha256RequestFingerprinter(), clock, PersonId::random);
    }

    // The SDK defaults (more attempts, long throttling backoff, no call timeout) can outlast API Gateway's 29 s.
    static DynamoDbClient dynamoDbClient(Function<String, String> environment) {
        return DynamoDbClient.builder()
                .region(Region.of(required(environment, "AWS_REGION")))
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .retryStrategy(AwsRetryStrategy.standardRetryStrategy().toBuilder()
                                .maxAttempts(MAX_ATTEMPTS)
                                .build()))
                .build();
    }

    // Powertools logging reads the same variable, so log lines and metrics name one service.
    static Metrics metrics(Function<String, String> environment) {
        return MetricsBuilder.builder()
                .withNamespace(METRICS_NAMESPACE)
                .withService(required(environment, "POWERTOOLS_SERVICE_NAME"))
                .build();
    }

    private static String required(Function<String, String> environment, String name) {
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }
}
