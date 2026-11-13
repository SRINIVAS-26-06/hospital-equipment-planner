package com.hospital.planner.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure interval maths used by utilization. No I/O so it can be unit tested. */
public final class Intervals {
    public record Span(Instant start, Instant end) {}

    private Intervals() {}

    public static List<Span> clip(List<Span> in, Instant from, Instant to) {
        List<Span> out = new ArrayList<>();
        for (Span s : in) {
            Instant a = s.start().isAfter(from) ? s.start() : from;
            Instant b = s.end().isBefore(to) ? s.end() : to;
            if (b.isAfter(a)) out.add(new Span(a, b));
        }
        return out;
    }

    /** Union of spans: overlapping/adjacent spans collapse, so duplicates never double count. */
    public static List<Span> merge(List<Span> in) {
        List<Span> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparing(Span::start));
        List<Span> out = new ArrayList<>();
        for (Span s : sorted) {
            if (!out.isEmpty() && !s.start().isAfter(out.get(out.size() - 1).end())) {
                Span last = out.remove(out.size() - 1);
                out.add(new Span(last.start(), s.end().isAfter(last.end()) ? s.end() : last.end()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    public static long seconds(List<Span> merged) {
        long total = 0;
        for (Span s : merged) total += s.end().getEpochSecond() - s.start().getEpochSecond();
        return total;
    }

    /** Both inputs must already be merged (sorted, disjoint). */
    public static long intersectionSeconds(List<Span> a, List<Span> b) {
        long total = 0;
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            Instant lo = a.get(i).start().isAfter(b.get(j).start()) ? a.get(i).start() : b.get(j).start();
            Instant hi = a.get(i).end().isBefore(b.get(j).end()) ? a.get(i).end() : b.get(j).end();
            if (hi.isAfter(lo)) total += hi.getEpochSecond() - lo.getEpochSecond();
            if (a.get(i).end().isBefore(b.get(j).end())) i++; else j++;
        }
        return total;
    }
}
