package com.tikkie.person.application;

import static com.tikkie.person.application.PersonFixtures.PERSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.domain.PersonDetails;
import org.junit.jupiter.api.Test;

class HmacRequestFingerprinterTest {

    private final RequestFingerprinter fingerprinter = new HmacRequestFingerprinter();

    @Test
    void matchesTheKnownHmacOfTheCanonicalForm() {
        RequestHash hash = fingerprinter.fingerprint(CanonicalRequest.of(PersonDetails.parse(PERSON)));

        assertThat(hash.hex()).isEqualTo("65b1679a729e0bbcb5fe67c2845e490a6c3f0937cdfeb13cef84ccd9702bee48");
    }
}
