package com.tikkie.person.infra.constructs;

import static com.tikkie.person.infra.constructs.NagAcknowledgements.acknowledge;
import static com.tikkie.person.infra.constructs.NagAcknowledgements.acknowledgeStageKeyPrefixActions;

import com.tikkie.person.infra.EnvironmentConfig;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.ArnComponents;
import software.amazon.awscdk.ArnFormat;
import software.amazon.awscdk.Aws;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.events.BaseArchiveProps;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ServicePrincipalOpts;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.pipes.CfnPipe;
import software.amazon.awscdk.services.pipes.CfnPipe.CloudwatchLogsLogDestinationProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.DeadLetterConfigProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.FilterCriteriaProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.FilterProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.PipeLogConfigurationProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.PipeSourceDynamoDBStreamParametersProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.PipeSourceParametersProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.PipeTargetEventBridgeEventBusParametersProperty;
import software.amazon.awscdk.services.pipes.CfnPipe.PipeTargetParametersProperty;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.constructs.Construct;

public final class PersonEvents extends Construct {

    public static final String EVENT_SOURCE = "tikkie.person-service";
    public static final String DETAIL_TYPE = "PersonCreated";
    static final String INSERT_ONLY_FILTER = "{\"eventName\":[\"INSERT\"]}";
    // A thin event: nothing about the person beyond its id. Pipes quotes string values itself, so the placeholders
    // stay unquoted.
    static final String INPUT_TEMPLATE = "{\"metadata\":{"
            + "\"eventId\":<$.eventID>,"
            + "\"eventVersion\":\"1\","
            + "\"occurredAt\":<$.dynamodb.NewImage.createdAt.S>,"
            + "\"correlationId\":<$.dynamodb.NewImage.correlationId.S>},"
            + "\"data\":{\"personId\":<$.dynamodb.NewImage.personId.S>}}";
    static final int MAXIMUM_RETRY_ATTEMPTS = 10;
    // One record per batch: a failing record is retried and dead-lettered alone, and a retry never republishes
    // records that were already delivered.
    static final int BATCH_SIZE = 1;
    // With one record per batch a shard delivers roughly 20 events/s, below the prod API rate of 100 requests/s.
    // Ten concurrent batches per shard keep up, and ordering is only kept per person, which is all one
    // PersonCreated per person needs.
    static final int PARALLELIZATION_FACTOR = 10;
    // A record still failing after 6 h is dead-lettered while the stream keeps it for another 18 h, so the DLQ
    // metadata can still be used to re-read it. Without a limit, service errors that don't consume retry attempts
    // could hold a record until the 24 h stream retention drops it unrecoverably.
    static final int MAXIMUM_RECORD_AGE_SECONDS = 6 * 60 * 60;
    static final String LOG_LEVEL = "ERROR";

    private final EventBus bus;
    private final Queue deadLetterQueue;
    private final CfnPipe pipe;

    public PersonEvents(Construct scope, String id, EnvironmentConfig config, PersonStorage storage) {
        super(scope, id);
        this.bus = EventBus.Builder.create(this, "Bus")
                .eventBusName(busName(config))
                .build();
        bus.archive("Archive", BaseArchiveProps.builder()
                .description("Person Service events")
                .eventPattern(EventPattern.builder().source(List.of(EVENT_SOURCE)).build())
                .retention(Duration.days(config.eventArchiveRetention().toDays()))
                .build());
        // Pipes dead-letters batch metadata, not the record itself. The stage key avoids a second key per stage.
        this.deadLetterQueue = Queue.Builder.create(this, "DeadLetterQueue")
                .encryption(QueueEncryption.KMS)
                .encryptionMasterKey(storage.encryptionKey())
                .retentionPeriod(Duration.days(14))
                .enforceSsl(true)
                .removalPolicy(config.removalPolicy())
                .build();
        acknowledge(deadLetterQueue, "AwsSolutions-SQS3",
                "ADR 0001: this is the Pipe's dead-letter queue. cdk-nag only recognises SQS and Lambda dead-letter "
                        + "settings, not a Pipe's.");
        LogGroup logGroup = LogGroup.Builder.create(this, "PipeLogGroup")
                .dataProtectionPolicy(PersonalDataLogProtection.policy())
                .retention(config.logRetention())
                .removalPolicy(config.removalPolicy())
                .build();
        Role role = Role.Builder.create(this, "PipeRole")
                .assumedBy(pipesOfThisAccount())
                .build();
        storage.personsTable().grantStreamRead(role);
        bus.grantPutEventsTo(role);
        deadLetterQueue.grantSendMessages(role);
        logGroup.grantWrite(role);
        acknowledgeStageKeyPrefixActions(role,
                "ADR 0013: emitted by grantStreamRead and the dead-letter queue grant, scoped to the stage key.");
        this.pipe = pipe(storage, role, logGroup);
        // Pipes checks at creation that its role can read the source, so the role's policy must exist first.
        pipe.getNode().addDependency(role);
        CfnOutput.Builder.create(this, "BusName").value(bus.getEventBusName()).build();
        CfnOutput.Builder.create(this, "BusArn").value(bus.getEventBusArn()).build();
    }

