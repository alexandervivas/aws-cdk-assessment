package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.tikkie.person.infra.EnvironmentConfig.AlarmThresholds;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.logs.RetentionDays;

class EnvironmentConfigTest {

    private static final AlarmThresholds THRESHOLDS = new AlarmThresholds(1, 1);
    private static final Duration ARCHIVE_RETENTION = Duration.ofDays(7);

    @Test
    void devIsDisposable() {
        EnvironmentConfig dev = EnvironmentConfig.dev();

        assertThat(dev.stageName()).isEqualTo("dev");
        assertThat(dev.removalPolicy()).isEqualTo(RemovalPolicy.DESTROY);
        assertThat(dev.deletionProtection()).isFalse();
        assertThat(dev.terminationProtection()).isFalse();
        assertThat(dev.pointInTimeRecovery()).isFalse();
        assertThat(dev.reservedConcurrency()).isNull();
    }

    @Test
    void prodIsRetainedAndProtected() {
        EnvironmentConfig prod = EnvironmentConfig.prod();

        assertThat(prod.stageName()).isEqualTo("prod");
        assertThat(prod.removalPolicy()).isEqualTo(RemovalPolicy.RETAIN);
        assertThat(prod.deletionProtection()).isTrue();
        assertThat(prod.terminationProtection()).isTrue();
        assertThat(prod.pointInTimeRecovery()).isTrue();
        assertThat(prod.reservedConcurrency()).isPositive();
    }

    @Test
    void prodAllowsMoreTrafficThanDev() {
        assertThat(EnvironmentConfig.prod().apiRateLimit()).isGreaterThan(EnvironmentConfig.dev().apiRateLimit());
        assertThat(EnvironmentConfig.prod().apiBurstLimit()).isGreaterThan(EnvironmentConfig.dev().apiBurstLimit());
    }

    @Test
    void devArchivesEventsForAWeekAndProdForThirtyDays() {
        assertThat(EnvironmentConfig.dev().eventArchiveRetention().toDays()).isEqualTo(7);
        assertThat(EnvironmentConfig.prod().eventArchiveRetention().toDays()).isEqualTo(30);
    }

    @Test
    void rejectsMissingEventArchiveRetention() {
        assertThatNullPointerException()
                .isThrownBy(() -> new EnvironmentConfig(
                        "dev", "INFO", RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, false, false, false,
                        10, 20, null, THRESHOLDS, null))
                .withMessage("eventArchiveRetention");
    }

    @Test
    void rejectsAnArchiveRetentionThatIsNotAPositiveNumberOfWholeDays() {
        assertThatIllegalArgumentException().isThrownBy(() -> archivedFor(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> archivedFor(Duration.ofDays(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> archivedFor(Duration.ofHours(36)));
    }

    @Test
    void rejectsMissingStageName() {
        assertThatNullPointerException()
                .isThrownBy(() -> config(null, "INFO", RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, THRESHOLDS))
                .withMessage("stageName");
    }

    @Test
    void rejectsMissingLogLevel() {
        assertThatNullPointerException()
                .isThrownBy(() -> config("dev", null, RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, THRESHOLDS))
                .withMessage("logLevel");
    }

    @Test
    void rejectsMissingLogRetention() {
        assertThatNullPointerException()
                .isThrownBy(() -> config("dev", "INFO", null, RemovalPolicy.DESTROY, THRESHOLDS))
                .withMessage("logRetention");
    }

    @Test
    void rejectsMissingRemovalPolicy() {
        assertThatNullPointerException()
                .isThrownBy(() -> config("dev", "INFO", RetentionDays.ONE_WEEK, null, THRESHOLDS))
                .withMessage("removalPolicy");
    }

    @Test
    void rejectsMissingAlarmThresholds() {
        assertThatNullPointerException()
                .isThrownBy(() -> config("dev", "INFO", RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, null))
                .withMessage("alarmThresholds");
    }

    @Test
    void rejectsBlankStageNameOrLogLevel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config(" ", "INFO", RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, THRESHOLDS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config("dev", " ", RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, THRESHOLDS));
    }

    @Test
    void rejectsNonPositiveThrottling() {
        assertThatIllegalArgumentException().isThrownBy(() -> throttled(0, 20, null));
        assertThatIllegalArgumentException().isThrownBy(() -> throttled(10, 0, null));
    }

    @Test
    void rejectsNonPositiveReservedConcurrency() {
        assertThatIllegalArgumentException().isThrownBy(() -> throttled(10, 20, 0));
    }

    @Test
    void prodBurstsNoHigherThanItsReservedConcurrency() {
        EnvironmentConfig prod = EnvironmentConfig.prod();

        assertThat(prod.apiRateLimit()).isEqualTo(100);
        assertThat(prod.apiBurstLimit()).isEqualTo(100);
        assertThat(prod.reservedConcurrency()).isEqualTo(100);
    }

    @Test
    void rejectsABurstLimitAboveTheReservedConcurrency() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> throttled(10, 101, 100))
                .withMessageContaining("apiBurstLimit");
    }

    @Test
    void acceptsABurstLimitEqualToTheReservedConcurrency() {
        assertThat(throttled(10, 100, 100).apiBurstLimit()).isEqualTo(100);
    }

    @Test
    void rejectsNegativeAlarmThresholds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AlarmThresholds(-1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AlarmThresholds(1, -1));
    }

    private static EnvironmentConfig config(
            String stageName, String logLevel, RetentionDays logRetention, RemovalPolicy removalPolicy, AlarmThresholds thresholds) {
        return new EnvironmentConfig(
                stageName, logLevel, logRetention, removalPolicy, false, false, false, 10, 20, null, thresholds,
                ARCHIVE_RETENTION);
    }

    private static EnvironmentConfig archivedFor(Duration eventArchiveRetention) {
        return new EnvironmentConfig(
                "dev", "INFO", RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, false, false, false,
                10, 20, null, THRESHOLDS, eventArchiveRetention);
    }

    private static EnvironmentConfig throttled(double rateLimit, int burstLimit, Integer reservedConcurrency) {
        return new EnvironmentConfig(
                "dev", "INFO", RetentionDays.ONE_WEEK, RemovalPolicy.DESTROY, false, false, false,
                rateLimit, burstLimit, reservedConcurrency, THRESHOLDS, ARCHIVE_RETENTION);
    }
}
