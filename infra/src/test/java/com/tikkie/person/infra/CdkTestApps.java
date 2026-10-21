package com.tikkie.person.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;

public final class CdkTestApps {

    private static final Path CDK_JSON = Path.of("../cdk.json");

    private CdkTestApps() {
    }

    public static App withCdkJsonContext() {
        return withCdkJsonContext(Map.of());
    }

    public static App withCdkJsonContext(Map<String, Object> overrides) {
        Map<String, Object> context = new HashMap<>(cdkJsonContext());
        context.putAll(overrides);
        return appWith(context);
    }

    public static App withFeatureFlagsOnly() {
        return appWith(featureFlags());
    }

    public static Map<String, Object> featureFlags() {
        return cdkJsonContext().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("@aws-cdk"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> cdkJsonContext() {
        try {
            Map<String, Object> cdkJson = new ObjectMapper().readValue(
                    CDK_JSON.toFile(), new TypeReference<Map<String, Object>>() { });
            return (Map<String, Object>) cdkJson.getOrDefault("context", Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static App appWith(Map<String, Object> context) {
        return new App(AppProps.builder().context(context).build());
    }
}
