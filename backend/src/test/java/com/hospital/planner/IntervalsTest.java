package com.hospital.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hospital.planner.service.Intervals;
import com.hospital.planner.service.Intervals.Span;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntervalsTest {
    private static Instant h(int hour) {
        return Instant.parse("2030-01-01T00:00:00Z").plusSeconds(hour * 3600L);
    }

    @Test
    void mergeCollapsesOverlapsAndDuplicates() {
        var merged = Intervals.merge(List.of(new Span(h(0), h(4)), new Span(h(2), h(6)), new Span(h(0), h(4)),
                new Span(h(8), h(9))));
        assertEquals(2, merged.size());
        assertEquals(7 * 3600, Intervals.seconds(merged));
    }

    @Test
    void clipDropsAndTrims() {
        var clipped = Intervals.clip(List.of(new Span(h(-5), h(2)), new Span(h(30), h(40))), h(0), h(24));
        assertEquals(1, clipped.size());
        assertEquals(2 * 3600, Intervals.seconds(clipped));
    }

    @Test
    void intersection() {
        var a = Intervals.merge(List.of(new Span(h(0), h(8)), new Span(h(14), h(18))));
        var b = Intervals.merge(List.of(new Span(h(6), h(12))));
        assertEquals(2 * 3600, Intervals.intersectionSeconds(a, b));
    }
}
