package com.hospital.planner.service;

import com.hospital.planner.common.Times;
import com.hospital.planner.entity.WorkOrder;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.UsageRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only reporting; no free-text columns (purpose, remarks, descriptions) are ever returned. */
@Service
public class AnalyticsService {
    private final EntityManager em;
    private final UsageRepository usage;
    private final Clock clock;

    public AnalyticsService(EntityManager em, UsageRepository usage, Clock clock) {
        this.em = em;
        this.usage = usage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> usage(String granularity, Instant from, Instant to, Long departmentId) {
        if (!"day".equals(granularity) && !"month".equals(granularity)) throw ApiException.bad("granularity must be day or month");
        Instant e = to == null ? clock.instant() : to;
        Instant s = from == null ? e.minus("month".equals(granularity) ? 180 : 14, ChronoUnit.DAYS) : from;
        List<Object[]> rows = "month".equals(granularity) ? usage.hoursByMonth(s, e, clock.instant(), departmentId) : usage.hoursByDay(s, e, clock.instant(), departmentId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", r[0]);
            m.put("department", r[1]);
            m.put("sessions", Times.num(r[2]));
            m.put("hours", r[3]);
            out.add(m);
        }
        return out;
    }

    private long count(String jpql, Map<String, Object> p) {
        var q = em.createQuery(jpql, Long.class);
        p.forEach(q::setParameter);
        return q.getSingleResult();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> maintenance(Instant from, Instant to) {
        Instant e = to == null ? clock.instant() : to;
        Instant s = from == null ? e.minus(90, ChronoUnit.DAYS) : from;
        Instant now = clock.instant();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("from", s);
        w.put("to", e);
        String base = "select count(m) from Maintenance m where m.startAt >= :from and m.startAt < :to and ";
        long completed = count(base + "m.status = 'COMPLETED'", w);
        long onTime = count(base + "m.status = 'COMPLETED' and m.actualEnd <= m.endAt", w);
        Map<String, Object> wn = new LinkedHashMap<>(w);
        wn.put("now", now);
        long lateOpen = count(base + "m.status in ('SCHEDULED','IN_PROGRESS') and m.endAt < :now", wn);
        long upcoming = count(base + "m.status = 'SCHEDULED' and m.startAt >= :now", wn);
        long cancelled = count(base + "m.status = 'CANCELLED'", w);
        long active = count("select count(x) from Equipment x where x.lifecycleStatus <> com.hospital.planner.entity.LifecycleStatus.RETIRED", Map.of());
        long overdue = count("select count(x) from Equipment x where x.lifecycleStatus <> com.hospital.planner.entity.LifecycleStatus.RETIRED and "
                + "(x.nextMaintenanceDue <= :now or x.usageHoursSinceMaintenance >= x.maintenanceThresholdHours)", Map.of("now", now));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("completed", completed);
        out.put("completedOnTime", onTime);
        out.put("lateOpen", lateOpen);
        out.put("upcoming", upcoming);
        out.put("cancelled", cancelled);
        out.put("activeAssets", active);
        out.put("overdueAssets", overdue);
        out.put("onTimeRate", completed == 0 ? null : (double) onTime / completed);
        out.put("overdueRate", active == 0 ? 0.0 : (double) overdue / active);
        out.put("from", s);
        out.put("to", e);
        return out;
    }

    private static Map<String, Object> pair(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> breakdowns(Instant from, Instant to) {
        Instant e = to == null ? clock.instant() : to;
        Instant s = from == null ? e.minus(180, ChronoUnit.DAYS) : from;
        List<WorkOrder> all = em.createQuery("select w from WorkOrder w where w.reportedAt >= :from and w.reportedAt < :to", WorkOrder.class)
                .setParameter("from", s).setParameter("to", e).getResultList();
        Map<String, long[]> byMonth = new TreeMap<>();
        Map<String, Long> bySeverity = new TreeMap<>();
        Map<String, Long> byDept = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int withDowntime = 0;
        long open = 0;
        for (WorkOrder w : all) {
            long[] c = byMonth.computeIfAbsent(YearMonth.from(w.reportedAt.atZone(ZoneOffset.UTC)).toString(), k -> new long[2]);
            c[0]++;
            if (w.unsafe) c[1]++;
            bySeverity.merge(w.severity, 1L, Long::sum);
            byDept.merge(w.equipment.department.name, 1L, Long::sum);
            if (!List.of("VERIFIED", "CLOSED").contains(w.status)) open++;
            if (w.downtimeHours != null) { total = total.add(w.downtimeHours); withDowntime++; }
        }
        List<Map<String, Object>> months = new ArrayList<>();
        byMonth.forEach((k, v) -> { Map<String, Object> m = pair("period", k, "reports", v[0]); m.put("unsafeReports", v[1]); months.add(m); });
        List<Map<String, Object>> sev = new ArrayList<>();
        bySeverity.forEach((k, v) -> sev.add(pair("severity", k, "reports", v)));
        List<Map<String, Object>> dept = new ArrayList<>();
        byDept.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(x -> dept.add(pair("department", x.getKey(), "reports", x.getValue())));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", (long) all.size());
        summary.put("open", open);
        summary.put("meanRepairHours", withDowntime == 0 ? null : total.divide(BigDecimal.valueOf(withDowntime), 2, RoundingMode.HALF_UP));
        summary.put("totalDowntimeHours", total.setScale(2, RoundingMode.HALF_UP));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byMonth", months);
        out.put("bySeverity", sev);
        out.put("byDepartment", dept);
        out.put("summary", summary);
        return out;
    }
}
