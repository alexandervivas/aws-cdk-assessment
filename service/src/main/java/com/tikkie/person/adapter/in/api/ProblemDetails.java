package com.tikkie.person.adapter.in.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tikkie.person.domain.FieldError;
import java.util.List;

// RFC 9457. Every detail is fixed text: nothing from the request or from a caught exception reaches the client.
record ProblemDetails(
        String type,
        String title,
        int status,
        String detail,
        String correlationId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> errors) {

    static ProblemDetails validationError(List<FieldError> errors, CorrelationId correlationId) {
        return new ProblemDetails("/problems/validation-error", "Validation failed", 400,
                "One or more fields are invalid.", correlationId.value(), List.copyOf(errors));
    }

    static ProblemDetails malformedRequest(CorrelationId correlationId) {
        return new ProblemDetails("/problems/malformed-request", "Malformed request", 400,
                "The request body must be a JSON object with only the documented person fields.",
                correlationId.value(), null);
    }

    static ProblemDetails unsupportedMediaType(CorrelationId correlationId) {
        return new ProblemDetails("/problems/unsupported-media-type", "Unsupported media type", 415,
                "The request body must be sent with Content-Type application/json.", correlationId.value(), null);
    }

    static ProblemDetails idempotencyKeyReused(CorrelationId correlationId) {
        return new ProblemDetails("/problems/idempotency-key-reused", "Idempotency key reused", 422,
                "The Idempotency-Key was already used with a different request.", correlationId.value(), null);
    }

    static ProblemDetails internalError(CorrelationId correlationId) {
        return new ProblemDetails("/problems/internal-error", "Internal error", 500,
                "An unexpected error occurred. Quote the correlation ID when reporting it.",
                correlationId.value(), null);
    }
}
