package com.tikkie.person.infra.constructs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceJarTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void findsTheJarWhenSynthesisingFromTheRepositoryRoot() throws IOException {
        Path jar = createJar();

        assertThat(ServiceJar.locate(repositoryRoot)).isEqualTo(jar);
    }

    @Test
    void findsTheJarWhenRunningFromTheInfraModule() throws IOException {
        Path jar = createJar();
        Path infraModule = Files.createDirectories(repositoryRoot.resolve("infra"));

        assertThat(ServiceJar.locate(infraModule)).isEqualTo(jar);
    }

    @Test
    void failsWithABuildHintWhenTheJarIsMissing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ServiceJar.locate(repositoryRoot))
                .withMessageContaining("./mvnw");
    }

    @Test
    void failsWithABuildHintFromTheFilesystemRoot() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ServiceJar.locate(repositoryRoot.getRoot()))
                .withMessageContaining("./mvnw");
    }

    private Path createJar() throws IOException {
        Path jar = repositoryRoot.resolve("service/target/person-service.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "stub");
        return jar.toAbsolutePath().normalize();
    }
}
