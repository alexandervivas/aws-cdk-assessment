package com.tikkie.person.adapter.in.api;

import java.util.Optional;

// Counted: the business outcomes, and UnexpectedError because the handler answers every failure itself, so
// Lambda's own Errors metric never fires. Conflicts, unreadable bodies and wrong media types are only logged.
enum Outcome {
    CREATED("created", "PersonCreated"),
    REPLAYED("replayed", "IdempotentReplay"),
    VALIDATION_FAILED("validation_failed", "ValidationFailed"),
    CONFLICT("conflict", null),
    MALFORMED("malformed", null),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", null),
    ERROR("error", "UnexpectedError");

    private final String label;
    private final String metricName;

    Outcome(String label, String metricName) {
        this.label = label;
        this.metricName = metricName;
    }

    String label() {
        return label;
    }

    Optional<String> metricName() {
        return Optional.ofNullable(metricName);
    }
}
