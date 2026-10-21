package com.tikkie.person.infra.constructs;

import com.tikkie.person.infra.EnvironmentConfig;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.apigateway.AccessLogFormat;
import software.amazon.awscdk.services.apigateway.AuthorizationType;
import software.amazon.awscdk.services.apigateway.EndpointType;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.LogGroupLogDestination;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.Model;
import software.amazon.awscdk.services.apigateway.ModelOptions;
import software.amazon.awscdk.services.apigateway.RequestValidator;
import software.amazon.awscdk.services.apigateway.RequestValidatorOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.Stage;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.logs.ILogGroup;
import software.amazon.awscdk.services.logs.LogGroup;
import software.constructs.Construct;

public final class PersonApi extends Construct {

    // Request context only: bodies carry personal data. Access logs cannot read request headers, so the function
    // logs the correlation id instead.
    static final String ACCESS_LOG_FORMAT = """
            {"requestId":"$context.requestId",\
            "extendedRequestId":"$context.extendedRequestId",\
            "ip":"$context.identity.sourceIp",\
            "userArn":"$context.identity.userArn",\
            "requestTime":"$context.requestTime",\
            "httpMethod":"$context.httpMethod",\
            "resourcePath":"$context.resourcePath",\
            "status":"$context.status",\
            "protocol":"$context.protocol",\
            "responseLength":"$context.responseLength",\
            "integrationLatency":"$context.integrationLatency"}""";

    private final LogGroup accessLogGroup;
    private final RestApi restApi;

    public PersonApi(Construct scope, String id, EnvironmentConfig config, IFunction handler) {
        super(scope, id);
        this.accessLogGroup = LogGroup.Builder.create(this, "AccessLogs")
                .retention(config.logRetention())
                .removalPolicy(config.removalPolicy())
                .build();
        this.restApi = restApi(config, accessLogGroup);
        addCreatePersonMethod(restApi, handler);
    }

    public LogGroup accessLogGroup() {
        return accessLogGroup;
    }

    public Stage stage() {
        return restApi.getDeploymentStage();
    }

    private RestApi restApi(EnvironmentConfig config, ILogGroup accessLogs) {
        return RestApi.Builder.create(this, "RestApi")
                .restApiName("person-service-" + config.stageName())
                .description("Person Service")
                .endpointTypes(List.of(EndpointType.REGIONAL))
                // Access logging needs the account-level CloudWatch role. Creating it here lets a fresh account
                // deploy. Retained, because every REST API in the account and region uses it (ADR 0014).
                .cloudWatchRole(true)
                .cloudWatchRoleRemovalPolicy(RemovalPolicy.RETAIN)
                .deployOptions(StageOptions.builder()
                        .stageName(config.stageName())
                        .throttlingRateLimit(config.apiRateLimit())
                        .throttlingBurstLimit(config.apiBurstLimit())
                        .tracingEnabled(true)
                        .dataTraceEnabled(false)
                        .accessLogDestination(new LogGroupLogDestination(accessLogs))
                        .accessLogFormat(AccessLogFormat.custom(ACCESS_LOG_FORMAT))
                        .build())
                .build();
    }

    private static void addCreatePersonMethod(RestApi api, IFunction handler) {
        Model requestModel = api.addModel("CreatePersonRequest", ModelOptions.builder()
                .contentType(PersonRequestModel.CONTENT_TYPE)
                .schema(PersonRequestModel.schema())
                .build());
        // Idempotency-Key presence is checked by the handler alone: it matches the header name case-insensitively and
        // answers with problem details, so the gateway declares no required parameters.
        RequestValidator validator = api.addRequestValidator("Body", RequestValidatorOptions.builder()
                .validateRequestBody(true)
                .validateRequestParameters(false)
                .build());
        LambdaIntegration integration = LambdaIntegration.Builder.create(handler)
                // The console's test invoke would need a second Lambda permission, outside IAM auth and the stage.
                .allowTestInvoke(false)
                .proxy(true)
                .build();
        api.getRoot().addResource("person").addMethod("POST", integration, MethodOptions.builder()
                .authorizationType(AuthorizationType.IAM)
                .requestValidator(validator)
                .requestModels(Map.of(PersonRequestModel.CONTENT_TYPE, requestModel))
                .build());
    }
}
