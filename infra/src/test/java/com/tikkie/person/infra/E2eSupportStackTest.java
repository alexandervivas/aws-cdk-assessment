package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.infra.constructs.PersonEvents;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class E2eSupportStackTest {

    private static final String DEV_ACCOUNT = "111111111111";
    private static final String NO_STAGE_KEY = "NoStageKey";

    @Test
    void routesPersonCreatedFromTheDevBusToTheQueue() {
        Template template = template();

        template.resourceCountIs("AWS::Events::EventBus", 0);
        template.hasResourceProperties("AWS::Events::Rule", Map.of(
                "EventBusName", "person-events-dev",
                "EventPattern", Map.of(
                        "source", List.of(PersonEvents.EVENT_SOURCE),
                        "detail-type", List.of(PersonEvents.DETAIL_TYPE)),
                "State", "ENABLED",
                "Targets", List.of(Map.of(
                        "Arn", Map.of("Fn::GetAtt", List.of(queueLogicalId(template), "Arn")),
                        "Id", Match.anyValue()))));
    }

    @Test
    void encryptsTheQueueWithSqsManagedKeysAndKeepsMessagesForOneDay() {
        Template template = template();

        template.resourceCountIs("AWS::KMS::Key", 0);
        template.hasResourceProperties("AWS::SQS::Queue", Map.of(
                "SqsManagedSseEnabled", true,
                "MessageRetentionPeriod", 24 * 60 * 60));
    }

    @Test
    void deniesQueueAccessWithoutTls() {
        template().hasResourceProperties("AWS::SQS::QueuePolicy", Map.of("PolicyDocument", Map.of(
                "Statement", Match.arrayWith(List.of(Match.objectLike(Map.of(
                        "Effect", "Deny",
                        "Condition", Map.of("Bool", Map.of("aws:SecureTransport", "false")))))))));
    }

    @Test
    void letsOnlyThisRuleSendToTheQueue() {
        Template template = template();

        assertThat(allowStatements(template)).containsExactly(Map.of(
                "Effect", "Allow",
                "Principal", Map.of("Service", "events.amazonaws.com"),
                "Action", List.of("sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:SendMessage"),
                "Resource", Map.of("Fn::GetAtt", List.of(queueLogicalId(template), "Arn")),
                "Condition", Map.of("ArnEquals", Map.of(
                        "aws:SourceArn", Map.of("Fn::GetAtt", List.of(ruleLogicalId(template), "Arn"))))));
    }

    @Test
    void grantsNoIamPermissionsAndNoWildcards() {
        Template template = template();

        template.resourceCountIs("AWS::IAM::Role", 0);
        template.resourceCountIs("AWS::IAM::Policy", 0);
        assertThat(IamWildcards.disallowedStatements(allowStatements(template), NO_STAGE_KEY)).isEmpty();
    }

    @Test
    void outputsTheQueueUrl() {
        Template template = template();

        template.hasOutput("QueueUrl", Map.of("Value", Map.of("Ref", queueLogicalId(template))));
    }

    @Test
    void deploysToTheDevEnvironment() {
        E2eSupportStack stack = stack(Map.of("devAccount", DEV_ACCOUNT, "prodAccount", "222222222222", "region", "eu-central-1"));

        assertThat(stack.getAccount()).isEqualTo(DEV_ACCOUNT);
        assertThat(stack.getRegion()).isEqualTo("eu-central-1");
        assertThat(stack.getTerminationProtection()).isFalse();
    }

    private static Template template() {
        return Template.fromStack(stack(Map.of()));
    }

    private static E2eSupportStack stack(Map<String, Object> context) {
        Map<String, Object> withFlag = new HashMap<>(context);
        withFlag.put("e2e", "true");
        App app = CdkTestApps.withCdkJsonContext(withFlag);
        PersonServiceApp.addStages(app, Map.of());
        return (E2eSupportStack) app.getNode().findChild("E2eSupportStack");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> allowStatements(Template template) {
        Map<String, Object> policy = template.findResources("AWS::SQS::QueuePolicy").values().iterator().next();
        Map<String, Object> document = (Map<String, Object>) ((Map<String, Object>) policy.get("Properties")).get("PolicyDocument");
        return ((List<Map<String, Object>>) document.get("Statement")).stream()
                .filter(statement -> "Allow".equals(statement.get("Effect")))
                .toList();
    }

    private static String queueLogicalId(Template template) {
        return onlyLogicalId(template, "AWS::SQS::Queue");
    }

    private static String ruleLogicalId(Template template) {
        return onlyLogicalId(template, "AWS::Events::Rule");
    }

    private static String onlyLogicalId(Template template, String type) {
        Map<String, Map<String, Object>> resources = template.findResources(type);
        assertThat(resources).hasSize(1);
        return resources.keySet().iterator().next();
    }
}