    public static String busName(EnvironmentConfig config) {
        return "person-events-" + config.stageName();
    }

    public Queue deadLetterQueue() {
        return deadLetterQueue;
    }

    // Ref on AWS::Pipes::Pipe returns the pipe name.
    public String pipeName() {
        return pipe.getRef();
    }

    // Confused-deputy guard: only a pipe in this account and region may assume the role.
    private ServicePrincipal pipesOfThisAccount() {
        String pipesOfThisRegion = Stack.of(this).formatArn(ArnComponents.builder()
                .service("pipes")
                .resource("pipe")
                .resourceName("*")
                .arnFormat(ArnFormat.SLASH_RESOURCE_NAME)
                .build());
        return new ServicePrincipal("pipes.amazonaws.com", ServicePrincipalOpts.builder()
                .conditions(Map.of(
                        "StringEquals", Map.of("aws:SourceAccount", Aws.ACCOUNT_ID),
                        "ArnLike", Map.of("aws:SourceArn", pipesOfThisRegion)))
                .build());
    }

    private CfnPipe pipe(PersonStorage storage, Role role, LogGroup logGroup) {
        return CfnPipe.Builder.create(this, "PersonCreatedPipe")
                .description("Publishes PersonCreated for every inserted person")
                .roleArn(role.getRoleArn())
                .source(storage.personsTable().getTableStreamArn())
                .sourceParameters(PipeSourceParametersProperty.builder()
                        .dynamoDbStreamParameters(PipeSourceDynamoDBStreamParametersProperty.builder()
                                .startingPosition("LATEST")
                                .batchSize(BATCH_SIZE)
                                .parallelizationFactor(PARALLELIZATION_FACTOR)
                                .maximumRetryAttempts(MAXIMUM_RETRY_ATTEMPTS)
                                .maximumRecordAgeInSeconds(MAXIMUM_RECORD_AGE_SECONDS)
                                .deadLetterConfig(DeadLetterConfigProperty.builder()
                                        .arn(deadLetterQueue.getQueueArn())
                                        .build())
                                .build())
                        .filterCriteria(FilterCriteriaProperty.builder()
                                .filters(List.of(FilterProperty.builder().pattern(INSERT_ONLY_FILTER).build()))
                                .build())
                        .build())
                .target(bus.getEventBusArn())
                .targetParameters(PipeTargetParametersProperty.builder()
                        .eventBridgeEventBusParameters(PipeTargetEventBridgeEventBusParametersProperty.builder()
                                .source(EVENT_SOURCE)
                                .detailType(DETAIL_TYPE)
                                .build())
                        .inputTemplate(INPUT_TEMPLATE)
                        .build())
                // No IncludeExecutionData: it would log the stream record, NewImage and its personal data included.
                .logConfiguration(PipeLogConfigurationProperty.builder()
                        .level(LOG_LEVEL)
                        .cloudwatchLogsLogDestination(CloudwatchLogsLogDestinationProperty.builder()
                                .logGroupArn(logGroup.getLogGroupArn())
                                .build())
                        .build())
                .build();
    }
}
