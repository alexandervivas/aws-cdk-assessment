package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IamWildcardsTest {

    private static final String KEY_ID = "StorageKey1234ABCD";
    private static final Map<String, Object> KEY_ARN = Map.of("Fn::GetAtt", List.of(KEY_ID, "Arn"));
    private static final Map<String, Object> TABLE_ARN = Map.of("Fn::GetAtt", List.of("PersonsTable5678EFGH", "Arn"));

    @Test
    void allowsTheXRayWriteStatementOnEveryResource() {
        Map<String, Object> statement = allow(List.of("xray:PutTelemetryRecords", "xray:PutTraceSegments"), "*");

        assertThat(IamWildcards.disallowedStatements(List.of(statement), KEY_ID)).isEmpty();
    }

    @Test
    void allowsTheKmsWildcardActionsOnTheStageKey() {
        Map<String, Object> statement = allow(
                List.of("kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*"), KEY_ARN);

        assertThat(IamWildcards.disallowedStatements(List.of(statement), KEY_ID)).isEmpty();
    }

    @Test
    void allowsStatementsWithoutWildcards() {
        Map<String, Object> statement = allow(List.of("dynamodb:PutItem"), TABLE_ARN);

        assertThat(IamWildcards.disallowedStatements(List.of(statement), KEY_ID)).isEmpty();
    }

    @Test
    void rejectsAnEmbeddedActionWildcard() {
        assertRejected(allow(List.of("dynamodb:Get*"), TABLE_ARN));
    }

    @Test
    void rejectsASingleCharacterActionWildcard() {
        assertRejected(allow(List.of("s3:?etObject"), "arn:aws:s3:::example-bucket/object"));
    }

    @Test
    void rejectsAWildcardInsideAnIntrinsicResource() {
        Map<String, Object> tableChildren = Map.of("Fn::Join", List.of("", List.of(TABLE_ARN, "/index/*")));

        assertRejected(allow(List.of("dynamodb:Query"), tableChildren));
    }

    @Test
    void rejectsATableWildcardInsideAJoinedArn() {
        Map<String, Object> anyTable = Map.of("Fn::Join", List.of("", List.of(
                "arn:", Map.of("Ref", "AWS::Partition"), ":dynamodb:eu-west-1:", Map.of("Ref", "AWS::AccountId"), ":table/*")));

        assertRejected(allow(List.of("dynamodb:PutItem"), anyTable));
    }

    @Test
    void rejectsTheKmsWildcardActionsOnAnyOtherResource() {
        assertRejected(allow(List.of("kms:ReEncrypt*", "kms:GenerateDataKey*"), "*"));
        assertRejected(allow(List.of("kms:GenerateDataKey*"), Map.of("Fn::GetAtt", List.of("OtherKey9999", "Arn"))));
    }

    @Test
    void rejectsOtherKmsWildcardsOnTheStageKey() {
        assertRejected(allow(List.of("kms:*"), KEY_ARN));
        assertRejected(allow(List.of("kms:Create*"), KEY_ARN));
    }

    @Test
    void rejectsTheXRayActionsWithAnExtraAction() {
        assertRejected(allow(List.of("xray:PutTelemetryRecords", "xray:PutTraceSegments", "xray:GetTraceSummaries"), "*"));
    }

    private static void assertRejected(Map<String, Object> statement) {
        assertThat(IamWildcards.disallowedStatements(List.of(statement), KEY_ID)).containsExactly(statement);
    }

    private static Map<String, Object> allow(List<String> actions, Object resource) {
        return Map.of("Effect", "Allow", "Action", actions, "Resource", resource);
    }
}
