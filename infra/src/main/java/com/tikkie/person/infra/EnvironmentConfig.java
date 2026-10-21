package com.tikkie.person.infra;

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
        AlarmThresholds alarmThresholds) {

    public record AlarmThresholds(int lambdaErrors, int apiServerErrors) {
        public AlarmThresholds {
            requireNotNegative(lambdaErrors, "lambdaErrors");
            requireNotNegative(apiServerErrors, "apiServerErrors");
        }
    }

    public EnvironmentConfig {
        Objects.requireNonNull(stageName, "stageName");
        Objects.requireNonNull(logLevel, "logLevel");
        Objects.requireNonNull(logRetention, "logRetention");
        Objects.requireNonNull(removalPolicy, "removalPolicy");
        Objects.requireNonNull(alarmThresholds, "alarmThresholds");
        if (stageName.isBlank() || logLevel.isBlank()) {
            throw new IllegalArgumentException("stageName and logLevel must not be blank");
        }
        if (apiRateLimit <= 0 || apiBurstLimit <= 0) {
            throw new IllegalArgumentException("API throttling limits must be positive");
        }
        if (reservedConcurrency != null && reservedConcurrency <= 0) {
            throw new IllegalArgumentException("reservedConcurrency must be positive when set");
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
                new AlarmThresholds(5, 5));
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
                new AlarmThresholds(1, 1));
    }

    private static void requireNotNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
