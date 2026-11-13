package com.hospital.planner.service;

import com.hospital.planner.common.Times;
import com.hospital.planner.entity.*;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.DepartmentHistoryDao;
import com.hospital.planner.repository.DepartmentRepository;
import com.hospital.planner.repository.UsageRepository;
import com.hospital.planner.service.Intervals.Span;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * utilization = used hours / available hours, where available = in-service time minus unavailable time (maintenance - scheduled windows
 * only once they start, in-progress until completed -, downtime incl. out-of-service and overdue lockout, breakdowns - unsafe ones until
 * verified -, and failed-calibration time). Used time that overlaps unavailable time is not counted. Idle is not unavailable.
 * Every hour is attributed to the department that owned the equipment at that hour; department figures are ratio-of-sums.
 */
@Service
public class UtilizationService {
    public record UtilRow(String key, String name, double totalHours, double unavailableHours, double availableHours,
                          double usedHours, double idleHours, Double utilization, String flag) {}

    private record Period(long deptId, String deptName, Instant from, Instant to) {}

    private static final class Data {
        Equipment e;
        final List<Span> used = new ArrayList<>();
        final List<Span> down = new ArrayList<>();
        final List<Period> periods = new ArrayList<>();
    }

    private final EntityManager em;
    private final UsageRepository usage;
    private final DepartmentRepository departments;
    private final DepartmentHistoryDao history;
    private final Clock clock;
    private final double underBelow;
    private final double overAbove;

    public UtilizationService(EntityManager em, UsageRepository usage, DepartmentRepository departments, DepartmentHistoryDao history, Clock clock,
                              @Value("${planner.underused-below:0.30}") double underBelow, @Value("${planner.overworked-above:0.85}") double overAbove) {
        this.em = em;
        this.usage = usage;
        this.departments = departments;
        this.history = history;
        this.clock = clock;
        this.underBelow = underBelow;
        this.overAbove = overAbove;
    }

    private static void add(List<Span> spans, Instant s, Instant e) {
        if (s != null && e != null && e.isAfter(s)) spans.add(new Span(s, e));
    }

    private Map<Long, Data> load(Instant from, Instant to) {
        Instant now = clock.instant();
        Map<Long, Data> m = new LinkedHashMap<>();
        for (Equipment e : em.createQuery("select e from Equipment e order by e.assetTag", Equipment.class).getResultList()) {
            Data d = new Data();
            d.e = e;
            m.put(e.id, d);
            // overdue lockout that is still running (finished ones are stored as OVERDUE_LOCKOUT downtime events)
            Instant byDate = e.nextMaintenanceDue != null && e.nextMaintenanceDue.isBefore(now) ? e.nextMaintenanceDue : null;
            Instant od = byDate == null ? e.hoursOverdueSince : (e.hoursOverdueSince == null || byDate.isBefore(e.hoursOverdueSince) ? byDate : e.hoursOverdueSince);
            if (od != null) add(d.down, od, now);
        }
        for (Usage u : usage.findOverlappingWindow(from, to)) {
            Data d = m.get(u.equipment.id);
            if (d != null) add(d.used, u.startAt, u.endAt == null ? now : u.endAt);
        }
        for (Maintenance mt : em.createQuery("select m from Maintenance m where m.status <> 'CANCELLED' and (m.startAt < :to or m.actualStart < :to)", Maintenance.class)
                .setParameter("to", to).getResultList()) {
            Data d = m.get(mt.equipment.id);
            if (d == null) continue;
            switch (mt.status) {
                case "COMPLETED" -> add(d.down, mt.actualStart != null ? mt.actualStart : mt.startAt, mt.actualEnd != null ? mt.actualEnd : mt.endAt);
                case "IN_PROGRESS" -> {
                    Instant s = mt.actualStart != null ? mt.actualStart : mt.startAt;
                    add(d.down, s, now.isAfter(s) ? now : s);
                }
                default -> add(d.down, mt.startAt, mt.endAt.isBefore(now) ? mt.endAt : now);   // planned: only once it has started
            }
        }
        for (DowntimeEvent dt : em.createQuery("select d from DowntimeEvent d where d.startAt < :to and (d.endAt is null or d.endAt > :from)", DowntimeEvent.class)
                .setParameter("to", to).setParameter("from", from).getResultList()) {
            Data d = m.get(dt.equipment.id);
            if (d != null) add(d.down, dt.startAt, dt.endAt == null ? now : dt.endAt);
        }
        for (WorkOrder w : em.createQuery("select w from WorkOrder w where w.reportedAt < :to", WorkOrder.class).setParameter("to", to).getResultList()) {
            Data d = m.get(w.equipment.id);
            if (d == null) continue;
            Instant end = List.of("REPORTED", "TRIAGED", "ASSIGNED", "IN_PROGRESS").contains(w.status) ? now
                    : (w.unsafe ? (w.verifiedAt != null ? w.verifiedAt : now) : (w.resolvedAt != null ? w.resolvedAt : now));
            add(d.down, w.reportedAt, end);
        }
        List<Calibration> done = em.createQuery("select c from Calibration c where c.status = 'COMPLETED' and c.performedAt is not null", Calibration.class).getResultList();
        for (Calibration c : done) {
            if (!"FAIL".equals(c.result)) continue;
            Data d = m.get(c.equipment.id);
            if (d == null) continue;
            Instant end = done.stream().filter(p -> p.equipment.id.equals(c.equipment.id) && "PASS".equals(p.result) && p.performedAt.isAfter(c.performedAt))
                    .map(p -> p.performedAt).min(Instant::compareTo).orElse(now);
            add(d.down, c.performedAt, end);
        }
        for (Object[] r : history.periods(from, to)) {
            Data d = m.get(Times.num(r[0]));
            Instant f = Times.instant(r[3]);
            Instant t = Times.instant(r[4]);
            if (d != null && f != null && t != null && t.isAfter(f)) d.periods.add(new Period(Times.num(r[1]), (String) r[2], f, t));
        }
        return m;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private interface Sink {
        void accept(Data asset, Period period, UtilizationMath.Raw raw);
    }

    private void measure(Map<Long, Data> data, Instant from, Instant to, Sink sink) {
        for (Data d : data.values()) {
            Instant lo = d.e.inServiceFrom == null ? from : max(from, d.e.inServiceFrom);
            Instant hi = d.e.retiredAt == null ? to : min(to, d.e.retiredAt);
            for (Period pr : d.periods) {
                Instant a = max(lo, pr.from());
                Instant b = min(hi, pr.to());
                if (b.isAfter(a)) sink.accept(d, pr, UtilizationMath.compute(a, b, d.down, d.used));
            }
        }
    }

    private UtilRow row(String key, String name, double total, double unavailable, double used) {
        double available = Math.max(0, total - unavailable);
        Double util = available > 0 ? used / available : null;
        String flag = util == null ? "NO_AVAILABLE_TIME" : util < underBelow ? "UNDERUSED" : util > overAbove ? "OVERWORKED" : "NORMAL";
        return new UtilRow(key, name, total, unavailable, available, used, available - used, util, flag);
    }

    private static void check(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) throw ApiException.bad("'to' must be after 'from'");
        if (Duration.between(from, to).toDays() > 800) throw ApiException.bad("The reporting window cannot exceed 800 days");
    }

