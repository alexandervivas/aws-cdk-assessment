package com.tikkie.person.infra;

import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stage;
import software.amazon.awscdk.StageProps;
import software.constructs.Construct;

public final class PersonServiceStage extends Stage {

    private final EnvironmentConfig config;
    private final PersonServiceStack stack;

    public PersonServiceStage(Construct scope, Environment environment, EnvironmentConfig config) {
        super(scope, stageId(config), StageProps.builder().env(environment).build());
        this.config = config;
        this.stack = new PersonServiceStack(this, "PersonService", config);
    }

    public EnvironmentConfig config() {
        return config;
    }

    public PersonServiceStack stack() {
        return stack;
    }

    private static String stageId(EnvironmentConfig config) {
        String name = config.stageName();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
