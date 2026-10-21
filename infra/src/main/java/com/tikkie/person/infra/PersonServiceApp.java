package com.tikkie.person.infra;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Validations;

public final class PersonServiceApp {

    static final String DEFAULT_REGION = "eu-west-1";
    private static final String FAIL_SYNTH_ON_VALIDATION_ERRORS = "@aws-cdk/core:failSynthOnValidationErrors";
    private static final String APP_MODE = "@aws-cdk/core:appMode";

    private static final Pattern ACCOUNT_ID = Pattern.compile("^\\d{12}$");
    private static final Pattern REGION_CODE = Pattern.compile("^[a-z]{2,4}(-[a-z]+)+-\\d{1,2}$");

    private PersonServiceApp() {
    }

    public static void main(String[] args) {
        App app = newApp(Map.of());
        addStages(app, System.getenv());
        checkAwsSolutions(app);
        app.synth();
    }

    // The CLI asks the app to leave validation failures to it, then reports only those in the stacks it selected. A
    // bare `cdk synth` selects none here, because every stack sits in a stage, so the app fails synthesis itself.
    // In-memory mode puts the findings in the exception, because the JVM never sees what the jsii kernel prints.
    static App newApp(Map<String, ?> context) {
        return new App(AppProps.builder()
                .context(context)
                .postCliContext(Map.of(FAIL_SYNTH_ON_VALIDATION_ERRORS, true, APP_MODE, "inmemory"))
                .build());
    }

    // A policy validation plugin: synthesis fails on any finding not acknowledged where it arises.
    static void checkAwsSolutions(App app) {
        Validations.of(app).addPlugins(new AwsSolutionsChecks(app));
    }

    static void addStages(App app, Map<String, String> environmentVariables) {
        String devAccount = context(app, "devAccount", ACCOUNT_ID, "a 12-digit AWS account ID");
        String prodAccount = context(app, "prodAccount", ACCOUNT_ID, "a 12-digit AWS account ID");
        String region = context(app, "region", REGION_CODE, "an AWS region code such as eu-west-1");
        requireDistinctAccountPair(devAccount, prodAccount);

        String defaultAccount = environmentVariables.get("CDK_DEFAULT_ACCOUNT");
        String resolvedRegion = firstPresent(region, environmentVariables.get("CDK_DEFAULT_REGION"), DEFAULT_REGION);
        Environment devEnvironment = environment(firstPresent(devAccount, defaultAccount), resolvedRegion);
        new PersonServiceStage(app, devEnvironment, EnvironmentConfig.dev());
        new PersonServiceStage(app, environment(firstPresent(prodAccount, defaultAccount), resolvedRegion), EnvironmentConfig.prod());
        if (e2eRequested(app)) {
            new E2eSupportStack(app, "E2eSupportStack", devEnvironment, EnvironmentConfig.dev());
        }
    }

    // The CLI passes -c e2e=true as the string "true"; cdk.json or a test may pass a boolean.
    private static boolean e2eRequested(App app) {
        Object value = app.getNode().tryGetContext("e2e");
        if (value == null) {
            return false;
        }
        String flag = value.toString().trim();
        if (!flag.equals("true") && !flag.equals("false")) {
            throw new IllegalArgumentException("e2e must be true or false");
        }
        return flag.equals("true");
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
