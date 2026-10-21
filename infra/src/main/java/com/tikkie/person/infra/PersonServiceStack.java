package com.tikkie.person.infra;

import com.tikkie.person.infra.constructs.PersonStorage;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public final class PersonServiceStack extends Stack {

    private final PersonStorage storage;

    public PersonServiceStack(Construct scope, String id, EnvironmentConfig config) {
        super(scope, id, StackProps.builder().terminationProtection(config.terminationProtection()).build());
        this.storage = new PersonStorage(this, "Storage", config);
    }

    public PersonStorage storage() {
        return storage;
    }
}
