package com.tikkie.person.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IamWildcards {

    private static final ObjectMapper JSON = new ObjectMapper();
    // X-Ray's write actions support no resource-level permissions, so "*" is their only valid resource (ADR 0013).
    private static final Map<String, Object> XRAY_WRITE_STATEMENT = Map.of(
            "Effect", "Allow",
            "Action", List.of("xray:PutTelemetryRecords", "xray:PutTraceSegments"),
            "Resource", "*");
    private static final Set<String> KMS_WILDCARD_ACTIONS = Set.of("kms:ReEncrypt*", "kms:GenerateDataKey*");

    private IamWildcards() {
    }

    public static List<Map<String, Object>> disallowedStatements(List<Map<String, Object>> statements, String stageKeyLogicalId) {
        return statements.stream()
                .filter(statement -> usesWildcard(statement) && !isAdr0013Exception(statement, stageKeyLogicalId))
                .toList();
    }

    private static boolean usesWildcard(Map<String, Object> statement) {
        return !wildcards(statement.get("Action")).isEmpty() || !wildcards(statement.get("Resource")).isEmpty();
    }

    private static boolean isAdr0013Exception(Map<String, Object> statement, String stageKeyLogicalId) {
        return statement.equals(XRAY_WRITE_STATEMENT) || isKmsKeyStatement(statement, stageKeyLogicalId);
    }

    private static boolean isKmsKeyStatement(Map<String, Object> statement, String stageKeyLogicalId) {
        String stageKeyArn = json(Map.of("Fn::GetAtt", List.of(stageKeyLogicalId, "Arn")));
        return KMS_WILDCARD_ACTIONS.containsAll(wildcards(statement.get("Action")))
                && asStrings(statement.get("Resource")).equals(List.of(stageKeyArn));
    }

    private static List<String> wildcards(Object value) {
        if (value == null) {
            return List.of();
        }
        return asStrings(value).stream().filter(text -> text.contains("*") || text.contains("?")).toList();
    }

    public static List<String> asStrings(Object value) {
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        return values.stream().map(IamWildcards::json).toList();
    }

    public static String json(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
