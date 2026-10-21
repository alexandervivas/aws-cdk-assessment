package com.tikkie.person.infra;

import static com.tikkie.person.infra.CdkTestApps.withCdkJsonContext;
import static com.tikkie.person.infra.CdkTestApps.withFeatureFlagsOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.FeatureFlags;
import software.amazon.awscdk.Token;

class PersonServiceAppTest {

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\b\\d{12}\\b");
    private static final String DEV_ACCOUNT = "111111111111";
    private static final String PROD_ACCOUNT = "222222222222";

    @Test
    void buildsDevAndProdStagesWithTheirOwnConfig() {
        App app = withCdkJsonContext();

        PersonServiceApp.addStages(app, Map.of());

        assertThat(stage(app, "Dev").config()).isEqualTo(EnvironmentConfig.dev());
        assertThat(stage(app, "Prod").config()).isEqualTo(EnvironmentConfig.prod());
    }

    @Test
    void namesTheStageAfterItsConfig() {
        PersonServiceStage stage = new PersonServiceStage(withCdkJsonContext(), Environment.builder().build(), EnvironmentConfig.prod());

        assertThat(stage.getNode().getId()).isEqualTo("Prod");
    }

    @Test
    void protectsOnlyTheProdStackFromTermination() {
        App app = withCdkJsonContext();

        PersonServiceApp.addStages(app, Map.of());

        assertThat(stage(app, "Dev").stack().getTerminationProtection()).isFalse();
        assertThat(stage(app, "Prod").stack().getTerminationProtection()).isTrue();
    }

    @Test
    void staysEnvironmentAgnosticWithoutAnAccount() {
        App app = withCdkJsonContext();

        PersonServiceApp.addStages(app, Map.of());

        assertThat(Token.isUnresolved(stage(app, "Dev").stack().getAccount())).isTrue();
        assertThat(Token.isUnresolved(stage(app, "Prod").stack().getAccount())).isTrue();
        assertThat(stage(app, "Dev").stack().getRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void takesAccountsAndRegionFromContext() {
        App app = withCdkJsonContext(Map.of("devAccount", DEV_ACCOUNT, "prodAccount", PROD_ACCOUNT, "region", "eu-central-1"));

        PersonServiceApp.addStages(app, Map.of("CDK_DEFAULT_ACCOUNT", "333333333333", "CDK_DEFAULT_REGION", "us-east-1"));

        assertThat(stage(app, "Dev").stack().getAccount()).isEqualTo(DEV_ACCOUNT);
        assertThat(stage(app, "Prod").stack().getAccount()).isEqualTo(PROD_ACCOUNT);
        assertThat(stage(app, "Prod").stack().getRegion()).isEqualTo("eu-central-1");
    }

    @Test
    void trimsContextValues() {
        App app = withCdkJsonContext(Map.of("devAccount", " 111111111111 ", "prodAccount", "222222222222\n", "region", " eu-central-1 "));

        PersonServiceApp.addStages(app, Map.of());

        assertThat(stage(app, "Dev").stack().getAccount()).isEqualTo(DEV_ACCOUNT);
        assertThat(stage(app, "Prod").stack().getAccount()).isEqualTo(PROD_ACCOUNT);
        assertThat(stage(app, "Dev").stack().getRegion()).isEqualTo("eu-central-1");
    }

    @Test
    void requiresBothAccountsWhenEitherIsGiven() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> addStagesWithContext(Map.of("devAccount", DEV_ACCOUNT)))
                .withMessageContaining("both devAccount and prodAccount");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> addStagesWithContext(Map.of("prodAccount", PROD_ACCOUNT)))
                .withMessageContaining("both devAccount and prodAccount");
    }

    @Test
    void rejectsTheSameAccountForDevAndProd() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> addStagesWithContext(Map.of("devAccount", DEV_ACCOUNT, "prodAccount", " " + DEV_ACCOUNT)))
                .withMessageContaining("different accounts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678901", "1234567890123", "11111111111a", "1111-1111-1111"})
    void rejectsAnAccountThatIsNotTwelveDigits(String account) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> addStagesWithContext(Map.of("devAccount", account, "prodAccount", PROD_ACCOUNT)))
                .withMessageContaining("devAccount must be a 12-digit AWS account ID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eusc-de-east-1", "us-gov-west-1"})
    void acceptsSovereignAndGovCloudRegionCodes(String region) {
        App app = withCdkJsonContext(Map.of("region", region));

        PersonServiceApp.addStages(app, Map.of());

        assertThat(stage(app, "Prod").stack().getRegion()).isEqualTo(region);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe", "eu_west_1", "EU-WEST-1", "eu-west"})
    void rejectsARegionThatIsNotARegionCode(String region) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> addStagesWithContext(Map.of("region", region)))
                .withMessageContaining("region must be an AWS region code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"devAccount", "prodAccount", "region"})
    void rejectsBlankContextExplicitly(String key) {
        Map<String, Object> context = new HashMap<>(Map.of("devAccount", DEV_ACCOUNT, "prodAccount", PROD_ACCOUNT));
        context.put(key, "  ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> addStagesWithContext(context))
                .withMessage("Context value " + key + " is blank; pass a value or leave it out");
    }

    @Test
    void fallsBackToTheCdkDefaultEnvironment() {
        App app = withFeatureFlagsOnly();

        PersonServiceApp.addStages(app, Map.of("CDK_DEFAULT_ACCOUNT", "333333333333", "CDK_DEFAULT_REGION", "us-east-1"));

        assertThat(stage(app, "Dev").stack().getAccount()).isEqualTo("333333333333");
        assertThat(stage(app, "Prod").stack().getRegion()).isEqualTo("us-east-1");
    }

    @Test
    void regionFromCdkJsonWinsOverCdkDefaultRegion() {
        App app = withCdkJsonContext();

        PersonServiceApp.addStages(app, Map.of("CDK_DEFAULT_REGION", "us-east-1"));

        assertThat(stage(app, "Dev").stack().getRegion()).isEqualTo("eu-west-1");
        assertThat(stage(app, "Prod").stack().getRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void appliesTheRecommendedFeatureFlagsFromCdkJson() {
        App app = withCdkJsonContext();

        PersonServiceApp.addStages(app, Map.of());

        assertThat(CdkTestApps.featureFlags()).isNotEmpty();
        assertThat(FeatureFlags.of(stage(app, "Prod").stack()).isEnabled("@aws-cdk/aws-iam:minimizePolicies")).isTrue();
    }

    @Test
    void synthesisesBothStagesOffline() {
        App app = withCdkJsonContext();
        PersonServiceApp.addStages(app, Map.of());

        assertThat(app.synth().getNestedAssemblies()).hasSize(2);
    }

    @Test
    void commitsNoAccountIds() throws IOException {
        List<Path> files;
        try (Stream<Path> sources = Files.walk(Path.of("src/main"))) {
            files = Stream.concat(sources.filter(Files::isRegularFile), Stream.of(Path.of("../cdk.json"))).toList();
        }

        for (Path file : files) {
            assertThat(ACCOUNT_ID.matcher(Files.readString(file)).find()).as(file.toString()).isFalse();
        }
    }

    private static void addStagesWithContext(Map<String, Object> context) {
        PersonServiceApp.addStages(withCdkJsonContext(context), Map.of());
    }

    private static PersonServiceStage stage(App app, String id) {
        return (PersonServiceStage) app.getNode().findChild(id);
    }
}
