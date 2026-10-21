package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkie.person.infra.constructs.PersonFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

class PersonServiceStackTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    // ADR 0013: X-Ray's write actions support no resource-level permissions.
    private static final Map<String, Object> XRAY_WRITE_STATEMENT = Map.of(
            "Effect", "Allow",
            "Action", List.of("xray:PutTelemetryRecords", "xray:PutTraceSegments"),
            "Resource", "*");
    // ADR 0013: added by the CDK table grants, scoped to the stage's own key.
    private static final Set<String> KMS_WILDCARD_ACTIONS = Set.of("kms:GenerateDataKey*", "kms:ReEncrypt*");
    // ADR 0014: the account-level API Gateway logging role.
    private static final Map<String, Object> API_GATEWAY_LOGGING_POLICY = Map.of("Fn::Join", List.of("", List.of(
            "arn:", Map.of("Ref", "AWS::Partition"), ":iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs")));

    @ParameterizedTest
    @ValueSource(strings = {"Dev", "Prod"})
    void wiresTheStorageIntoEachStage(String stageId) {
        PersonServiceStack stack = stack(stageId);

        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::DynamoDB::GlobalTable", 2);
        template.resourceCountIs("AWS::KMS::Key", 1);
        assertThat(stack.storage().getNode().getScope()).isSameAs(stack);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dev", "Prod"})
    void wiresTheFunctionBehindItsLiveAliasIntoEachStage(String stageId) {
        PersonServiceStack stack = stack(stageId);

        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::Lambda::Function", 1);
        template.hasResourceProperties("AWS::Lambda::Alias", Map.of("Name", "live"));
        assertThat(stack.function().getNode().getScope()).isSameAs(stack);
        assertThat(stack.function().liveAlias().getAliasName()).isEqualTo(PersonFunction.ALIAS_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dev", "Prod"})
    void wiresAnApiNamedAfterTheStageIntoEachStage(String stageId) {
        Template template = Template.fromStack(stack(stageId));

        template.resourceCountIs("AWS::ApiGateway::RestApi", 1);
        template.hasResourceProperties("AWS::ApiGateway::RestApi", Map.of(
                "Name", "person-service-" + stageId.toLowerCase()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dev", "Prod"})
    void allowsIamWildcardsOnlyForTheRecordedExceptions(String stageId) {
        Template template = Template.fromStack(stack(stageId));
        Map<String, Object> stageKeyArn = Map.of("Fn::GetAtt", List.of(onlyLogicalId(template, "AWS::KMS::Key"), "Arn"));

        for (Map<String, Object> statement : policyStatements(template)) {
            if (statement.equals(XRAY_WRITE_STATEMENT)) {
                continue;
            }
            assertThat(json(statement.get("Resource"))).as(json(statement)).doesNotContain("*");
            for (String action : asList(statement.get("Action"))) {
                if (action.contains("*")) {
                    assertThat(action).as(json(statement)).isIn(KMS_WILDCARD_ACTIONS);
                    assertThat(statement.get("Resource")).as(json(statement)).isEqualTo(stageKeyArn);
                }
            }
        }
        assertThat(managedPolicyArns(template)).containsExactly(API_GATEWAY_LOGGING_POLICY);
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

    @SuppressWarnings("unchecked")
    private static List<Object> managedPolicyArns(Template template) {
        List<Object> arns = new ArrayList<>();
        for (Map<String, Object> role : template.findResources("AWS::IAM::Role").values()) {
            Map<String, Object> properties = (Map<String, Object>) role.get("Properties");
            arns.addAll((List<Object>) properties.getOrDefault("ManagedPolicyArns", List.of()));
        }
        return arns;
    }

    private static List<String> asList(Object value) {
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        return values.stream().map(Object::toString).toList();
    }

    private static String onlyLogicalId(Template template, String type) {
        Map<String, Map<String, Object>> resources = template.findResources(type);
        assertThat(resources).hasSize(1);
        return resources.keySet().iterator().next();
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PersonServiceStack stack(String stageId) {
        App app = CdkTestApps.withCdkJsonContext();
        PersonServiceApp.addStages(app, Map.of());
        return ((PersonServiceStage) app.getNode().findChild(stageId)).stack();
    }
}
