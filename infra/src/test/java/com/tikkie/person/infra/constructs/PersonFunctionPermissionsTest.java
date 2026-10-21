package com.tikkie.person.infra.constructs;

import static com.tikkie.person.infra.IamWildcards.asStrings;
import static com.tikkie.person.infra.IamWildcards.json;
import static com.tikkie.person.infra.constructs.PersonFunctionTemplates.onlyLogicalId;
import static com.tikkie.person.infra.constructs.PersonFunctionTemplates.tableId;
import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.infra.EnvironmentConfig;
import com.tikkie.person.infra.IamWildcards;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class PersonFunctionPermissionsTest {

    private static final String STAGES = "com.tikkie.person.infra.constructs.PersonFunctionTemplates#stages";
    private static final Set<String> TABLE_READ_ACTIONS = Set.of(
            "dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:Scan",
            "dynamodb:ConditionCheckItem", "dynamodb:GetRecords", "dynamodb:GetShardIterator");
    private static final Set<String> TABLE_ADMIN_ACTIONS = Set.of(
            "dynamodb:CreateTable", "dynamodb:DeleteTable", "dynamodb:UpdateTable", "dynamodb:UpdateTimeToLive",
            "dynamodb:UpdateContinuousBackups", "dynamodb:CreateBackup", "dynamodb:DeleteBackup",
            "dynamodb:RestoreTableFromBackup", "dynamodb:TagResource", "dynamodb:UntagResource");

    @TempDir
    static Path stubAsset;

    @ParameterizedTest
    @MethodSource(STAGES)
    void writesPersonsWithoutReadingIt(EnvironmentConfig config) {
        Template template = synth(config);

        Set<String> actions = actionsOn(template, tableId(template, "personId"));

        assertThat(actions).contains("dynamodb:PutItem").doesNotContainAnyElementsOf(TABLE_READ_ACTIONS);
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void readsAndWritesIdempotencyKeysWithoutAdministeringTheTable(EnvironmentConfig config) {
        Template template = synth(config);

        Set<String> actions = actionsOn(template, tableId(template, "idempotencyKey"));

        assertThat(actions).contains("dynamodb:PutItem", "dynamodb:GetItem").doesNotContainAnyElementsOf(TABLE_ADMIN_ACTIONS);
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void usesTheTablesKeyToEncryptAndDecrypt(EnvironmentConfig config) {
        Template template = synth(config);

        assertThat(actionsOn(template, onlyLogicalId(template, "AWS::KMS::Key"))).containsExactlyInAnyOrder(
                "kms:Decrypt",
                "kms:DescribeKey",
                "kms:Encrypt",
                "kms:ReEncrypt*",
                "kms:GenerateDataKey*");
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void writesOnlyToItsOwnLogGroup(EnvironmentConfig config) {
        Template template = synth(config);
        String logGroupId = onlyLogicalId(template, "AWS::Logs::LogGroup");

        assertThat(actionsOn(template, logGroupId)).containsExactlyInAnyOrder("logs:CreateLogStream", "logs:PutLogEvents");
        for (Map<String, Object> statement : policyStatements(template)) {
            if (asStrings(statement.get("Action")).stream().anyMatch(action -> action.startsWith("logs:"))) {
                assertThat(asStrings(statement.get("Resource"))).as(json(statement)).allMatch(resource -> resource.contains(logGroupId));
            }
        }
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void attachesNoManagedPolicies(EnvironmentConfig config) {
        synth(config).hasResourceProperties("AWS::IAM::Role", Map.of(
                "AssumeRolePolicyDocument", Match.objectLike(Map.of("Statement", List.of(Match.objectLike(Map.of(
                        "Principal", Map.of("Service", "lambda.amazonaws.com")))))),
                "ManagedPolicyArns", Match.absent()));
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void grantsNoIndexResources(EnvironmentConfig config) {
        for (Map<String, Object> statement : policyStatements(synth(config))) {
            assertThat(asStrings(statement.get("Resource"))).as(json(statement)).noneMatch(resource -> resource.contains("index/*"));
        }
    }

    @ParameterizedTest
    @MethodSource(STAGES)
    void usesWildcardsOnlyForTheAdr0013Exceptions(EnvironmentConfig config) {
        Template template = synth(config);
        String stageKeyId = onlyLogicalId(template, "AWS::KMS::Key");

        assertThat(IamWildcards.disallowedStatements(policyStatements(template), stageKeyId)).isEmpty();
    }

    private static Set<String> actionsOn(Template template, String logicalId) {
        Set<String> actions = new TreeSet<>();
        for (Map<String, Object> statement : policyStatements(template)) {
            if (asStrings(statement.get("Resource")).stream().anyMatch(resource -> resource.contains("\"" + logicalId + "\""))) {
                actions.addAll(asStrings(statement.get("Action")));
            }
        }
        return actions;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> policyStatements(Template template) {
        List<Map<String, Object>> statements = new ArrayList<>();
        for (Map<String, Object> policy : template.findResources("AWS::IAM::Policy").values()) {
            Map<String, Object> properties = (Map<String, Object>) policy.get("Properties");
            Map<String, Object> document = (Map<String, Object>) properties.get("PolicyDocument");
            statements.addAll((List<Map<String, Object>>) document.get("Statement"));
        }
        assertThat(statements).isNotEmpty();
        return statements;
    }

    private static Template synth(EnvironmentConfig config) {
        return PersonFunctionTemplates.synth(config, stubAsset);
    }
}
