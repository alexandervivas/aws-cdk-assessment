package com.tikkie.person;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolchainTest {

    @Test
    void runsOnJava25() {
        assertThat(Runtime.version().feature()).as("build must run on the Java 25 toolchain").isEqualTo(25);
    }
}
