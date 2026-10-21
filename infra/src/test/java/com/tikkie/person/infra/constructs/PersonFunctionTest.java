package com.tikkie.person.infra.constructs;

import static com.tikkie.person.infra.constructs.PersonFunctionTemplates.FUNCTION;
import static com.tikkie.person.infra.constructs.PersonFunctionTemplates.onlyLogicalId;
import static com.tikkie.person.infra.constructs.PersonFunctionTemplates.tableId;

import com.tikkie.person.infra.EnvironmentConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.logs.RetentionDays;

class PersonFunctionTest {

    private static final String STAGES = "com.tikkie.person.infra.constructs.PersonFunctionTemplates#stages";
    private static final Map<RetentionDays, Integer> RETENTION_IN_DAYS =
            Map.of(RetentionDays.ONE_WEEK, 7, RetentionDays.THREE_MONTHS, 90);

    @TempDir
    static Path stubAsset;

    @ParameterizedTest
    @MethodSource(STAGES)
    void runsTheCreatePersonHandlerOnJava25Arm(EnvironmentConfig config) {
        synth(config).hasResourceProperties(FUNCTION, Map.of(
                "Runtime", "java25",
                "Architectures", List.of("arm64"),
                "MemorySize", 1024,
                "Timeout", 10,
                "Handler", "com.tikkie.person.adapter.in.api.CreatePersonHandler::handleRequest"));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void appliesSnapStartToPublishedVersions(EnvironmentConfig config) {
        synth(config).hasResourceProperties(FUNCTION, Map.of(
                "SnapStart", Map.of("ApplyOn", "PublishedVersions")));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void tracesWithXRay(EnvironmentConfig config) {
        synth(config).hasResourceProperties(FUNCTION, Map.of("TracingConfig", Map.of("Mode", "Active")));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void liveAliasPointsAtThePublishedVersion(EnvironmentConfig config) {
        Template template = synth(config);
        String versionId = onlyLogicalId(template, "AWS::Lambda::Version");

        template.hasResourceProperties("AWS::Lambda::Alias", Map.of(
                "Name", "live",
                "FunctionVersion", Map.of("Fn::GetAtt", List.of(versionId, "Version"))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void passesTableNamesServiceNameAndPowertoolsLogLevel(EnvironmentConfig config) {
        Template template = synth(config);

        template.hasResourceProperties(FUNCTION, Map.of("Environment", Map.of("Variables", Map.of(
                "PERSONS_TABLE", Map.of("Ref", tableId(template, "personId")),
                "IDEMPOTENCY_KEYS_TABLE", Map.of("Ref", tableId(template, "idempotencyKey")),
                "POWERTOOLS_SERVICE_NAME", "person-service",
                "POWERTOOLS_METRICS_NAMESPACE", "PersonService",
                "POWERTOOLS_LOG_LEVEL", config.logLevel()))));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void logsToAGroupWithTheStageRetentionAndRemovalPolicy(EnvironmentConfig config) {
        Template template = synth(config);
        String logGroupId = onlyLogicalId(template, "AWS::Logs::LogGroup");
        String policy = config.removalPolicy() == RemovalPolicy.RETAIN ? "Retain" : "Delete";

        template.hasResource("AWS::Logs::LogGroup", Map.of(
                "Properties", Map.of("RetentionInDays", RETENTION_IN_DAYS.get(config.logRetention())),
                "DeletionPolicy", policy,
                "UpdateReplacePolicy", policy));
        template.hasResourceProperties(FUNCTION, Map.of("LoggingConfig", Map.of("LogGroup", Map.of("Ref", logGroupId))));
    }

    @Test
    void devHasNoReservedConcurrency() {
        synth(EnvironmentConfig.dev()).hasResourceProperties(FUNCTION, Map.of(
                "ReservedConcurrentExecutions", Match.absent()));
    }

    @Test
    void prodReservesItsConfiguredConcurrency() {
        synth(EnvironmentConfig.prod()).hasResourceProperties(FUNCTION, Map.of(
                "ReservedConcurrentExecutions", EnvironmentConfig.prod().reservedConcurrency()));
    }

    private static Template synth(EnvironmentConfig config) {
        return PersonFunctionTemplates.synth(config, stubAsset);
    }
}
