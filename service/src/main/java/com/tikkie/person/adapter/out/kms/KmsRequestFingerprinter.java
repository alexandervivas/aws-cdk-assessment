package com.tikkie.person.adapter.out.kms;

import com.tikkie.person.application.CanonicalRequest;
import com.tikkie.person.application.RequestFingerprinter;
import com.tikkie.person.application.RequestHash;
import java.util.HexFormat;
import java.util.Objects;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateMacRequest;
import software.amazon.awssdk.services.kms.model.MacAlgorithmSpec;

public final class KmsRequestFingerprinter implements RequestFingerprinter {

    private static final int HMAC_SHA_256_BYTES = 32;

    private final KmsClient kms;
    private final String keyId;

    // The caller owns the client. KMS errors propagate unchanged; the handler maps them to 500.
    public KmsRequestFingerprinter(KmsClient kms, String keyId) {
        this.kms = Objects.requireNonNull(kms, "kms");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
    }

    @Override
    public RequestHash fingerprint(CanonicalRequest request) {
        SdkBytes mac = kms.generateMac(GenerateMacRequest.builder()
                .keyId(keyId)
                .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256)
                .message(SdkBytes.fromByteArray(request.utf8()))
                .build())
                .mac();
        if (mac == null || mac.asByteArray().length != HMAC_SHA_256_BYTES) {
            throw new IllegalStateException("KMS returned no HMAC-SHA-256 value");
        }
        return new RequestHash(HexFormat.of().formatHex(mac.asByteArray()));
    }
}
