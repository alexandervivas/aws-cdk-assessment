package com.tikkie.person.infra;

import com.tikkie.person.infra.constructs.PersonApi;
import com.tikkie.person.infra.constructs.PersonFunction;
import com.tikkie.person.infra.constructs.PersonStorage;
import com.tikkie.person.infra.constructs.ServiceJar;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public final class PersonServiceStack extends Stack {

    private final PersonStorage storage;
    private final PersonFunction function;

    public PersonServiceStack(Construct scope, String id, EnvironmentConfig config) {
        super(scope, id, StackProps.builder().terminationProtection(config.terminationProtection()).build());
        this.storage = new PersonStorage(this, "Storage", config);
        this.function = new PersonFunction(this, "Function", config, storage, ServiceJar.code());
        new PersonApi(this, "Api", config, function.liveAlias());
    }

    public PersonStorage storage() {
        return storage;
    }

    public PersonFunction function() {
        return function;
    }
}
