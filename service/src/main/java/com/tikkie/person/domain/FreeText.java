package com.tikkie.person.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

final class FreeText {

    // Lone surrogates (Cs) would turn into "?" when encoded as UTF-8; format (Cf) and
    // line/paragraph separators (Zl, Zp) are invisible and make equal-looking values differ.
    private static final Pattern DISALLOWED = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]");
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
        if (DISALLOWED.matcher(text).find()) {
            throw ValidationException.of(field, "invalid_characters", "must not contain control or invisible characters");
        }
        return text;
    }

    // NFC first, so a decomposed "u + combining diaeresis" equals the composed letter.
    // \p{Z} also strips no-break spaces, which String.strip() keeps. A null input strips to "".
    static String strip(String raw) {
        return raw == null ? "" : EDGE_SPACES.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("");
    }
}
