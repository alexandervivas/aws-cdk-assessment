package com.tikkie.person.application;

import com.tikkie.person.domain.Address;
import com.tikkie.person.domain.PersonDetails;
import com.tikkie.person.domain.Sensitive;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public record CanonicalRequest(String value) {

    private static final String VERSION = "v1";
    private static final String UNIT_SEPARATOR = "\u001F";

    public CanonicalRequest {
        Objects.requireNonNull(value, "value");
        strictUtf8(value);
    }

    // Built from normalised values, so a retry that differs only in formatting replays instead of conflicting.
    // Joining on U+001F is unambiguous: the domain parsers reject control characters (FreeText for the
    // address text, an allow-list for names, fixed formats for phone, postal code and country), so no value
    // contains the separator. Stored fingerprints depend on this exact form, so any change needs a new version
    // tag. The presence flag keeps an absent addition distinct from any present one.
    public static CanonicalRequest of(PersonDetails details) {
        Address address = details.address();
        List<String> values = List.of(
                VERSION,
                details.firstName().value().reveal(),
                details.lastName().value().reveal(),
                details.phoneNumber().e164().reveal(),
                address.street().reveal(),
                address.houseNumber().reveal(),
                address.houseNumberAddition().isPresent() ? "1" : "0",
                address.houseNumberAddition().map(Sensitive::reveal).orElse(""),
                address.postalCode().value().reveal(),
                address.city().reveal(),
                address.countryCode().value());
        return new CanonicalRequest(String.join(UNIT_SEPARATOR, values));
    }

    public byte[] utf8() {
        ByteBuffer encoded = strictUtf8(value);
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    @Override
    public String toString() {
        return "CanonicalRequest[***]";
    }

    // String.getBytes would replace a lone surrogate with "?", so two different requests could share a fingerprint.
    private static ByteBuffer strictUtf8(String text) {
        try {
            return StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("request text is not valid Unicode", e);
        }
    }
}