    @Transactional(readOnly = true)
    public List<UtilRow> byAsset(Instant from, Instant to, Long departmentId) {
        check(from, to);
        Map<Long, double[]> acc = new HashMap<>();
        Map<Long, Data> meta = load(from, to);
        measure(meta, from, to, (a, pr, raw) -> {
            if (departmentId != null && pr.deptId() != departmentId) return;
            double[] s = acc.computeIfAbsent(a.e.id, k -> new double[3]);
            s[0] += raw.totalHours();
            s[1] += raw.unavailableHours();
            s[2] += raw.usedHours();
        });
        List<UtilRow> out = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : acc.entrySet()) {
            Equipment q = meta.get(e.getKey()).e;
            out.add(row(q.assetTag, q.name, e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        }
        out.sort((x, y) -> x.key().compareTo(y.key()));
        return out;
    }

    @Transactional(readOnly = true)
    public List<UtilRow> byDepartment(Instant from, Instant to) {
        check(from, to);
        Map<Long, double[]> acc = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (Department d : departments.findByActiveTrueOrderByName()) {
            acc.put(d.id, new double[3]);
            names.put(d.id, d.name);
        }
        measure(load(from, to), from, to, (a, pr, raw) -> {
            double[] s = acc.computeIfAbsent(pr.deptId(), k -> new double[3]);
            names.putIfAbsent(pr.deptId(), pr.deptName());
            s[0] += raw.totalHours();
            s[1] += raw.unavailableHours();
            s[2] += raw.usedHours();
        });
        List<UtilRow> out = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : acc.entrySet()) out.add(row(String.valueOf(e.getKey()), names.get(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        out.sort((x, y) -> x.name().compareTo(y.name()));
        return out;
    }

    /** One row per UTC day: pooled utilization of all equipment (or one department) for that day. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> daily(Instant from, Instant to, Long departmentId) {
        check(from, to);
        if (Duration.between(from, to).toDays() > 400) throw ApiException.bad("Daily trend is limited to 400 days");
        Map<Long, Data> data = load(from, to);
        List<Map<String, Object>> out = new ArrayList<>();
        Instant day = LocalDate.ofInstant(from, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        while (day.isBefore(to)) {
            Instant lo = max(from, day);
            Instant hi = min(to, day.plus(Duration.ofDays(1)));
            double[] s = new double[3];
            measure(data, lo, hi, (a, pr, raw) -> {
                if (departmentId != null && pr.deptId() != departmentId) return;
                s[0] += raw.totalHours();
                s[1] += raw.unavailableHours();
                s[2] += raw.usedHours();
            });
            double available = Math.max(0, s[0] - s[1]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", LocalDate.ofInstant(day, ZoneOffset.UTC).toString());
            m.put("usedHours", Math.round(s[2] * 100) / 100.0);
            m.put("availableHours", Math.round(available * 100) / 100.0);
            m.put("unavailableHours", Math.round(s[1] * 100) / 100.0);
            m.put("utilization", available > 0 ? s[2] / available : null);
            out.add(m);
            day = day.plus(Duration.ofDays(1));
        }
        return out;
    }
}
