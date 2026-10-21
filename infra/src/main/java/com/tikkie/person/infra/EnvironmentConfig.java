package com.tikkie.person.infra;

import java.time.Duration;
import java.util.Objects;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.logs.RetentionDays;

public record EnvironmentConfig(
        String stageName,
        String logLevel,
        RetentionDays logRetention,
        RemovalPolicy removalPolicy,
        boolean deletionProtection,
        boolean terminationProtection,
        boolean pointInTimeRecovery,
        double apiRateLimit,
        int apiBurstLimit,
        Integer reservedConcurrency,
        AlarmThresholds alarmThresholds,
        Duration eventArchiveRetention) {

    // Each alarm fires when its five-minute sum reaches the threshold, so zero would leave it permanently in alarm.
    public record AlarmThresholds(
            int unexpectedErrors, int apiServerErrors, int lambdaErrors, int pipeFailures, int lambdaThrottles) {
        public AlarmThresholds {
            requireAtLeastOne(unexpectedErrors, "unexpectedErrors");
            requireAtLeastOne(apiServerErrors, "apiServerErrors");
            requireAtLeastOne(lambdaErrors, "lambdaErrors");
            requireAtLeastOne(pipeFailures, "pipeFailures");
            requireAtLeastOne(lambdaThrottles, "lambdaThrottles");
        }
    }

    public EnvironmentConfig {
        Objects.requireNonNull(stageName, "stageName");
        Objects.requireNonNull(logLevel, "logLevel");
        Objects.requireNonNull(logRetention, "logRetention");
        Objects.requireNonNull(removalPolicy, "removalPolicy");
        Objects.requireNonNull(alarmThresholds, "alarmThresholds");
        Objects.requireNonNull(eventArchiveRetention, "eventArchiveRetention");
        if (stageName.isBlank() || logLevel.isBlank()) {
            throw new IllegalArgumentException("stageName and logLevel must not be blank");
        }
        if (apiRateLimit <= 0 || apiBurstLimit <= 0) {
            throw new IllegalArgumentException("API throttling limits must be positive");
        }
        if (reservedConcurrency != null && reservedConcurrency <= 0) {
            throw new IllegalArgumentException("reservedConcurrency must be positive when set");
        }
        if (!isPositiveWholeDays(eventArchiveRetention)) {
            throw new IllegalArgumentException("eventArchiveRetention must be a positive number of whole days");
        }
        // A burst the function cannot absorb is throttled by Lambda and reaches clients as an API 5XX.
        if (reservedConcurrency != null && apiBurstLimit > reservedConcurrency) {
            throw new IllegalArgumentException("apiBurstLimit must not exceed reservedConcurrency");
        }
    }

    public static EnvironmentConfig dev() {
        return new EnvironmentConfig(
                "dev",
                "DEBUG",
                RetentionDays.ONE_WEEK,
                RemovalPolicy.DESTROY,
                false,
                false,
                false,
                10,
                20,
                null,
                new AlarmThresholds(5, 5, 1, 1, 1),
                Duration.ofDays(7));
    }

    public static EnvironmentConfig prod() {
        return new EnvironmentConfig(
                "prod",
                "INFO",
                RetentionDays.THREE_MONTHS,
                RemovalPolicy.RETAIN,
                true,
                true,
                true,
                100,
                100,
                100,
                new AlarmThresholds(1, 1, 1, 1, 1),
                Duration.ofDays(30));
    }

    private static boolean isPositiveWholeDays(Duration duration) {
        return duration.isPositive() && duration.equals(Duration.ofDays(duration.toDays()));
    }

    private static void requireAtLeastOne(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
    }
}
