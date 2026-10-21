package com.tikkie.person.adapter.in.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.tikkie.person.application.CallerId;
import com.tikkie.person.application.CreatePersonCommand;
import com.tikkie.person.application.CreatePersonResult;
import com.tikkie.person.application.CreatePersonUseCase;
import com.tikkie.person.application.IdempotencyConflictException;
import com.tikkie.person.application.IdempotencyKey;
import com.tikkie.person.domain.UnvalidatedPerson;
import com.tikkie.person.domain.ValidationException;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class CreatePersonHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String JSON = "application/json";

    private final CreatePersonUseCase useCase;

    public CreatePersonHandler(CreatePersonUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            CorrelationId correlationId =
                    CorrelationId.fromHeader(header(request, CORRELATION_ID_HEADER), UUID::randomUUID);
            return handle(request, correlationId);
        } catch (RuntimeException e) {
            return problem(ProblemDetails.internalError(new CorrelationId(UUID.randomUUID().toString())));
        }
    }

    private APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent request, CorrelationId correlationId) {
        try {
            requireJsonContentType(request);
            CreatePersonCommand command = new CreatePersonCommand(
                    CallerId.of(callerArn(request)),
                    IdempotencyKey.parse(header(request, IDEMPOTENCY_KEY_HEADER)),
                    readPerson(request),
                    correlationId.value());
            return created(useCase.create(command), correlationId);
        } catch (UnsupportedMediaTypeException e) {
            return problem(ProblemDetails.unsupportedMediaType(correlationId));
        } catch (MalformedRequestException e) {
            return problem(ProblemDetails.malformedRequest(correlationId));
        } catch (ValidationException e) {
            return problem(ProblemDetails.validationError(e.errors(), correlationId));
        } catch (IdempotencyConflictException e) {
            return problem(ProblemDetails.idempotencyKeyReused(correlationId));
        } catch (RuntimeException e) {
            return problem(ProblemDetails.internalError(correlationId));
        }
    }

    private static void requireJsonContentType(APIGatewayProxyRequestEvent request) {
        String contentType = header(request, CONTENT_TYPE_HEADER);
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].strip();
        if (!JSON.equalsIgnoreCase(mediaType)) {
            throw new UnsupportedMediaTypeException();
        }
    }

    // IAM auth on the API guarantees a principal, so a missing one is a deployment fault: CallerId.of rejects it as a 500.
    private static String callerArn(APIGatewayProxyRequestEvent request) {
        ProxyRequestContext context = request.getRequestContext();
        if (context == null || context.getIdentity() == null) {
            return null;
        }
        return context.getIdentity().getUserArn();
    }

    private static UnvalidatedPerson readPerson(APIGatewayProxyRequestEvent request) {
        String body = request.getBody();
        if (body == null) {
            throw new MalformedRequestException();
        }
        CreatePersonRequest parsed;
        try {
            parsed = Boolean.TRUE.equals(request.getIsBase64Encoded())
                    ? Json.MAPPER.readValue(decodeBase64(body), CreatePersonRequest.class)
                    : Json.MAPPER.readValue(body, CreatePersonRequest.class);
        } catch (IOException e) {
            // Jackson messages quote the offending input, so the cause is dropped rather than passed on.
            throw new MalformedRequestException();
        }
        if (parsed == null) {
            throw new MalformedRequestException();
        }
        return parsed.toUnvalidated();
    }

    private static byte[] decodeBase64(String body) {
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException();
        }
    }

    private static String header(APIGatewayProxyRequestEvent request, String name) {
        String value = find(request.getHeaders(), name, Function.identity());
        if (value != null) {
            return value;
        }
        return find(request.getMultiValueHeaders(), name, CreatePersonHandler::lastValue);
    }

    // Exact casing first, then other casings in sorted order, so a request always resolves to the same value.
    private static <V> String find(Map<String, V> headers, String name, Function<V, String> valueOf) {
        if (headers == null) {
            return null;
        }
        String exact = valueOf.apply(headers.get(name));
        if (exact != null) {
            return exact;
        }
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> valueOf.apply(entry.getValue()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // API Gateway also keeps the last value when it flattens repeated headers.
    private static String lastValue(List<String> values) {
        return values == null || values.isEmpty() ? null : values.getLast();
    }

    private static APIGatewayProxyResponseEvent created(CreatePersonResult result, CorrelationId correlationId) {
        Map<String, String> headers = baseHeaders(JSON, correlationId.value());
        headers.put("Location", "/person/" + result.personId());
        if (result instanceof CreatePersonResult.Replayed) {
            headers.put("Idempotent-Replayed", "true");
        }
        return response(201, headers, Json.write(PersonResponse.from(result)));
    }

    private static APIGatewayProxyResponseEvent problem(ProblemDetails problem) {
        Map<String, String> headers = baseHeaders("application/problem+json", problem.correlationId());
        return response(problem.status(), headers, Json.write(problem));
    }

    private static Map<String, String> baseHeaders(String contentType, String correlationId) {
        Map<String, String> headers = new HashMap<>();
        headers.put(CONTENT_TYPE_HEADER, contentType);
        headers.put(CORRELATION_ID_HEADER, correlationId);
        return headers;
    }

    private static APIGatewayProxyResponseEvent response(int status, Map<String, String> headers, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(status).withHeaders(headers).withBody(body);
    }

    // Exists only to map an unsupported Content-Type to 415.
    private static final class UnsupportedMediaTypeException extends RuntimeException {
    }

    // Exists only to map an unreadable body to 400 without carrying any of the input.
    private static final class MalformedRequestException extends RuntimeException {
    }
}
