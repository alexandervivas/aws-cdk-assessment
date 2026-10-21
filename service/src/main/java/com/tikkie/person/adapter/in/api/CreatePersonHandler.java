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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.powertools.logging.PowertoolsLogging;
import software.amazon.lambda.powertools.logging.argument.StructuredArguments;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.metrics.model.MetricUnit;
import software.amazon.lambda.powertools.tracing.TracingUtils;

public final class CreatePersonHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String JSON = "application/json";

    private static final Logger LOG = LoggerFactory.getLogger(CreatePersonHandler.class);
    private static final String USE_CASE_SUBSEGMENT = "CreatePerson";
    private static final int MAX_LOGGED_CAUSES = 10;

    private final CreatePersonUseCase useCase;
    private final Metrics metrics;

    // The DynamoDB client is built here, at init, so a SnapStart snapshot holds it. Credentials are resolved
    // on the first call, after restore.
    public CreatePersonHandler() {
        this(System::getenv);
    }

    private CreatePersonHandler(Function<String, String> environment) {
        this(ProductionWiring.useCase(environment, ProductionWiring.dynamoDbClient(environment)),
                ProductionWiring.metrics(environment));
    }

    CreatePersonHandler(CreatePersonUseCase useCase, Metrics metrics) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    // The cold_start log field means an on-demand init to Powertools, so under SnapStart it is always false.
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        return PowertoolsLogging.withLogging(context, () -> {
            Handled handled = handleSafely(request);
            observe(handled);
            return handled.response();
        });
    }

    private Handled handleSafely(APIGatewayProxyRequestEvent request) {
        try {
            CorrelationId correlationId =
                    CorrelationId.fromHeader(header(request, CORRELATION_ID_HEADER), UUID::randomUUID);
            return handle(request, correlationId);
        } catch (RuntimeException e) {
            return failed(e, new CorrelationId(UUID.randomUUID().toString()));
        }
    }

    private Handled handle(APIGatewayProxyRequestEvent request, CorrelationId correlationId) {
        try {
            requireJsonContentType(request);
            CreatePersonCommand command = new CreatePersonCommand(
                    CallerId.of(callerArn(request)),
                    IdempotencyKey.parse(header(request, IDEMPOTENCY_KEY_HEADER)),
                    readPerson(request),
                    correlationId.value());
            CreatePersonResult result = createTraced(command);
            Outcome outcome = result instanceof CreatePersonResult.Replayed ? Outcome.REPLAYED : Outcome.CREATED;
            return new Handled(created(result, correlationId), outcome, correlationId.value(),
                    result.personId().toString());
        } catch (UnsupportedMediaTypeException e) {
            return rejected(ProblemDetails.unsupportedMediaType(correlationId), Outcome.UNSUPPORTED_MEDIA_TYPE);
        } catch (MalformedRequestException e) {
            return rejected(ProblemDetails.malformedRequest(correlationId), Outcome.MALFORMED);
        } catch (ValidationException e) {
            return rejected(ProblemDetails.validationError(e.errors(), correlationId), Outcome.VALIDATION_FAILED);
        } catch (IdempotencyConflictException e) {
            return rejected(ProblemDetails.idempotencyKeyReused(correlationId), Outcome.CONFLICT);
        } catch (RuntimeException e) {
            return failed(e, correlationId);
        }
    }

    // Powertools 2.11.0 withSubsegment only takes a Consumer, so the result comes back through a reference.
    // Only an unexpected failure is a fault. It is marked by class name: addException would record the message.
    private CreatePersonResult createTraced(CreatePersonCommand command) {
        AtomicReference<CreatePersonResult> result = new AtomicReference<>();
        TracingUtils.withSubsegment(USE_CASE_SUBSEGMENT, subsegment -> {
            try {
                result.set(useCase.create(command));
            } catch (ValidationException | IdempotencyConflictException e) {
                throw e;
            } catch (RuntimeException e) {
                subsegment.setFault(true);
                subsegment.putAnnotation("exception", e.getClass().getName());
                throw e;
            }
        });
        return result.get();
    }

    // Class names only: messages from below the handler can quote the input, and the JSON layout would
    // print the message and stack trace of an attached exception.
    private static Handled failed(RuntimeException e, CorrelationId correlationId) {
        Telemetry.bestEffort(() -> LOG.error("Create person failed",
                StructuredArguments.entry("correlationId", correlationId.value()),
                StructuredArguments.entry("exception", classNames(e))));
        return rejected(ProblemDetails.internalError(correlationId), Outcome.ERROR);
    }

    // Bounded, because a cause chain can loop back on itself.
    private static List<String> classNames(Throwable e) {
        List<String> names = new ArrayList<>();
        for (Throwable cause = e; cause != null && names.size() < MAX_LOGGED_CAUSES; cause = cause.getCause()) {
            names.add(cause.getClass().getName());
        }
        return names;
    }

    private static Handled rejected(ProblemDetails problem, Outcome outcome) {
        return new Handled(problem(problem), outcome, problem.correlationId(), null);
    }

    private void observe(Handled handled) {
        Telemetry.bestEffort(() -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("correlationId", handled.correlationId());
            fields.put("outcome", handled.outcome().label());
            if (handled.personId() != null) {
                fields.put("personId", handled.personId());
            }
            LOG.info("Create person handled", StructuredArguments.entries(fields));
            handled.outcome().metricName().ifPresent(name -> {
                metrics.addMetric(name, 1, MetricUnit.COUNT);
                metrics.flush();
            });
        });
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

    private record Handled(
            APIGatewayProxyResponseEvent response, Outcome outcome, String correlationId, String personId) {
    }

    // Exists only to map an unsupported Content-Type to 415.
    private static final class UnsupportedMediaTypeException extends RuntimeException {
    }

    // Exists only to map an unreadable body to 400 without carrying any of the input.
    private static final class MalformedRequestException extends RuntimeException {
    }
}
