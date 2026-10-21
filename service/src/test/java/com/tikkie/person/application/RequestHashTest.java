package com.tikkie.person.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestHashTest {

    @Test
    void acceptsSixtyFourLowercaseHexCharacters() {
        String hex = "65b1679a729e0bbcb5fe67c2845e490a6c3f0937cdfeb13cef84ccd9702bee48";

        assertThat(new RequestHash(hex).hex()).isEqualTo(hex);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"})
    void rejectsAnythingButSixtyFourLowercaseHexCharacters(String hex) {
        assertThatIllegalArgumentException().isThrownBy(() -> new RequestHash(hex));
    }
}
