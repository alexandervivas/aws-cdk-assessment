package com.tikkie.person.infra.constructs;

import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.infra.CdkTestApps;
import com.tikkie.person.infra.EnvironmentConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.lambda.Code;

final class PersonFunctionTemplates {

    static final String FUNCTION = "AWS::Lambda::Function";

    private PersonFunctionTemplates() {
    }

    static Stream<EnvironmentConfig> stages() {
        return Stream.of(EnvironmentConfig.dev(), EnvironmentConfig.prod());
    }

    static Template synth(EnvironmentConfig config, Path assetDirectory) {
        Stack stack = stack();
        new PersonFunction(stack, "Function", config, new PersonStorage(stack, "Storage", config), stubCode(assetDirectory));
        return Template.fromStack(stack);
    }

    static Stack stack() {
        return new Stack(CdkTestApps.withCdkJsonContext(), "Test", StackProps.builder()
                .env(Environment.builder().region("eu-west-1").build())
                .build());
    }

    static Code stubCode(Path assetDirectory) {
        try {
            Files.writeString(assetDirectory.resolve("handler.txt"), "stub");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Code.fromAsset(assetDirectory.toString());
    }

    static String tableId(Template template, String partitionKey) {
        return template.findResources("AWS::DynamoDB::GlobalTable", Map.of("Properties", Map.of(
                "KeySchema", List.of(Map.of("AttributeName", partitionKey, "KeyType", "HASH")))))
                .keySet().iterator().next();
    }

    static String onlyLogicalId(Template template, String type) {
        Map<String, Map<String, Object>> resources = template.findResources(type);
        assertThat(resources).hasSize(1);
        return resources.keySet().iterator().next();
    }
}
