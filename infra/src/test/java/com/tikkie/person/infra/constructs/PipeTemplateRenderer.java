package com.tikkie.person.infra.constructs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// An approximation of the Pipes input transformer, enough for INPUT_TEMPLATE: each unquoted <$.path> becomes the
// JSON value at that dot path, so string values come out quoted. Placeholders inside quotes, arrays and wildcards
// are not supported.
final class PipeTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("<\\$\\.([^>]+)>");

    private PipeTemplateRenderer() {
    }

    static String render(String template, JsonNode record) {
        return PLACEHOLDER.matcher(template)
                .replaceAll(placeholder -> Matcher.quoteReplacement(valueAt(record, placeholder.group(1)).toString()));
    }

    private static JsonNode valueAt(JsonNode record, String path) {
        JsonNode node = record;
        for (String field : path.split("\\.")) {
            node = node.path(field);
        }
        if (node.isMissingNode()) {
            throw new IllegalArgumentException("sample record has no value at " + path);
        }
        return node;
    }
}
