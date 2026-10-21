package com.tikkie.person.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.tikkie.person.StandardOutput;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class TelemetryTest {

    @Test
    void runsTheCall() {
        AtomicBoolean ran = new AtomicBoolean();

        Telemetry.bestEffort(() -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void swallowsAFailingCall() {
        assertThatNoException().isThrownBy(() -> StandardOutput.capture(() -> {
            Telemetry.bestEffort(() -> {
                throw new IllegalStateException("sink down");
            });
            return null;
        }));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void reportsAFailingCallByClassNameOnly() {
        String output = StandardOutput.capture(() -> {
            Telemetry.bestEffort(() -> {
                throw new IllegalStateException("sink down");
            });
            return null;
        }).output();

        assertThat(output.lines().filter(line -> line.contains("Telemetry failed")))
                .singleElement().asString()
                .contains("java.lang.IllegalStateException").doesNotContain("sink down");
    }
}
