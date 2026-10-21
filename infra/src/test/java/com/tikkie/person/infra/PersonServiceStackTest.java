package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

class PersonServiceStackTest {

    @ParameterizedTest
    @ValueSource(strings = {"Dev", "Prod"})
    void wiresTheStorageIntoEachStage(String stageId) {
        App app = CdkTestApps.withCdkJsonContext();
        PersonServiceApp.addStages(app, Map.of());
        PersonServiceStack stack = ((PersonServiceStage) app.getNode().findChild(stageId)).stack();

        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::DynamoDB::GlobalTable", 2);
        template.resourceCountIs("AWS::KMS::Key", 1);
        assertThat(stack.storage().getNode().getScope()).isSameAs(stack);
    }
}
