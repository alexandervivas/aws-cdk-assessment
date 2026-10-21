package com.tikkie.person.infra.constructs;

import static com.tikkie.person.infra.constructs.PersonFunctionTemplates.onlyLogicalId;

import com.tikkie.person.infra.EnvironmentConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class PersonMonitoringTest {

    private static final String STAGES = "com.tikkie.person.infra.constructs.PersonFunctionTemplates#stages";
    private static final String ALARM = "AWS::CloudWatch::Alarm";
    private static final String TOPIC = "AWS::SNS::Topic";
    private static final int FIVE_MINUTES = 300;
    private static final String AT_LEAST = "GreaterThanOrEqualToThreshold";

    @TempDir
    static Path stubAsset;

    @ParameterizedTest
    @MethodSource(STAGES)
    void alarmsOnTheUnexpectedErrorsTheHandlerCountsForThisStage(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(ALARM, alarm(template, "PersonService", "UnexpectedError",
                List.of(dimension("Service", "person-service-" + config.stageName())),
                "Sum", config.alarmThresholds().unexpectedErrors(), AT_LEAST));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void alarmsOnServerErrorsOfThisStageApiOnly(EnvironmentConfig config) {
        Template template = synth(config);

        String apiStage = onlyLogicalId(template, "AWS::ApiGateway::Stage");

        template.hasResourceProperties(ALARM, alarm(template, "AWS/ApiGateway", "5XXError",
                List.of(dimension("ApiName", "person-service-" + config.stageName()),
                        dimension("Stage", Map.of("Ref", apiStage))),
                "Sum", config.alarmThresholds().apiServerErrors(), AT_LEAST));
        template.hasResourceProperties("AWS::ApiGateway::Stage", Map.of("StageName", config.stageName()));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void alarmsAsSoonAsTheDeadLetterQueueHoldsAMessage(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(ALARM, alarm(template, "AWS/SQS", "ApproximateNumberOfMessagesVisible",
                List.of(dimension("QueueName",
                        Map.of("Fn::GetAtt", List.of(onlyLogicalId(template, "AWS::SQS::Queue"), "QueueName")))),
                "Maximum", 0, "GreaterThanThreshold"));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void alarmsOnFailedPipeExecutions(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(ALARM, alarm(template, "AWS/EventBridge/Pipes", "ExecutionFailed",
                List.of(dimension("PipeName", Map.of("Ref", onlyLogicalId(template, "AWS::Pipes::Pipe")))),
                "Sum", config.alarmThresholds().pipeFailures(), AT_LEAST));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void alarmsOnFunctionErrorsTheHandlerCannotCatch(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(ALARM, alarm(template, "AWS/Lambda", "Errors",
                List.of(dimension("FunctionName", Map.of("Ref", onlyLogicalId(template, "AWS::Lambda::Function")))),
                "Sum", config.alarmThresholds().lambdaErrors(), AT_LEAST));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void alarmsOnThrottledInvocationsOfTheFunction(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(ALARM, alarm(template, "AWS/Lambda", "Throttles",
                List.of(dimension("FunctionName", Map.of("Ref", onlyLogicalId(template, "AWS::Lambda::Function")))),
                "Sum", config.alarmThresholds().lambdaThrottles(), AT_LEAST));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void hasNoOtherAlarms(EnvironmentConfig config) {
        synth(config).resourceCountIs(ALARM, 6);
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void notifiesATopicEncryptedWithTheStageKeyAndWithoutSubscriptions(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs(TOPIC, 1);
        template.hasResourceProperties(TOPIC, Map.of(
                "KmsMasterKeyId", Map.of("Fn::GetAtt", List.of(onlyLogicalId(template, "AWS::KMS::Key"), "Arn"))));
        template.resourceCountIs("AWS::SNS::Subscription", 0);
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void deniesPublishingToTheTopicWithoutTls(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties("AWS::SNS::TopicPolicy", Map.of(
                "Topics", List.of(Map.of("Ref", onlyLogicalId(template, TOPIC))),
                "PolicyDocument", Map.of("Statement", Match.arrayWith(List.of(Match.objectLike(Map.of(
                        "Effect", "Deny",
                        "Condition", Map.of("Bool", Map.of("aws:SecureTransport", "false")))))))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void letsOnlyThisAccountsCloudWatchAlarmsPublishToTheTopic(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties("AWS::SNS::TopicPolicy", Map.of(
                "PolicyDocument", Map.of("Statement", Match.arrayWith(List.of(Match.exact(Map.of(
                        "Sid", "AllowThisAccountsCloudWatchAlarms",
                        "Effect", "Allow",
                        "Principal", Map.of("Service", "cloudwatch.amazonaws.com"),
                        "Action", "sns:Publish",
                        "Resource", Map.of("Ref", onlyLogicalId(template, TOPIC)),
                        "Condition", Map.of(
                                "StringEquals", Map.of("aws:SourceAccount", Map.of("Ref", "AWS::AccountId")),
                                "ArnLike", Map.of("aws:SourceArn", alarmArnPattern())))))))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void letsCloudWatchUseTheStageKeyOnlyThroughSnsInThisAccount(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties("AWS::KMS::Key", Map.of("KeyPolicy", Map.of("Statement", Match.arrayWith(List.of(
                Match.exact(Map.of(
                        "Effect", "Allow",
                        "Principal", Map.of("Service", "cloudwatch.amazonaws.com"),
                        "Action", List.of("kms:Decrypt", "kms:GenerateDataKey*"),
                        "Resource", "*",
                        "Condition", Map.of("StringEquals", Map.of(
                                "kms:ViaService", Map.of("Fn::Join", List.of("", List.of(
                                        "sns.", Map.of("Ref", "AWS::Region"), ".amazonaws.com"))),
                                "aws:SourceAccount", Map.of("Ref", "AWS::AccountId"))))))))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void outputsTheTopicArn(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasOutput("*", Map.of("Value", Map.of("Ref", onlyLogicalId(template, TOPIC))));
    }

    private static Map<String, Object> alarm(
            Template template, String namespace, String metricName, List<Map<String, Object>> dimensions, String statistic,
            int threshold, String comparison) {
        return Map.of(
                "Namespace", namespace,
                "MetricName", metricName,
                "Dimensions", dimensions,
                "Statistic", statistic,
                "Period", FIVE_MINUTES,
                "EvaluationPeriods", 1,
                "Threshold", threshold,
                "ComparisonOperator", comparison,
                "TreatMissingData", "notBreaching",
                "AlarmActions", List.of(Map.of("Ref", onlyLogicalId(template, TOPIC))));
    }

    private static Map<String, Object> alarmArnPattern() {
        return Map.of("Fn::Join", List.of("", List.of(
                "arn:", Map.of("Ref", "AWS::Partition"),
                ":cloudwatch:", Map.of("Ref", "AWS::Region"),
                ":", Map.of("Ref", "AWS::AccountId"),
                ":alarm:*")));
    }

    private static Map<String, Object> dimension(String name, Object value) {
        return Map.of("Name", name, "Value", value);
    }

    private static Template synth(EnvironmentConfig config) {
        Stack stack = PersonFunctionTemplates.stack();
        PersonStorage storage = new PersonStorage(stack, "Storage", config);
        PersonFunction function = new PersonFunction(
                stack, "Function", config, storage, PersonFunctionTemplates.stubCode(stubAsset));
        PersonApi api = new PersonApi(stack, "Api", config, function.liveAlias());
        PersonEvents events = new PersonEvents(stack, "Events", config, storage);
        new PersonMonitoring(stack, "Monitoring", config, storage, function, api, events);
        return Template.fromStack(stack);
    }
}
