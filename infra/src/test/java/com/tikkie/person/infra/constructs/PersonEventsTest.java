package com.tikkie.person.infra.constructs;

import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.infra.CdkTestApps;
import com.tikkie.person.infra.EnvironmentConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.logs.RetentionDays;

class PersonEventsTest {

    private static final String PIPE = "AWS::Pipes::Pipe";
    private static final String QUEUE = "AWS::SQS::Queue";
    private static final String LOG_GROUP = "AWS::Logs::LogGroup";
    private static final Map<RetentionDays, Integer> RETENTION_IN_DAYS =
            Map.of(RetentionDays.ONE_WEEK, 7, RetentionDays.THREE_MONTHS, 90);
    private static final Pattern PLACEHOLDER = Pattern.compile("<\\$\\.([^>]+)>");
    private static final Set<String> STREAM_FIELDS_IN_EVENT = Set.of(
            "eventID", "dynamodb.NewImage.createdAt.S", "dynamodb.NewImage.correlationId.S",
            "dynamodb.NewImage.personId.S");

    static Stream<EnvironmentConfig> stages() {
        return Stream.of(EnvironmentConfig.dev(), EnvironmentConfig.prod());
    }

    @ParameterizedTest
    @MethodSource("stages")
    void namesTheBusAfterTheStage(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs("AWS::Events::EventBus", 1);
        template.hasResourceProperties("AWS::Events::EventBus", Map.of("Name", "person-events-" + config.stageName()));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void archivesTheServiceEventsOnTheBusForTheConfiguredRetention(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs("AWS::Events::Archive", 1);
        template.hasResourceProperties("AWS::Events::Archive", Map.of(
                "SourceArn", arnOf(onlyLogicalId(template, "AWS::Events::EventBus")),
                "EventPattern", Map.of("source", List.of("tikkie.person-service")),
                "RetentionDays", config.eventArchiveRetention().toDays()));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void keepsFailedRecordsInAKmsEncryptedDeadLetterQueueForFourteenDays(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs(QUEUE, 1);
        template.hasResourceProperties(QUEUE, Map.of(
                "KmsMasterKeyId", arnOf(onlyLogicalId(template, "AWS::KMS::Key")),
                "MessageRetentionPeriod", 14 * 24 * 60 * 60));
        template.hasResource(QUEUE, Map.of(
                "DeletionPolicy", config.removalPolicy() == RemovalPolicy.RETAIN ? "Retain" : "Delete"));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void deniesDeadLetterQueueAccessWithoutTls(EnvironmentConfig config) {
        synth(config).hasResourceProperties("AWS::SQS::QueuePolicy", Map.of("PolicyDocument", Map.of(
                "Statement", Match.arrayWith(List.of(Match.objectLike(Map.of(
                        "Effect", "Deny",
                        "Condition", Map.of("Bool", Map.of("aws:SecureTransport", "false")))))))));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void readsThePersonsStreamFromTheLatestRecord(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs(PIPE, 1);
        template.hasResourceProperties(PIPE, Map.of(
                "Source", streamArn(template),
                "SourceParameters", Map.of("DynamoDBStreamParameters", Map.of(
                        "StartingPosition", "LATEST",
                        "BatchSize", 1,
                        "ParallelizationFactor", 10,
                        "MaximumRetryAttempts", 10,
                        "MaximumRecordAgeInSeconds", 21_600,
                        "DeadLetterConfig", Map.of("Arn", arnOf(onlyLogicalId(template, QUEUE)))))));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void passesOnlyInsertRecords(EnvironmentConfig config) {
        synth(config).hasResourceProperties(PIPE, Map.of("SourceParameters", Map.of(
                "FilterCriteria", Map.of("Filters", List.of(Map.of("Pattern", "{\"eventName\":[\"INSERT\"]}"))))));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void publishesPersonCreatedToTheBusThroughTheThinEventTemplate(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(PIPE, Map.of(
                "Target", arnOf(onlyLogicalId(template, "AWS::Events::EventBus")),
                "TargetParameters", Map.of(
                        "EventBridgeEventBusParameters", Map.of(
                                "Source", "tikkie.person-service",
                                "DetailType", "PersonCreated"),
                        "InputTemplate", PersonEvents.INPUT_TEMPLATE)));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void logsOnlyErrorsWithoutExecutionDataToItsOwnLogGroup(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(PIPE, Map.of("LogConfiguration", Map.of(
                "Level", "ERROR",
                "CloudwatchLogsLogDestination", Map.of("LogGroupArn", arnOf(onlyLogicalId(template, LOG_GROUP))),
                "IncludeExecutionData", Match.absent())));
        template.hasResource(LOG_GROUP, Map.of(
                "Properties", Map.of("RetentionInDays", RETENTION_IN_DAYS.get(config.logRetention())),
                "DeletionPolicy", config.removalPolicy() == RemovalPolicy.RETAIN ? "Retain" : "Delete"));
    }

    @Test
    void pinsTheThinEventTemplate() {
        assertThat(PersonEvents.INPUT_TEMPLATE).isEqualTo(
                "{\"metadata\":{\"eventId\":<$.eventID>,\"eventVersion\":\"1\","
                        + "\"occurredAt\":<$.dynamodb.NewImage.createdAt.S>,"
                        + "\"correlationId\":<$.dynamodb.NewImage.correlationId.S>},"
                        + "\"data\":{\"personId\":<$.dynamodb.NewImage.personId.S>}}");
    }

    @Test
    void templateReadsNoOtherFieldOfTheStreamRecord() {
        List<String> paths = new ArrayList<>();
        Matcher placeholder = PLACEHOLDER.matcher(PersonEvents.INPUT_TEMPLATE);
        while (placeholder.find()) {
            paths.add(placeholder.group(1));
        }

        assertThat(paths).containsExactlyInAnyOrderElementsOf(STREAM_FIELDS_IN_EVENT);
    }

    @ParameterizedTest
    @MethodSource("stages")
    void pipeRoleHoldsExactlyTheGrantedStatements(EnvironmentConfig config) {
        Template template = synth(config);
        String roleId = pipeRoleId(template);

        Map<Object, Set<String>> actionsByResource = actionsByResource(template, roleId);

        assertThat(actionsByResource).isEqualTo(Map.of(
                streamArn(template), Set.of(
                        "dynamodb:ListStreams", "dynamodb:DescribeStream", "dynamodb:GetRecords",
                        "dynamodb:GetShardIterator"),
                arnOf(onlyLogicalId(template, "AWS::Events::EventBus")), Set.of("events:PutEvents"),
                arnOf(onlyLogicalId(template, LOG_GROUP)), Set.of("logs:CreateLogStream", "logs:PutLogEvents"),
                arnOf(onlyLogicalId(template, QUEUE)), Set.of(
                        "sqs:SendMessage", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"),
                arnOf(onlyLogicalId(template, "AWS::KMS::Key")), Set.of(
                        "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*")));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void pipeRoleIsAssumedOnlyByPipesOfThisAccountAndCarriesNoManagedPolicies(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResource("AWS::IAM::Role", Map.of("Properties", Map.of(
                "AssumeRolePolicyDocument", Map.of("Statement", List.of(Map.of(
                        "Action", "sts:AssumeRole",
                        "Effect", "Allow",
                        "Principal", Map.of("Service", "pipes.amazonaws.com"),
                        "Condition", Map.of(
                                "StringEquals", Map.of("aws:SourceAccount", Map.of("Ref", "AWS::AccountId")),
                                "ArnLike", Map.of("aws:SourceArn", Map.of("Fn::Join", List.of("", List.of(
                                        "arn:aws:pipes:eu-west-1:", Map.of("Ref", "AWS::AccountId"), ":pipe/*")))))))),
                "ManagedPolicyArns", Match.absent())));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void createsThePipeOnlyAfterItsRoleCanReadTheStream(EnvironmentConfig config) {
        Template template = synth(config);
        String policyId = pipeRolePolicyId(template, pipeRoleId(template));

        template.hasResource(PIPE, Map.of("DependsOn", Match.arrayWith(List.of(policyId))));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void exportsTheBusNameAndArn(EnvironmentConfig config) {
        Template template = synth(config);
        String busId = onlyLogicalId(template, "AWS::Events::EventBus");

        template.hasOutput("*", Map.of("Value", Map.of("Ref", busId)));
        template.hasOutput("*", Map.of("Value", arnOf(busId)));
    }

    private static Template synth(EnvironmentConfig config) {
        Stack stack = new Stack(CdkTestApps.withCdkJsonContext(), "Test", StackProps.builder()
                .env(Environment.builder().region("eu-west-1").build())
                .build());
        new PersonEvents(stack, "Events", config, new PersonStorage(stack, "Storage", config));
        return Template.fromStack(stack);
    }

    // Template.hasResourceProperties matches statements loosely, so asserting that the role holds exactly these
    // statements and nothing more means walking the synthesised JSON by hand.
    @SuppressWarnings("unchecked")
    private static String pipeRoleId(Template template) {
        Map<String, Object> pipe = template.findResources(PIPE).values().iterator().next();
        Map<String, Object> roleArn = (Map<String, Object>) ((Map<String, Object>) pipe.get("Properties")).get("RoleArn");
        return ((List<String>) roleArn.get("Fn::GetAtt")).get(0);
    }

    private static String pipeRolePolicyId(Template template, String roleId) {
        Map<String, Map<String, Object>> policies = template.findResources("AWS::IAM::Policy", Map.of("Properties", Map.of(
                "Roles", List.of(Map.of("Ref", roleId)))));
        assertThat(policies).hasSize(1);
        return policies.keySet().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Set<String>> actionsByResource(Template template, String roleId) {
        Map<String, Object> policy = template.findResources("AWS::IAM::Policy").get(pipeRolePolicyId(template, roleId));
        Map<String, Object> document = (Map<String, Object>) ((Map<String, Object>) policy.get("Properties")).get("PolicyDocument");
        Map<Object, Set<String>> actionsByResource = new HashMap<>();
        for (Map<String, Object> statement : (List<Map<String, Object>>) document.get("Statement")) {
            assertThat(statement).containsEntry("Effect", "Allow").doesNotContainKey("Condition");
            for (Object resource : asList(statement.get("Resource"))) {
                actionsByResource.computeIfAbsent(resource, any -> new TreeSet<>())
                        .addAll(asList(statement.get("Action")).stream().map(Object::toString).toList());
            }
        }
        return actionsByResource;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of(value);
    }

    private static Map<String, Object> streamArn(Template template) {
        return Map.of("Fn::GetAtt", List.of(PersonFunctionTemplates.tableId(template, "personId"), "StreamArn"));
    }

    private static Map<String, Object> arnOf(String logicalId) {
        return Map.of("Fn::GetAtt", List.of(logicalId, "Arn"));
    }

    private static String onlyLogicalId(Template template, String type) {
        return PersonFunctionTemplates.onlyLogicalId(template, type);
    }
}
