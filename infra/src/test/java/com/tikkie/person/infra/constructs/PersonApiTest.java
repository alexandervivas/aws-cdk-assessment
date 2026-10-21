package com.tikkie.person.infra.constructs;

import static com.tikkie.person.infra.constructs.PersonFunctionTemplates.onlyLogicalId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.tikkie.person.infra.EnvironmentConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awscdk.CfnElement;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class PersonApiTest {

    private static final String STAGES = "com.tikkie.person.infra.constructs.PersonFunctionTemplates#stages";
    private static final String METHOD = "AWS::ApiGateway::Method";
    private static final String API_STAGE = "AWS::ApiGateway::Stage";

    @TempDir
    static Path stubAsset;

    @ParameterizedTest
    @MethodSource(STAGES)
    void requiresIamAuthAndAnIdempotencyKeyOnPostPerson(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs(METHOD, 1);
        template.hasResourceProperties("AWS::ApiGateway::Resource", Map.of("PathPart", "person"));
        template.hasResourceProperties(METHOD, Map.of(
                "HttpMethod", "POST",
                "AuthorizationType", "AWS_IAM",
                "RequestParameters", Map.of("method.request.header.Idempotency-Key", true),
                "RequestValidatorId", Map.of("Ref", onlyLogicalId(template, "AWS::ApiGateway::RequestValidator"))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void validatesBothBodyAndParameters(EnvironmentConfig config) {
        synth(config).hasResourceProperties("AWS::ApiGateway::RequestValidator", Map.of(
                "ValidateRequestBody", true,
                "ValidateRequestParameters", true));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void mapsTheRequestModelToApplicationJsonOnly(EnvironmentConfig config) {
        Template template = synth(config);
        String modelId = onlyLogicalId(template, "AWS::ApiGateway::Model");

        template.hasResourceProperties("AWS::ApiGateway::Model", Map.of(
                "ContentType", "application/json",
                "Schema", Match.objectLike(Map.of("title", "CreatePersonRequest"))));
        template.hasResourceProperties(METHOD, Map.of(
                "RequestModels", Match.exact(Map.of("application/json", Map.of("Ref", modelId)))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void namesTheApiAfterItsStage(EnvironmentConfig config) {
        synth(config).hasResourceProperties("AWS::ApiGateway::RestApi", Map.of(
                "Name", "person-service-" + config.stageName()));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void throttlesTheStageFromConfig(EnvironmentConfig config) {
        synth(config).hasResourceProperties(API_STAGE, Map.of(
                "StageName", config.stageName(),
                "MethodSettings", List.of(Match.objectLike(Map.of(
                        "HttpMethod", "*",
                        "ResourcePath", "/*",
                        "ThrottlingRateLimit", config.apiRateLimit(),
                        "ThrottlingBurstLimit", config.apiBurstLimit(),
                        "DataTraceEnabled", false)))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void tracesRequestsWithXRay(EnvironmentConfig config) {
        synth(config).hasResourceProperties(API_STAGE, Map.of("TracingEnabled", true));
    }

    static Stream<Arguments> accessLogRetentionPerStage() {
        return Stream.of(
                arguments(EnvironmentConfig.dev(), 7, "Delete"),
                arguments(EnvironmentConfig.prod(), 90, "Retain"));
    }

    @ParameterizedTest
    @MethodSource("accessLogRetentionPerStage")
    void writesAccessLogsToItsOwnLogGroupWithRetention(EnvironmentConfig config, int retentionDays, String deletionPolicy) {
        Stack stack = PersonFunctionTemplates.stack();
        PersonApi api = synthApi(stack, config);
        Template template = Template.fromStack(stack);
        String logGroupId = stack.getLogicalId((CfnElement) api.accessLogGroup().getNode().getDefaultChild());

        template.hasResourceProperties(API_STAGE, Map.of("AccessLogSetting", Match.objectLike(Map.of(
                "DestinationArn", Map.of("Fn::Join", List.of("", List.of(
                        "arn:aws:logs:eu-west-1:",
                        Map.of("Ref", "AWS::AccountId"),
                        ":log-group:",
                        Map.of("Ref", logGroupId))))))));
        assertThat(template.findResources("AWS::Logs::LogGroup")).containsKey(logGroupId);
        template.hasResource("AWS::Logs::LogGroup", Map.of(
                "Properties", Map.of("RetentionInDays", retentionDays),
                "DeletionPolicy", deletionPolicy));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void logsOnlyRequestContextFieldsAsJson(EnvironmentConfig config) {
        synth(config).hasResourceProperties(API_STAGE, Map.of("AccessLogSetting", Match.objectLike(Map.of(
                "Format", Match.serializedJson(Match.exact(Map.ofEntries(
                        Map.entry("requestId", "$context.requestId"),
                        Map.entry("extendedRequestId", "$context.extendedRequestId"),
                        Map.entry("ip", "$context.identity.sourceIp"),
                        Map.entry("userArn", "$context.identity.userArn"),
                        Map.entry("requestTime", "$context.requestTime"),
                        Map.entry("httpMethod", "$context.httpMethod"),
                        Map.entry("resourcePath", "$context.resourcePath"),
                        Map.entry("status", "$context.status"),
                        Map.entry("protocol", "$context.protocol"),
                        Map.entry("responseLength", "$context.responseLength"),
                        Map.entry("integrationLatency", "$context.integrationLatency"))))))));
        assertThat(PersonApi.ACCESS_LOG_FORMAT).doesNotContain("$input", "requestBody", "responseBody", "caller");
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void proxiesToTheLiveAlias(EnvironmentConfig config) {
        Template template = synth(config);
        String aliasId = onlyLogicalId(template, "AWS::Lambda::Alias");

        template.hasResourceProperties(METHOD, Map.of("Integration", Map.of(
                "Type", "AWS_PROXY",
                "IntegrationHttpMethod", "POST",
                "Uri", Map.of("Fn::Join", List.of("", List.of(
                        "arn:aws:apigateway:eu-west-1:lambda:path/2015-03-31/functions/",
                        Map.of("Ref", aliasId),
                        "/invocations"))))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void letsOnlyThisApisPostPersonInvokeTheAlias(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs("AWS::Lambda::Permission", 1);
        template.hasResourceProperties("AWS::Lambda::Permission", Map.of(
                "Action", "lambda:InvokeFunction",
                "Principal", "apigateway.amazonaws.com",
                "FunctionName", Map.of("Ref", onlyLogicalId(template, "AWS::Lambda::Alias")),
                "SourceArn", Map.of("Fn::Join", List.of("", List.of(
                        "arn:aws:execute-api:eu-west-1:",
                        Map.of("Ref", "AWS::AccountId"),
                        ":",
                        Map.of("Ref", onlyLogicalId(template, "AWS::ApiGateway::RestApi")),
                        "/",
                        Map.of("Ref", onlyLogicalId(template, API_STAGE)),
                        "/POST/person")))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void outputsTheApiUrl(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasOutput("*", Map.of("Value", Map.of("Fn::Join", List.of("", List.of(
                "https://",
                Map.of("Ref", onlyLogicalId(template, "AWS::ApiGateway::RestApi")),
                ".execute-api.eu-west-1.",
                Map.of("Ref", "AWS::URLSuffix"),
                "/",
                Map.of("Ref", onlyLogicalId(template, API_STAGE)),
                "/")))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void isARegionalEndpoint(EnvironmentConfig config) {
        synth(config).hasResourceProperties("AWS::ApiGateway::RestApi", Map.of(
                "EndpointConfiguration", Map.of("Types", List.of("REGIONAL"))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void setsUpTheAccountCloudWatchRoleAndKeepsIt(EnvironmentConfig config) {
        Template template = synth(config);
        Map<String, Object> assumedByApiGateway = Map.of("AssumeRolePolicyDocument", Match.objectLike(Map.of(
                "Statement", List.of(Match.objectLike(Map.of(
                        "Principal", Map.of("Service", "apigateway.amazonaws.com")))))));
        Map<String, Map<String, Object>> roles = template.findResources("AWS::IAM::Role", Map.of(
                "Properties", Match.objectLike(assumedByApiGateway)));
        assertThat(roles).hasSize(1);
        String roleId = roles.keySet().iterator().next();

        template.hasResourceProperties("AWS::ApiGateway::Account", Map.of(
                "CloudWatchRoleArn", Map.of("Fn::GetAtt", List.of(roleId, "Arn"))));
        template.hasResource("AWS::IAM::Role", Map.of(
                "DeletionPolicy", "Retain",
                "Properties", Match.objectLike(Map.of("ManagedPolicyArns", List.of(Map.of("Fn::Join", List.of("", List.of(
                        "arn:",
                        Map.of("Ref", "AWS::Partition"),
                        ":iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs"))))))));
    }

    private static Template synth(EnvironmentConfig config) {
        Stack stack = PersonFunctionTemplates.stack();
        synthApi(stack, config);
        return Template.fromStack(stack);
    }

    private static PersonApi synthApi(Stack stack, EnvironmentConfig config) {
        PersonStorage storage = new PersonStorage(stack, "Storage", config);
        PersonFunction function = new PersonFunction(stack, "Function", config, storage,
                PersonFunctionTemplates.stubCode(stubAsset));
        return new PersonApi(stack, "Api", config, function.liveAlias());
    }
}
