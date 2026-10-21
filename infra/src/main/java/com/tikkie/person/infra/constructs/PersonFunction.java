package com.tikkie.person.infra.constructs;

import static com.tikkie.person.infra.constructs.NagAcknowledgements.acknowledge;
import static com.tikkie.person.infra.constructs.NagAcknowledgements.acknowledgeStageKeyPrefixActions;

import com.tikkie.person.infra.EnvironmentConfig;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.SnapStartConf;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.LogGroup;
import software.constructs.Construct;

public final class PersonFunction extends Construct {

    public static final String ALIAS_NAME = "live";
    static final String HANDLER = "com.tikkie.person.adapter.in.api.CreatePersonHandler::handleRequest";

    private final Function function;
    private final Alias liveAlias;

    public PersonFunction(Construct scope, String id, EnvironmentConfig config, PersonStorage storage, Code code) {
        super(scope, id);
        LogGroup logGroup = LogGroup.Builder.create(this, "LogGroup")
                .dataProtectionPolicy(PersonalDataLogProtection.policy())
                .retention(config.logRetention())
                .removalPolicy(config.removalPolicy())
                .build();
        Role role = Role.Builder.create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .build();
        logGroup.grantWrite(role);
        acknowledge(role, "AwsSolutions-IAM5[Resource::*]",
                "ADR 0013: X-Ray PutTraceSegments and PutTelemetryRecords have no resource-level permissions. "
                        + "The stack IAM walk fails on any other wildcard resource.");
        acknowledgeStageKeyPrefixActions(role, "ADR 0013: emitted by the table grants, scoped to the stage key.");
        this.function = Function.Builder.create(this, "CreatePerson")
                .runtime(Runtime.JAVA_25)
                .architecture(Architecture.ARM_64)
                .memorySize(1024)
                .timeout(Duration.seconds(10))
                .code(code)
                .handler(HANDLER)
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .role(role)
                .logGroup(logGroup)
                .reservedConcurrentExecutions(config.reservedConcurrency())
                .environment(Map.of(
                        "PERSONS_TABLE", storage.personsTable().getTableName(),
                        "IDEMPOTENCY_KEYS_TABLE", storage.idempotencyKeysTable().getTableName(),
                        "POWERTOOLS_SERVICE_NAME", serviceName(config),
                        "POWERTOOLS_METRICS_NAMESPACE", "PersonService",
                        "POWERTOOLS_LOG_LEVEL", config.logLevel()))
                .build();
        storage.personsTable().grantWriteData(function);
        storage.idempotencyKeysTable().grantReadWriteData(function);
        this.liveAlias = Alias.Builder.create(this, "LiveAlias")
                .aliasName(ALIAS_NAME)
                .version(function.getCurrentVersion())
                .build();
    }

    // Powertools emits it as the Service dimension of every metric. Naming the stage keeps the metrics of two stages
    // in one account apart.
    public static String serviceName(EnvironmentConfig config) {
        return "person-service-" + config.stageName();
    }

    public Function function() {
        return function;
    }

    public Alias liveAlias() {
        return liveAlias;
    }
}
