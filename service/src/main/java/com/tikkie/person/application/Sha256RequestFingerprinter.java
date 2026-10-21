package com.tikkie.person.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Sha256RequestFingerprinter implements RequestFingerprinter {

    @Override
    public RequestHash fingerprint(CanonicalRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.utf8());
            return new RequestHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
