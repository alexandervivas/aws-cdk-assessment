package com.tikkie.person.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacRequestFingerprinter implements RequestFingerprinter {

    private static final byte[] TEST_KEY = "fictional-test-key-not-a-secret!".getBytes(StandardCharsets.US_ASCII);

    @Override
    public RequestHash fingerprint(CanonicalRequest request) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(TEST_KEY, "HmacSHA256"));
            return new RequestHash(HexFormat.of().formatHex(hmac.doFinal(request.utf8())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required on every Java platform", e);
        }
    }
}
