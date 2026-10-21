package com.tikkie.person.infra;

import static com.tikkie.person.infra.constructs.NagAcknowledgements.acknowledge;

import com.tikkie.person.infra.constructs.PersonEvents;
import java.util.List;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.IEventBus;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.targets.SqsQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.constructs.Construct;

// Test-only: gives the end-to-end tests a queue that receives every PersonCreated on the stage bus. It sits outside
// the stages, so Dev and Prod never contain it.
public final class E2eSupportStack extends Stack {

    public E2eSupportStack(Construct scope, String id, Environment environment, EnvironmentConfig config) {
        super(scope, id, StackProps.builder()
                .env(environment)
                .description("Test-only: routes PersonCreated from the " + config.stageName() + " bus to a queue")
                .build());
        IEventBus bus = EventBus.fromEventBusName(this, "Bus", PersonEvents.busName(config));
        // SQS-managed encryption: EventBridge can send to it without a key policy, and it costs no extra key.
        Queue queue = Queue.Builder.create(this, "Queue")
                .encryption(QueueEncryption.SQS_MANAGED)
                .retentionPeriod(Duration.days(1))
                .enforceSsl(true)
                .build();
        acknowledge(queue, "AwsSolutions-SQS3",
                "ADR 0015: a test sink read by the end-to-end tests. A message nobody reads expires; a dead-letter "
                        + "queue would hold nothing worth redriving.");
        Rule.Builder.create(this, "PersonCreatedRule")
                .eventBus(bus)
                .eventPattern(EventPattern.builder()
                        .source(List.of(PersonEvents.EVENT_SOURCE))
                        .detailType(List.of(PersonEvents.DETAIL_TYPE))
                        .build())
                .targets(List.of(new SqsQueue(queue)))
                .build();
        CfnOutput.Builder.create(this, "QueueUrl").value(queue.getQueueUrl()).build();
    }
}
