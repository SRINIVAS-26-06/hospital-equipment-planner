package com.hospital.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.planner.service.Intervals.Span;
import com.hospital.planner.service.UtilizationMath;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs the shared fixture (testdata/utilization_cases.json); the Python suite runs the same file. */
class UtilizationMathTest {
    private static final Instant START = Instant.parse("2031-01-01T00:00:00Z");

    private static List<Span> spans(JsonNode arr) {
        List<Span> out = new ArrayList<>();
        for (JsonNode s : arr) {
            out.add(new Span(START.plusSeconds(s.get(0).asLong() * 3600), START.plusSeconds(s.get(1).asLong() * 3600)));
        }
        return out;
    }

    @Test
    void sharedFixtureCases() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/utilization_cases.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            Instant end = START.plusSeconds(root.get("windowHours").asLong() * 3600);
            for (JsonNode c : root.get("cases")) {
                String name = c.get("name").asText();
                var raw = UtilizationMath.compute(START, end, spans(c.get("down")), spans(c.get("used")));
                JsonNode e = c.get("expected");
                assertEquals(e.get("unavailable").asDouble(), raw.unavailableHours(), 1e-9, name + " unavailable");
                assertEquals(e.get("available").asDouble(), raw.availableHours(), 1e-9, name + " available");
                assertEquals(e.get("used").asDouble(), raw.usedHours(), 1e-9, name + " used");
                if (e.get("utilization").isNull()) {
                    assertNull(raw.utilization(), name);
                } else {
                    assertEquals(e.get("utilization").asDouble(), raw.utilization(), 1e-9, name);
                }
            }
        }
    }
}
