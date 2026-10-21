package com.tikkie.person.infra;

import static com.tikkie.person.infra.CdkTestApps.withCdkJsonContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.PolicyViolatingResource;
import software.amazon.awscdk.PolicyViolation;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.Validations;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.IConstruct;
import software.constructs.MetadataEntry;

class AwsSolutionsChecksTest {

    @Test
    void bothStagesHaveNoUnacknowledgedFindings() {
        App app = appWithStages();

        List<PolicyViolation> violations = new AwsSolutionsChecks(app).validateScope(app).getViolations();

        assertThat(violations).extracting(AwsSolutionsChecksTest::describe).isEmpty();
    }

    @Test
    void acknowledgesOnlyTheRecordedFindingsWhereTheyArise() {
        assertThat(acknowledgements(appWithStages()).keySet()).containsExactlyInAnyOrderElementsOf(
                Stream.of("Dev", "Prod").flatMap(AwsSolutionsChecksTest::expectedAcknowledgements).toList());
    }

    // An acknowledgement on the App or a Stage would hide a finding on every resource below it.
    @Test
    void theAppAndItsStagesCarryNoAcknowledgements() {
        App app = appWithStages();
        List<IConstruct> appAndStages = Stream.concat(Stream.of(app), app.getNode().getChildren().stream()
                .filter(Stage::isStage)).toList();

        assertThat(appAndStages).hasSize(3).allSatisfy(construct -> assertThat(construct.getNode().getMetadata())
                .extracting(MetadataEntry::getType)
                .doesNotContain(Validations.ACKNOWLEDGED_RULES_METADATA_KEY));
    }

    @Test
    void everyAcknowledgementCitesAnAdr() {
        assertThat(acknowledgements(appWithStages()).values())
                .isNotEmpty()
                .allSatisfy(reason -> assertThat(reason).containsPattern("ADR \\d{4}"));
    }

    @Test
    void synthesisesTheAppWithTheChecksRegistered() {
        App app = appWithStages();
        PersonServiceApp.checkAwsSolutions(app);

        assertThatNoException().isThrownBy(app::synth);
    }

    @Test
    void synthesisFailsOnAnUnacknowledgedError() {
        App app = withCdkJsonContext();
        new Queue(new Stack(app, "Unchecked"), "QueueWithoutDeadLetterQueue");
        PersonServiceApp.checkAwsSolutions(app);

        assertThatThrownBy(app::synth).hasMessageContaining("Validation failed");
    }

    @Test
    void theAppFailsSynthesisAndNamesTheFindingEvenWhenTheCliOffersToReport() {
        App app = PersonServiceApp.newApp(Map.of("@aws-cdk/core:failSynthOnValidationErrors", false));
        new Queue(new Stack(app, "Unchecked"), "QueueWithoutDeadLetterQueue");
        PersonServiceApp.checkAwsSolutions(app);

        assertThatThrownBy(app::synth).hasMessageContaining("AwsSolutions-SQS3");
    }

    private static Stream<String> expectedAcknowledgements(String stageId) {
        String stack = stageId + "/PersonService/";
        String apiStage = stack + "Api/RestApi/DeploymentStage." + stageId.toLowerCase();
        return Stream.of(
                stack + "Function/Role AwsSolutions-IAM5[Resource::*]",
                stack + "Function/Role AwsSolutions-IAM5[Action::kms:GenerateDataKey*]",
                stack + "Function/Role AwsSolutions-IAM5[Action::kms:ReEncrypt*]",
                stack + "Events/PipeRole AwsSolutions-IAM5[Action::kms:GenerateDataKey*]",
                stack + "Events/PipeRole AwsSolutions-IAM5[Action::kms:ReEncrypt*]",
                stack + "Events/DeadLetterQueue AwsSolutions-SQS3",
                stack + "Api/RestApi AwsSolutions-APIG2",
                stack + "Api/RestApi/CloudWatchRole AwsSolutions-IAM4[Policy::arn:<AWS::Partition>:iam::aws:policy/"
                        + "service-role/AmazonAPIGatewayPushToCloudWatchLogs]",
                apiStage + " AwsSolutions-APIG3",
                apiStage + " AwsSolutions-APIG6",
                stack + "Api/RestApi/Default/person/POST AwsSolutions-COG4");
    }

    private static App appWithStages() {
        App app = withCdkJsonContext();
        PersonServiceApp.addStages(app, Map.of());
        return app;
    }

    // Keyed by "<full construct path> <rule id>", valued by the reason. The walk starts at the App, so an
    // acknowledgement placed above the stacks shows up too.
    private static Map<String, String> acknowledgements(App app) {
        Map<String, String> acknowledgements = new TreeMap<>();
        for (IConstruct construct : app.getNode().findAll()) {
            for (MetadataEntry entry : construct.getNode().getMetadata()) {
                if (Validations.ACKNOWLEDGED_RULES_METADATA_KEY.equals(entry.getType())) {
                    ((Map<?, ?>) entry.getData()).forEach((id, reason) -> acknowledgements.put(
                            construct.getNode().getPath() + " " + ruleId(id), reason.toString()));
                }
            }
        }
        return acknowledgements;
    }

    private static String ruleId(Object metadataKey) {
        return metadataKey.toString().replaceFirst("(?i)^annotation::", "");
    }

    private static String describe(PolicyViolation violation) {
        return violation.getRuleName() + " at " + violation.getViolatingResources().stream()
                .map(PolicyViolatingResource::getConstructPath)
                .toList();
    }
}
