package com.hospital.planner.controller;

import com.hospital.planner.service.AnalyticsService;
import com.hospital.planner.service.UtilizationService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final UtilizationService utilization;
    private final AnalyticsService analytics;
    private final Clock clock;

    public AnalyticsController(UtilizationService utilization, AnalyticsService analytics, Clock clock) {
        this.utilization = utilization;
        this.analytics = analytics;
        this.clock = clock;
    }

    /** groupBy = department | asset. Defaults to the last 7 days. */
    @GetMapping("/utilization")
    public List<UtilizationService.UtilRow> utilization(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                        @RequestParam(defaultValue = "department") String groupBy, @RequestParam(required = false) Long departmentId) {
        Instant e = to == null ? clock.instant() : to;
        Instant s = from == null ? e.minus(7, ChronoUnit.DAYS) : from;
        return "asset".equalsIgnoreCase(groupBy) ? utilization.byAsset(s, e, departmentId) : utilization.byDepartment(s, e);
    }

    @GetMapping("/utilization/daily")
    public List<Map<String, Object>> daily(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                           @RequestParam(required = false) Long departmentId) {
        Instant e = to == null ? clock.instant() : to;
        return utilization.daily(from == null ? e.minus(14, ChronoUnit.DAYS) : from, e, departmentId);
    }

    @GetMapping("/usage")
    public List<Map<String, Object>> usage(@RequestParam(defaultValue = "day") String granularity, @RequestParam(required = false) Instant from,
                                           @RequestParam(required = false) Instant to, @RequestParam(required = false) Long departmentId) {
        return analytics.usage(granularity, from, to, departmentId);
    }

    @GetMapping("/maintenance")
    public Map<String, Object> maintenance(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return analytics.maintenance(from, to);
    }

    @GetMapping("/breakdowns")
    public Map<String, Object> breakdowns(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return analytics.breakdowns(from, to);
    }
}
