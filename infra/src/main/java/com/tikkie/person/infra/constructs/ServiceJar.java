package com.tikkie.person.infra.constructs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import software.amazon.awscdk.services.lambda.Code;

public final class ServiceJar {

    private static final String RELATIVE_PATH = "service/target/person-service.jar";

    private ServiceJar() {
    }

    public static Code code() {
        return Code.fromAsset(locate(Path.of("")).toString());
    }

    // `cdk synth` runs from the repository root, Maven tests from the infra module.
    static Path locate(Path workingDirectory) {
        Path absolute = workingDirectory.toAbsolutePath();
        return Stream.of(absolute, absolute.resolve(".."))
                .map(directory -> directory.resolve(RELATIVE_PATH).normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        RELATIVE_PATH + " not found; build it first with ./mvnw -B package -DskipTests"));
    }
}
