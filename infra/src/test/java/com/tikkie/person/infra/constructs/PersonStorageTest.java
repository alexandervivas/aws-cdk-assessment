package com.tikkie.person.infra.constructs;

import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.infra.CdkTestApps;
import com.tikkie.person.infra.EnvironmentConfig;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class PersonStorageTest {

    private static final String TABLE = "AWS::DynamoDB::GlobalTable";

    static Stream<EnvironmentConfig> stages() {
        return Stream.of(EnvironmentConfig.dev(), EnvironmentConfig.prod());
    }

    @ParameterizedTest
    @MethodSource("stages")
    void createsOneCustomerManagedKeyWithRotation(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs("AWS::KMS::Key", 1);
        template.hasResourceProperties("AWS::KMS::Key", Map.of("EnableKeyRotation", true));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void encryptsBothTablesWithThatKey(EnvironmentConfig config) {
        Template template = synth(config);
        String keyId = template.findResources("AWS::KMS::Key").keySet().iterator().next();

        template.resourcePropertiesCountIs(TABLE, Map.of("SSESpecification", Map.of("SSEEnabled", true, "SSEType", "KMS")), 2);
        template.resourcePropertiesCountIs(TABLE, replicaProperty(
                "SSESpecification", Map.of("KMSMasterKeyId", Map.of("Fn::GetAtt", List.of(keyId, "Arn")))),
                2);
    }

    @ParameterizedTest
    @MethodSource("stages")
    void personsTableIsKeyedByPersonIdOnDemandWithNewImageStreamAndNoTtl(EnvironmentConfig config) {
        synth(config).hasResourceProperties(TABLE, Map.of(
                "KeySchema", List.of(Map.of("AttributeName", "personId", "KeyType", "HASH")),
                "AttributeDefinitions", List.of(Map.of("AttributeName", "personId", "AttributeType", "S")),
                "BillingMode", "PAY_PER_REQUEST",
                "StreamSpecification", Map.of("StreamViewType", "NEW_IMAGE"),
                "TimeToLiveSpecification", Match.absent()));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void idempotencyKeysTableIsKeyedByIdempotencyKeyOnDemandWithTtlAndNoStream(EnvironmentConfig config) {
        synth(config).hasResourceProperties(TABLE, Map.of(
                "KeySchema", List.of(Map.of("AttributeName", "idempotencyKey", "KeyType", "HASH")),
                "AttributeDefinitions", List.of(Map.of("AttributeName", "idempotencyKey", "AttributeType", "S")),
                "BillingMode", "PAY_PER_REQUEST",
                "TimeToLiveSpecification", Map.of("AttributeName", "expiresAt", "Enabled", true),
                "StreamSpecification", Match.absent()));
    }

    @ParameterizedTest
    @MethodSource("stages")
    void setsNoPhysicalTableNames(EnvironmentConfig config) {
        Template template = synth(config);

        template.resourceCountIs(TABLE, 2);
        template.resourcePropertiesCountIs(TABLE, Map.of("TableName", Match.absent()), 2);
    }

    @Test
    void prodRetainsAndProtectsKeyAndTablesWithPointInTimeRecovery() {
        Template template = synth(EnvironmentConfig.prod());

        assertRemovalPolicy(template, "Retain");
        template.resourcePropertiesCountIs(TABLE, replicaProperty("DeletionProtectionEnabled", true), 2);
        template.resourcePropertiesCountIs(TABLE, pointInTimeRecovery(true), 2);
    }

    @Test
    void devDestroysKeyAndTablesWithoutDeletionProtectionOrPointInTimeRecovery() {
        Template template = synth(EnvironmentConfig.dev());

        assertRemovalPolicy(template, "Delete");
        template.resourcePropertiesCountIs(TABLE, replicaProperty("DeletionProtectionEnabled", false), 2);
        template.resourcePropertiesCountIs(TABLE, pointInTimeRecovery(false), 2);
    }

    private static Map<String, Object> pointInTimeRecovery(boolean enabled) {
        return replicaProperty("PointInTimeRecoverySpecification", Map.of("PointInTimeRecoveryEnabled", enabled));
    }

    private static Map<String, Object> replicaProperty(String key, Object value) {
        return Map.of("Replicas", List.of(Match.objectLike(Map.of(key, value))));
    }

    private static void assertRemovalPolicy(Template template, String policy) {
        Map<String, Object> retention = Map.of("DeletionPolicy", policy, "UpdateReplacePolicy", policy);
        template.resourceCountIs("AWS::KMS::Key", 1);
        template.hasResource("AWS::KMS::Key", retention);
        assertThat(template.findResources(TABLE, retention)).hasSize(2);
    }

    private static Template synth(EnvironmentConfig config) {
        Stack stack = new Stack(CdkTestApps.withCdkJsonContext(), "Test", StackProps.builder()
                .env(Environment.builder().region("eu-west-1").build())
                .build());
        new PersonStorage(stack, "Storage", config);
        return Template.fromStack(stack);
    }
}
