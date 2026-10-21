package com.tikkie.person.adapter.in.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.powertools.logging.argument.StructuredArguments;

// Logs and metrics describe a response; they must never be the reason it is lost.
final class Telemetry {

    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

    private Telemetry() {
    }

    static void bestEffort(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            reportFailure(e);
        }
    }

    // Class name only: the message of a failure from a logging or metrics sink can carry what it was writing.
    private static void reportFailure(RuntimeException e) {
        try {
            LOG.warn("Telemetry failed", StructuredArguments.entry("exception", e.getClass().getName()));
        } catch (RuntimeException ignored) {
            // Logging itself is broken, so there is nowhere left to report it.
        }
    }
}
