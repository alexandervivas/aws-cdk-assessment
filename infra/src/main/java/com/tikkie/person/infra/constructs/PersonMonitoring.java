package com.tikkie.person.infra.constructs;

import com.tikkie.person.infra.EnvironmentConfig;
import com.tikkie.person.infra.EnvironmentConfig.AlarmThresholds;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Aws;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.CreateAlarmOptions;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.Stats;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.sns.Topic;
import software.constructs.Construct;

public final class PersonMonitoring extends Construct {

    private static final Duration PERIOD = Duration.minutes(5);
    private static final String SERVICE_NAMESPACE = "PersonService";
    private static final String UNEXPECTED_ERROR_METRIC = "UnexpectedError";
    // Namespace, metric and dimension from "Monitoring Amazon EventBridge Pipes" in the EventBridge User Guide.
    private static final String PIPES_NAMESPACE = "AWS/EventBridge/Pipes";
    private static final String PIPE_EXECUTION_FAILED_METRIC = "ExecutionFailed";

    private static final ServicePrincipal CLOUDWATCH = new ServicePrincipal("cloudwatch.amazonaws.com");

    private final Topic alarmTopic;

    public PersonMonitoring(
            Construct scope, String id, EnvironmentConfig config, PersonStorage storage, PersonFunction function,
            PersonApi api, PersonEvents events) {
        super(scope, id);
        AlarmThresholds thresholds = config.alarmThresholds();
        // An AWS managed key cannot be used: its key policy cannot be changed to let CloudWatch publish alarms.
        this.alarmTopic = Topic.Builder.create(this, "AlarmTopic")
                .masterKey(storage.encryptionKey())
                .enforceSsl(true)
                .build();
        allowCloudWatchAlarmsToPublish(storage);

        // The handler turns its own failures into 500s and counts them, so Lambda Errors only sees what it cannot catch.
        alarmAtLeast("UnexpectedErrors", unexpectedErrors(config), thresholds.unexpectedErrors(),
                "The handler answered with 500 Internal Server Error");
        alarmAtLeast("LambdaErrors", function.function().metricErrors(sumOverPeriod()), thresholds.lambdaErrors(),
                "The function failed outside the handler: timeout, init failure or runtime crash");
        alarmAtLeast("ApiServerErrors", api.stage().metricServerError(sumOverPeriod()), thresholds.apiServerErrors(),
                "API Gateway answered with a 5XX, from the handler or before reaching it");
        alarmAtLeast("PipeFailures", pipeExecutionFailures(events), thresholds.pipeFailures(),
                "The Pipe failed to publish PersonCreated; it retries, then dead-letters");
        alarmAtLeast("LambdaThrottles", function.function().metricThrottles(sumOverPeriod()), thresholds.lambdaThrottles(),
                "Lambda throttled invocations; the API answered them with a 5XX");
        alarm("DeadLetters", events.deadLetterQueue().metricApproximateNumberOfMessagesVisible(MetricOptions.builder()
                        .statistic(Stats.MAXIMUM)
                        .period(PERIOD)
                        .build()),
                0, ComparisonOperator.GREATER_THAN_THRESHOLD,
                "A PersonCreated event was dead-lettered after all retries and needs replaying");

        CfnOutput.Builder.create(this, "AlarmTopicArn").value(alarmTopic.getTopicArn()).build();
    }

    // Not grantPublish: it would leave the topic statement without the source account and alarm ARN conditions.
    private void allowCloudWatchAlarmsToPublish(PersonStorage storage) {
        alarmTopic.addToResourcePolicy(PolicyStatement.Builder.create()
                .sid("AllowThisAccountsCloudWatchAlarms")
                .principals(List.of(CLOUDWATCH))
                .actions(List.of("sns:Publish"))
                .resources(List.of(alarmTopic.getTopicArn()))
                .conditions(Map.of(
                        "StringEquals", Map.of("aws:SourceAccount", Aws.ACCOUNT_ID),
                        "ArnLike", Map.of("aws:SourceArn",
                                "arn:" + Aws.PARTITION + ":cloudwatch:" + Aws.REGION + ":" + Aws.ACCOUNT_ID + ":alarm:*")))
                .build());
        // AWS's documented statement, unconditioned on purpose: sources disagree on whether kms:ViaService and
        // aws:SourceAccount reach CloudWatch's KMS request context, and if either is missing every alarm fires but
        // no notification is delivered. The topic policy above is the gate that keeps other accounts' alarms out.
        storage.encryptionKey().addToResourcePolicy(PolicyStatement.Builder.create()
                .principals(List.of(CLOUDWATCH))
                .actions(List.of("kms:Decrypt", "kms:GenerateDataKey*"))
                .resources(List.of("*"))
                .build());
    }

    private static Metric unexpectedErrors(EnvironmentConfig config) {
        return Metric.Builder.create()
                .namespace(SERVICE_NAMESPACE)
                .metricName(UNEXPECTED_ERROR_METRIC)
                .dimensionsMap(Map.of("Service", PersonFunction.serviceName(config)))
                .statistic(Stats.SUM)
                .period(PERIOD)
                .build();
    }

    private static Metric pipeExecutionFailures(PersonEvents events) {
        return Metric.Builder.create()
                .namespace(PIPES_NAMESPACE)
                .metricName(PIPE_EXECUTION_FAILED_METRIC)
                .dimensionsMap(Map.of("PipeName", events.pipeName()))
                .statistic(Stats.SUM)
                .period(PERIOD)
                .build();
    }

    private static MetricOptions sumOverPeriod() {
        return MetricOptions.builder().statistic(Stats.SUM).period(PERIOD).build();
    }

    private void alarmAtLeast(String id, Metric metric, int threshold, String description) {
        alarm(id, metric, threshold, ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD, description);
    }

    private void alarm(String id, Metric metric, int threshold, ComparisonOperator comparison, String description) {
        Alarm alarm = metric.createAlarm(this, id, CreateAlarmOptions.builder()
                .alarmDescription(description)
                .threshold(threshold)
                .comparisonOperator(comparison)
                .evaluationPeriods(1)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build());
        alarm.addAlarmAction(new SnsAction(alarmTopic));
    }
}
