package com.tikkie.person.infra;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;

public final class PersonServiceApp {

    static final String DEFAULT_REGION = "eu-west-1";

    private static final Pattern ACCOUNT_ID = Pattern.compile("^\\d{12}$");
    private static final Pattern REGION_CODE = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d{1,2}$");

    private PersonServiceApp() {
    }

    public static void main(String[] args) {
        App app = new App();
        addStages(app, System.getenv());
        app.synth();
    }

    static void addStages(App app, Map<String, String> environmentVariables) {
        String devAccount = context(app, "devAccount", ACCOUNT_ID, "a 12-digit AWS account ID");
        String prodAccount = context(app, "prodAccount", ACCOUNT_ID, "a 12-digit AWS account ID");
        String region = context(app, "region", REGION_CODE, "an AWS region code such as eu-west-1");
        requireDistinctAccountPair(devAccount, prodAccount);

        String defaultAccount = environmentVariables.get("CDK_DEFAULT_ACCOUNT");
        String resolvedRegion = firstPresent(region, environmentVariables.get("CDK_DEFAULT_REGION"), DEFAULT_REGION);
        new PersonServiceStage(app, environment(firstPresent(devAccount, defaultAccount), resolvedRegion), EnvironmentConfig.dev());
        new PersonServiceStage(app, environment(firstPresent(prodAccount, defaultAccount), resolvedRegion), EnvironmentConfig.prod());
    }

    private static void requireDistinctAccountPair(String devAccount, String prodAccount) {
        if ((devAccount == null) != (prodAccount == null)) {
            throw new IllegalArgumentException("Pass both devAccount and prodAccount, or neither");
        }
        if (devAccount != null && devAccount.equals(prodAccount)) {
            throw new IllegalArgumentException("devAccount and prodAccount must be different accounts");
        }
    }

    private static String context(App app, String key, Pattern format, String description) {
        Object value = app.getNode().tryGetContext(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.toString().trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Context value " + key + " is blank; pass a value or leave it out");
        }
        if (!format.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(key + " must be " + description);
        }
        return trimmed;
    }

    private static Environment environment(String account, String region) {
        return Environment.builder().account(account).region(region).build();
    }

    private static String firstPresent(String... candidates) {
        return Stream.of(candidates)
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .findFirst()
                .orElse(null);
    }
}
