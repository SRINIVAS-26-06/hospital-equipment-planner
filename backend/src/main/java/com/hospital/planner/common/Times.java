package com.hospital.planner.common;

import java.time.Instant;
import java.time.OffsetDateTime;

/** Native queries may hand back Instant, Timestamp, OffsetDateTime or Date depending on the driver; normalise to Instant. */
public final class Times {
    private Times() {}

    public static Instant instant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof OffsetDateTime d) return d.toInstant();
        if (o instanceof java.sql.Timestamp t) {
            long ms = t.getTime();
            return (ms == Long.MAX_VALUE || ms == Long.MIN_VALUE) ? null : t.toInstant();   // +/- infinity
        }
        if (o instanceof java.util.Date d) return d.toInstant();
        throw new IllegalArgumentException("not a timestamp: " + o.getClass());
    }

    public static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
