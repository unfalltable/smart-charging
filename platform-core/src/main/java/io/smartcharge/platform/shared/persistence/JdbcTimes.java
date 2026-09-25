package io.smartcharge.platform.shared.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/** PostgreSQL JDBC parameters for UTC instants. */
public final class JdbcTimes {
    private JdbcTimes() { }

    public static Timestamp timestamp(Instant instant) {
        return Timestamp.from(Objects.requireNonNull(instant, "instant"));
    }

    public static Timestamp nullableTimestamp(Instant instant) {
        return instant == null ? null : timestamp(instant);
    }
}
