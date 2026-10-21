package com.tikkie.person.application;

import static com.tikkie.person.application.PersonFixtures.PERSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.domain.PersonDetails;
import org.junit.jupiter.api.Test;

class Sha256RequestFingerprinterTest {

    private final RequestFingerprinter fingerprinter = new Sha256RequestFingerprinter();

    @Test
    void matchesTheKnownSha256OfTheCanonicalForm() {
        RequestHash hash = fingerprinter.fingerprint(CanonicalRequest.of(PersonDetails.parse(PERSON)));

        assertThat(hash.hex()).isEqualTo("578a9125eff2fb44b0d40b773643ff73d48934f7ec431447f3213f85ec91d334");
    }
}
