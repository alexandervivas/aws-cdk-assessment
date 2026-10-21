package com.tikkie.person.adapter.in.api;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// The value is echoed in headers and written to logs, so only a short, plain token is ever accepted.
record CorrelationId(String value) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    CorrelationId {
        Objects.requireNonNull(value, "value");
        if (!isSafe(value)) {
            throw new IllegalArgumentException("correlation id must match " + SAFE.pattern());
        }
    }

    static CorrelationId fromHeader(String raw, Supplier<UUID> ids) {
        if (raw != null && isSafe(raw)) {
            return new CorrelationId(raw);
        }
        return new CorrelationId(ids.get().toString());
    }

    private static boolean isSafe(String value) {
        return SAFE.matcher(value).matches();
    }
}
