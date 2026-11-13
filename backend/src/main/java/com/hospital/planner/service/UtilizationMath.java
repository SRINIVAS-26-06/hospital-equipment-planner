package com.hospital.planner.service;

import com.hospital.planner.service.Intervals.Span;
import java.time.Instant;
import java.util.List;

/** The utilization formula with no I/O. The same cases run against analytics/utilization.py (testdata/). */
public final class UtilizationMath {
    public record Raw(double totalHours, double unavailableHours, double usedHours) {
        public double availableHours() {
            return Math.max(0, totalHours - unavailableHours);
        }

        public Double utilization() {
            double avail = availableHours();
            return avail > 0 ? usedHours / avail : null;
        }
    }

    private UtilizationMath() {}

    public static Raw compute(Instant from, Instant to, List<Span> downSpans, List<Span> usedSpans) {
        List<Span> unavailable = Intervals.merge(Intervals.clip(downSpans, from, to));
        List<Span> used = Intervals.merge(Intervals.clip(usedSpans, from, to));
        long total = to.getEpochSecond() - from.getEpochSecond();
        long unavail = Intervals.seconds(unavailable);
        long usedSecs = Intervals.seconds(used) - Intervals.intersectionSeconds(used, unavailable);
        return new Raw(total / 3600.0, unavail / 3600.0, usedSecs / 3600.0);
    }
}
