package com.tikkie.person.application;

import java.util.Objects;
import java.util.regex.Pattern;

public record RequestHash(String hex) {

    private static final Pattern LOWERCASE_HEX_256_BITS = Pattern.compile("[0-9a-f]{64}");

    public RequestHash {
        Objects.requireNonNull(hex, "hex");
        if (!LOWERCASE_HEX_256_BITS.matcher(hex).matches()) {
            throw new IllegalArgumentException("not 64 lowercase hex characters");
        }
    }
}
