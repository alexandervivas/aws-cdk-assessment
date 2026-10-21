package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.infra.constructs.PersonFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

class PersonServiceStackTest {

    // ADR 0014: the account-level API Gateway logging role.
    private static final Map<String, Object> API_GATEWAY_LOGGING_POLICY = Map.of("Fn::Join", List.of("", List.of(
            "arn:", Map.of("Ref", "AWS::Partition"), ":iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs")));
    private static final String NO_STAGE_KEY = "NoStageKey";

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
        List<Map<String, Object>> statements = policyStatements(template);

        assertThat(statements).isNotEmpty();
        assertThat(IamWildcards.disallowedStatements(statements, onlyLogicalId(template, "AWS::KMS::Key"))).isEmpty();
        assertThat(managedPolicyArns(template)).containsExactly(API_GATEWAY_LOGGING_POLICY);
    }

    @Test
    void rejectsAWildcardInARoleInlinePolicy() {
        Map<String, Object> statement = Map.of("Effect", "Allow", "Action", "s3:*", "Resource", "arn:aws:s3:::example-bucket");
        Template template = Template.fromJSON(Map.of("Resources", Map.of("ExampleRole", Map.of(
                "Type", "AWS::IAM::Role",
                "Properties", Map.of("Policies", List.of(Map.of(
                        "PolicyName", "inline",
                        "PolicyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(statement)))))))));

        assertThat(IamWildcards.disallowedStatements(policyStatements(template), NO_STAGE_KEY)).containsExactly(statement);
    }

    @Test
    void rejectsAWildcardInACustomerManagedPolicy() {
        Map<String, Object> statement = Map.of(
                "Effect", "Allow", "Action", "dynamodb:Get*", "Resource", Map.of("Fn::GetAtt", List.of("ExampleTable", "Arn")));
        Template template = Template.fromJSON(Map.of("Resources", Map.of("ExamplePolicy", Map.of(
                "Type", "AWS::IAM::ManagedPolicy",
                "Properties", Map.of("PolicyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(statement)))))));

        assertThat(IamWildcards.disallowedStatements(policyStatements(template), NO_STAGE_KEY)).containsExactly(statement);
    }

    private static List<Map<String, Object>> policyStatements(Template template) {
        List<Map<String, Object>> statements = new ArrayList<>();
        for (Map<String, Object> policy : resourceProperties(template, "AWS::IAM::Policy")) {
            statements.addAll(statementsOf(policy.get("PolicyDocument")));
        }
        for (Map<String, Object> policy : resourceProperties(template, "AWS::IAM::ManagedPolicy")) {
            statements.addAll(statementsOf(policy.get("PolicyDocument")));
        }
        for (Map<String, Object> role : resourceProperties(template, "AWS::IAM::Role")) {
            for (Map<String, Object> inlinePolicy : inlinePolicies(role)) {
                statements.addAll(statementsOf(inlinePolicy.get("PolicyDocument")));
            }
        }
        return statements;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resourceProperties(Template template, String type) {
        return template.findResources(type).values().stream()
                .map(resource -> (Map<String, Object>) resource.getOrDefault("Properties", Map.of()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inlinePolicies(Map<String, Object> roleProperties) {
        return (List<Map<String, Object>>) roleProperties.getOrDefault("Policies", List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> statementsOf(Object policyDocument) {
        return (List<Map<String, Object>>) ((Map<String, Object>) policyDocument).get("Statement");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> managedPolicyArns(Template template) {
        List<Object> arns = new ArrayList<>();
        for (Map<String, Object> role : resourceProperties(template, "AWS::IAM::Role")) {
            arns.addAll((List<Object>) role.getOrDefault("ManagedPolicyArns", List.of()));
        }
        return arns;
    }

    private static String onlyLogicalId(Template template, String type) {
        Map<String, Map<String, Object>> resources = template.findResources(type);
        assertThat(resources).hasSize(1);
        return resources.keySet().iterator().next();
    }

    private static PersonServiceStack stack(String stageId) {
        App app = CdkTestApps.withCdkJsonContext();
        PersonServiceApp.addStages(app, Map.of());
        return ((PersonServiceStage) app.getNode().findChild(stageId)).stack();
    }
}
