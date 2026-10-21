package com.tikkie.person.infra;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public final class PersonServiceStack extends Stack {

    public PersonServiceStack(Construct scope, String id, EnvironmentConfig config) {
        super(scope, id, StackProps.builder().terminationProtection(config.terminationProtection()).build());
    }
}
