package com.tikkie.person.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

final class FreeText {

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern EDGE_SPACES = Pattern.compile("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$");

    private FreeText() {
    }

    static String parse(String field, String raw, int maxLength) {
        String text = strip(raw);
        if (text.isEmpty()) {
            throw ValidationException.of(field, "required", "must not be blank");
        }
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw ValidationException.of(field, "too_long", "must be at most " + maxLength + " characters");
        }
        if (CONTROL.matcher(text).find()) {
            throw ValidationException.of(field, "invalid_characters", "must not contain control characters");
        }
        return text;
    }

    // NFC first, so a decomposed "u + combining diaeresis" equals the composed letter.
    // \p{Z} also strips no-break spaces, which String.strip() keeps. A null input strips to "".
    static String strip(String raw) {
        return raw == null ? "" : EDGE_SPACES.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("");
    }
}
