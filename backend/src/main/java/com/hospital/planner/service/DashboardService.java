package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.entity.*;
import com.hospital.planner.repository.EquipmentRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Role-aware dashboard. The UI polls summary() often and charts() less often (charts() is cached for 60 s). */
@Service
public class DashboardService {
    private record Cached(Instant at, Map<String, Object> value) {}

    private final EntityManager em;
    private final EquipmentRepository equipment;
    private final UtilizationService utilization;
    private final AnalyticsService analytics;
    private final Clock clock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public DashboardService(EntityManager em, EquipmentRepository equipment, UtilizationService utilization, AnalyticsService analytics, Clock clock) {
        this.em = em;
        this.equipment = equipment;
        this.utilization = utilization;
        this.analytics = analytics;
        this.clock = clock;
    }

    private long count(String jpql, Object... kv) {
        var q = em.createQuery(jpql, Long.class);
        for (int i = 0; i + 1 < kv.length; i += 2) q.setParameter((String) kv[i], kv[i + 1]);
        return q.getSingleResult();
    }

    private <T> List<T> top(String jpql, Class<T> type, int max, Object... kv) {
        var q = em.createQuery(jpql, type).setMaxResults(max);
        for (int i = 0; i + 1 < kv.length; i += 2) q.setParameter((String) kv[i], kv[i + 1]);
        return q.getResultList();
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Instant now = clock.instant();
        Instant in14 = now.plus(14, ChronoUnit.DAYS);
        Instant in30 = now.plus(30, ChronoUnit.DAYS);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String me = CurrentUser.username();
        String retired = "com.hospital.planner.entity.LifecycleStatus.RETIRED";
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Long> states = new HashMap<>();
        for (Object[] r : equipment.countByState(now)) states.put((String) r[0], ((Number) r[1]).longValue());
        long ret = states.getOrDefault("RETIRED", 0L);
        out.put("equipment", m("total", states.values().stream().mapToLong(Long::longValue).sum() - ret, "available", states.getOrDefault("AVAILABLE", 0L),
                "booked", states.getOrDefault("BOOKED", 0L), "inUse", states.getOrDefault("IN_USE", 0L),
                "underMaintenance", states.getOrDefault("UNDER_MAINTENANCE", 0L), "overdueMaintenance", states.getOrDefault("OVERDUE_MAINTENANCE", 0L),
                "underRepair", states.getOrDefault("UNDER_REPAIR", 0L), "calibrationFailed", states.getOrDefault("CALIBRATION_FAILED", 0L),
                "outOfService", states.getOrDefault("OUT_OF_SERVICE", 0L), "retired", ret));

        out.put("kpis", m(
                "activeBookings", count("select count(b) from Booking b where b.status = com.hospital.planner.entity.BookingStatus.ACTIVE and b.startAt <= :now and b.endAt > :now", "now", now),
                "upcomingBookings", count("select count(b) from Booking b where b.status = com.hospital.planner.entity.BookingStatus.ACTIVE and b.startAt > :now", "now", now),
                "pendingBreakdowns", count("select count(w) from WorkOrder w where w.status in ('REPORTED','TRIAGED')"),
                "openWorkOrders", count("select count(w) from WorkOrder w where w.status not in ('VERIFIED','CLOSED')"),
                "upcomingMaintenance", count("select count(x) from Maintenance x where x.status = 'SCHEDULED' and x.startAt >= :now and x.startAt <= :in14", "now", now, "in14", in14),
                "maintenanceDueSoon", count("select count(e) from Equipment e where e.lifecycleStatus <> " + retired + " and e.nextMaintenanceDue > :now and e.nextMaintenanceDue <= :in14", "now", now, "in14", in14),
                "calibrationDue", count("select count(e) from Equipment e where e.lifecycleStatus <> " + retired + " and e.calibrationRequired = true and e.nextCalibrationDue <= :in30", "in30", in30),
                "warrantyExpiring", count("select count(e) from Equipment e where e.lifecycleStatus <> " + retired + " and e.warrantyEnd between :today and :in30d", "today", today, "in30d", today.plusDays(30)),
                "contractsExpiring", count("select count(e) from Equipment e where e.lifecycleStatus <> " + retired + " and e.contractType <> 'NONE' and e.contractEnd between :today and :in30d", "today", today, "in30d", today.plusDays(30))));

        out.put("upcomingMaintenance", top("select x from Maintenance x where x.status = 'SCHEDULED' and x.startAt >= :now order by x.startAt", Maintenance.class, 8, "now", now).stream()
                .map(x -> m("id", x.id, "assetTag", x.equipment.assetTag, "assetName", x.equipment.name, "startAt", x.startAt, "priority", x.priority, "assignedEngineer", x.assignedEngineer)).toList());
        out.put("overdueAssets", top("select e from Equipment e where e.lifecycleStatus <> " + retired + " and (e.nextMaintenanceDue <= :now or e.usageHoursSinceMaintenance >= e.maintenanceThresholdHours) "
                + "order by e.nextMaintenanceDue", Equipment.class, 8, "now", now).stream()
                .map(e -> m("id", e.id, "assetTag", e.assetTag, "name", e.name, "nextMaintenanceDue", e.nextMaintenanceDue, "usageHoursSinceMaintenance", e.usageHoursSinceMaintenance,
                        "maintenanceThresholdHours", e.maintenanceThresholdHours)).toList());
        out.put("calibrationDueList", top("select e from Equipment e where e.lifecycleStatus <> " + retired + " and e.calibrationRequired = true and (e.nextCalibrationDue <= :in30 or e.calibrationFailed = true) "
                + "order by e.nextCalibrationDue nulls first", Equipment.class, 8, "in30", in30).stream()
                .map(e -> m("id", e.id, "assetTag", e.assetTag, "name", e.name, "nextCalibrationDue", e.nextCalibrationDue, "calibrationFailed", e.calibrationFailed)).toList());
        String staff = CurrentUser.is("STAFF") ? " and w.reportedBy = :me" : "";
        Object[] woParams = CurrentUser.is("STAFF") ? new Object[]{"me", me} : new Object[0];
        out.put("openWorkOrders", top("select w from WorkOrder w where w.status not in ('VERIFIED','CLOSED')" + staff
                + " order by case w.priority when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end, w.reportedAt", WorkOrder.class, 8, woParams).stream()
                .map(w -> m("id", w.id, "assetTag", w.equipment.assetTag, "title", w.title, "status", w.status, "priority", w.priority, "severity", w.severity, "unsafe", w.unsafe,
                        "assignedEngineer", w.assignedEngineer, "reportedAt", w.reportedAt)).toList());

        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("maintenance", top("select x from Maintenance x where x.assignedEngineer = :me and x.status in ('SCHEDULED','IN_PROGRESS') order by x.startAt", Maintenance.class, 10, "me", me).stream()
                .map(x -> m("id", x.id, "assetTag", x.equipment.assetTag, "assetName", x.equipment.name, "status", x.status, "startAt", x.startAt, "priority", x.priority)).toList());
        mine.put("calibrations", top("select c from Calibration c where c.assignedEngineer = :me and c.status = 'SCHEDULED' order by c.scheduledFor", Calibration.class, 10, "me", me).stream()
                .map(c -> m("id", c.id, "assetTag", c.equipment.assetTag, "assetName", c.equipment.name, "scheduledFor", c.scheduledFor)).toList());
        mine.put("workOrders", top("select w from WorkOrder w where w.assignedEngineer = :me and w.status in ('ASSIGNED','IN_PROGRESS','RESOLVED') order by w.reportedAt", WorkOrder.class, 10, "me", me).stream()
                .map(w -> m("id", w.id, "assetTag", w.equipment.assetTag, "title", w.title, "status", w.status, "priority", w.priority)).toList());
        mine.put("bookings", top("select b from Booking b where b.requestedBy = :me and b.status = com.hospital.planner.entity.BookingStatus.ACTIVE and b.endAt > :now order by b.startAt", Booking.class, 10, "me", me, "now", now).stream()
                .map(b -> m("id", b.id, "assetTag", b.equipment.assetTag, "assetName", b.equipment.name, "startAt", b.startAt, "endAt", b.endAt)).toList());
        mine.put("activeSessions", top("select u from Usage u where u.usedBy = :me and u.endAt is null", Usage.class, 10, "me", me).stream()
                .map(u -> m("id", u.id, "assetTag", u.equipment.assetTag, "assetName", u.equipment.name, "startAt", u.startAt)).toList());
        mine.put("reportedBreakdowns", top("select w from WorkOrder w where w.reportedBy = :me and w.status <> 'CLOSED' order by w.reportedAt desc", WorkOrder.class, 10, "me", me).stream()
                .map(w -> m("id", w.id, "assetTag", w.equipment.assetTag, "title", w.title, "status", w.status)).toList());
        out.put("mine", mine);
        out.put("generatedAt", now);
        return out;
    }

    /** Chart datasets: department utilization, utilization trend, usage by day/month, breakdowns, maintenance. */
    public Map<String, Object> charts() {
        Instant now = clock.instant();
        Cached c = cache.get("charts");
        if (c != null && c.at().isAfter(now.minusSeconds(60))) return c.value();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("departmentUtilization", utilization.byDepartment(now.minus(7, ChronoUnit.DAYS), now));
        out.put("utilizationTrend", utilization.daily(now.minus(14, ChronoUnit.DAYS), now, null));
        out.put("usageDaily", analytics.usage("day", null, null, null));
        out.put("usageMonthly", analytics.usage("month", null, null, null));
        out.put("breakdowns", analytics.breakdowns(null, null));
        out.put("maintenance", analytics.maintenance(null, null));
        out.put("generatedAt", now);
        cache.put("charts", new Cached(now, out));
        return out;
    }
}
