package com.tikkie.person.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CallerIdTest {

    private static final String ROLE = "arn:aws:sts::111122223333:assumed-role/person-api-client";

    @ParameterizedTest
    @ValueSource(strings = {"session-1", "other-session@example.com"})
    void dropsTheSessionNameOfAnAssumedRole(String session) {
        assertThat(CallerId.of(ROLE + "/" + session)).isEqualTo(new CallerId(ROLE));
    }

    @Test
    void dropsTheSessionNameInAnyPartition() {
        assertThat(CallerId.of("arn:aws-cn:sts::111122223333:assumed-role/client/session-1").value())
                .isEqualTo("arn:aws-cn:sts::111122223333:assumed-role/client");
    }

    @ParameterizedTest
    @ValueSource(strings = {"arn:aws:iam::111122223333:user/integration-client", ROLE,
            "arn:aws:iam::111122223333:role/person-api-client"})
    void keepsAnyOtherArnAsIs(String arn) {
        assertThat(CallerId.of(arn).value()).isEqualTo(arn);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void rejectsAMissingPrincipal(String arn) {
        assertThatIllegalArgumentException().isThrownBy(() -> CallerId.of(arn));
    }

    @Test
    void rejectsTheKeySeparatorWithoutEchoingTheValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CallerId.of("arn:aws:iam::111122223333:user/client|x"))
                .withMessageNotContaining("client");
    }

    @Test
    void toStringHidesTheValue() {
        assertThat(CallerId.of("arn:aws:iam::111122223333:user/integration-client").toString())
                .doesNotContain("integration-client", "111122223333");
    }
}
