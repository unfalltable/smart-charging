package io.smartcharge.platform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcTimesTest {
    @Test
    void convertsInstantToJdbcTimestampWithoutLosingPrecision() {
        Instant instant = Instant.parse("2026-09-25T06:55:36.123456Z");

        assertThat(JdbcTimes.timestamp(instant).toInstant()).isEqualTo(instant);
    }

    @Test
    void preservesNullForOptionalDatabaseValues() {
        assertThat(JdbcTimes.nullableTimestamp(null)).isNull();
    }
}
