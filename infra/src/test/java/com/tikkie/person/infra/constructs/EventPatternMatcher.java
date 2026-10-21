package com.tikkie.person.infra.constructs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

// An approximation of EventBridge pattern matching, enough for the pipe filter: nested objects and arrays of exact
// values. Prefix, numeric, exists and anything-but matchers are not supported.
final class EventPatternMatcher {

    private EventPatternMatcher() {
    }

    static boolean matches(JsonNode pattern, JsonNode event) {
        for (Map.Entry<String, JsonNode> field : pattern.properties()) {
            JsonNode expected = field.getValue();
            JsonNode actual = event.path(field.getKey());
            boolean fieldMatches = expected.isObject() ? matches(expected, actual) : isOneOf(expected, actual);
            if (!fieldMatches) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOneOf(JsonNode allowedValues, JsonNode actual) {
        if (!allowedValues.isArray()) {
            throw new IllegalArgumentException("unsupported pattern value: " + allowedValues);
        }
        for (JsonNode allowed : allowedValues) {
            if (allowed.equals(actual)) {
                return true;
            }
        }
        return false;
    }
}
