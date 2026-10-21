package com.tikkie.person.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

final class FreeText {

    // Lone surrogates (Cs) would turn into "?" when encoded as UTF-8; format (Cf) and
    // line/paragraph separators (Zl, Zp) are invisible and make equal-looking values differ.
    private static final Pattern DISALLOWED = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]");

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
    static String strip(String raw) {
        return trim(raw == null ? null : Normalizer.normalize(raw, Normalizer.Form.NFC));
    }

    // Also trims no-break spaces, which String.strip() keeps. A null input trims to "".
    static String trim(String raw) {
        if (raw == null) {
            return "";
        }
        int start = 0;
        while (start < raw.length() && isEdgeSpace(raw.codePointAt(start))) {
            start += Character.charCount(raw.codePointAt(start));
        }
        int end = raw.length();
        while (end > start && isEdgeSpace(raw.codePointBefore(end))) {
            end -= Character.charCount(raw.codePointBefore(end));
        }
        return raw.substring(start, end);
    }

    private static boolean isEdgeSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
