package com.tikkie.person.application;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CallerId(String value) {

    private static final Pattern ASSUMED_ROLE_SESSION =
            Pattern.compile("(arn:[^:/]+:sts::[0-9]+:assumed-role/[^/]+)/[^/]+");

    public CallerId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("caller id must not be blank");
        }
        // Reserved so a caller and an idempotency key can be joined into one unambiguous store key.
        if (value.indexOf('|') >= 0) {
            throw new IllegalArgumentException("caller id must not contain '|'");
        }
    }

    // A retry may arrive on a new STS session of the same role, so the session name is not part of the caller.
    public static CallerId of(String principalArn) {
        if (principalArn == null || principalArn.isBlank()) {
            throw new IllegalArgumentException("principal ARN is required");
        }
        Matcher assumedRole = ASSUMED_ROLE_SESSION.matcher(principalArn);
        return new CallerId(assumedRole.matches() ? assumedRole.group(1) : principalArn);
    }

    @Override
    public String toString() {
        return "CallerId[***]";
    }
}
