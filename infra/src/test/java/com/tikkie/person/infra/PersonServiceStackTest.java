package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.infra.constructs.PersonFunction;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

class PersonServiceStackTest {

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

    private static PersonServiceStack stack(String stageId) {
        App app = CdkTestApps.withCdkJsonContext();
        PersonServiceApp.addStages(app, Map.of());
        return ((PersonServiceStage) app.getNode().findChild(stageId)).stack();
    }
}
