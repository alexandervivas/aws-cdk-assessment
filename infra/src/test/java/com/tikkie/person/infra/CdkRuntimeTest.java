package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;

class CdkRuntimeTest {

    @Test
    void synthesisesAnEmptyApp() {
        assertThat(new App().synth().getStacks()).isEmpty();
    }
}
