package com.tikkie.person.adapter.out.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tikkie.person.application.CanonicalRequest;
import com.tikkie.person.application.RequestHash;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateMacRequest;
import software.amazon.awssdk.services.kms.model.GenerateMacResponse;
import software.amazon.awssdk.services.kms.model.MacAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.NotFoundException;

class KmsRequestFingerprinterTest {

    private static final String KEY_ID = "alias/person-request-fingerprint";
    private static final String MAC_HEX = "65b1679a729e0bbcb5fe67c2845e490a6c3f0937cdfeb13cef84ccd9702bee48";
    private static final CanonicalRequest REQUEST = new CanonicalRequest("v1\u001FZoë\u001FDamrak");

    @Test
    void asksKmsForAnHmacSha256OfTheCanonicalUtf8BytesUnderTheConfiguredKey() {
        StubKms kms = new StubKms(request -> macOf(HexFormat.of().parseHex(MAC_HEX)));

        new KmsRequestFingerprinter(kms, KEY_ID).fingerprint(REQUEST);

        assertThat(kms.requests).singleElement().satisfies(request -> {
            assertThat(request.keyId()).isEqualTo(KEY_ID);
            assertThat(request.macAlgorithm()).isEqualTo(MacAlgorithmSpec.HMAC_SHA_256);
            assertThat(request.message().asByteArray()).isEqualTo(REQUEST.utf8());
        });
    }

    @Test
    void returnsTheMacAsLowercaseHex() {
        StubKms kms = new StubKms(request -> macOf(HexFormat.of().parseHex(MAC_HEX)));

        RequestHash hash = new KmsRequestFingerprinter(kms, KEY_ID).fingerprint(REQUEST);

        assertThat(hash).isEqualTo(new RequestHash(MAC_HEX));
    }

    @Test
    void kmsErrorsPropagateUnchanged() {
        NotFoundException missingKey = NotFoundException.builder().message("key not found").build();
        StubKms kms = new StubKms(request -> {
            throw missingKey;
        });

        assertThatThrownBy(() -> new KmsRequestFingerprinter(kms, KEY_ID).fingerprint(REQUEST)).isSameAs(missingKey);
    }

    @Test
    void aMacOfTheWrongLengthFailsWithoutRevealingTheRequest() {
        StubKms kms = new StubKms(request -> macOf(new byte[16]));

        assertThatIllegalStateException()
                .isThrownBy(() -> new KmsRequestFingerprinter(kms, KEY_ID).fingerprint(REQUEST))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Zo", "Damrak"))
                .withNoCause();
    }

    @Test
    void aMissingMacFailsWithoutRevealingTheRequest() {
        StubKms kms = new StubKms(request -> GenerateMacResponse.builder().build());

        assertThatIllegalStateException()
                .isThrownBy(() -> new KmsRequestFingerprinter(kms, KEY_ID).fingerprint(REQUEST))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Zo", "Damrak"))
                .withNoCause();
    }

    @Test
    void requiresAKeyId() {
        StubKms kms = new StubKms(request -> macOf(new byte[32]));

        assertThatIllegalArgumentException().isThrownBy(() -> new KmsRequestFingerprinter(kms, " "));
        assertThatThrownBy(() -> new KmsRequestFingerprinter(kms, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KmsRequestFingerprinter(null, KEY_ID)).isInstanceOf(NullPointerException.class);
    }

    private static GenerateMacResponse macOf(byte[] mac) {
        return GenerateMacResponse.builder().mac(SdkBytes.fromByteArray(mac)).build();
    }

    private static final class StubKms implements KmsClient {

        private final List<GenerateMacRequest> requests = new ArrayList<>();
        private final Function<GenerateMacRequest, GenerateMacResponse> answer;

        StubKms(Function<GenerateMacRequest, GenerateMacResponse> answer) {
            this.answer = answer;
        }

        @Override
        public GenerateMacResponse generateMac(GenerateMacRequest request) {
            requests.add(request);
            return answer.apply(request);
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }
    }
}
