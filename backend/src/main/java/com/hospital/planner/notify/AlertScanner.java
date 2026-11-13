package com.hospital.planner.notify;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic scan for maintenance / calibration due or overdue, and warranty / contract expiry.
 * Each alert has a dedupe key (asset + due date + audience), so a condition notifies once.
 */
@Component
public class AlertScanner {
    private static final Logger log = LoggerFactory.getLogger(AlertScanner.class);
    private final EntityManager em;
    private final Clock clock;

    public AlertScanner(EntityManager em, Clock clock) {
        this.em = em;
        this.clock = clock;
    }

    @Transactional
    @Scheduled(initialDelayString = "${planner.alerts.initial-delay-ms:60000}",
            fixedDelayString = "${planner.alerts.interval-ms:900000}")
    public void scheduled() {
        try {
            int n = scan();
            if (n > 0) log.info("alert scan created {} notifications", n);
        } catch (Exception e) {
            log.error("alert scan failed", e);
        }
    }

    private static final String ROLES = "CROSS JOIN (VALUES ('ADMIN'), ('ENGINEER')) AS r(role) ";
    private static final String INS = "INSERT INTO notifications(type, severity, title, message, entity_type, entity_id, audience_role, dedupe_key) ";

    private int exec(String sql, Map<String, Object> params) {
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q.executeUpdate();
    }

    // @Transactional
    // public int scan() {
    //     Instant now = clock.instant();
    //     Instant soon7 = now.plus(7, ChronoUnit.DAYS);
    //     Instant soon14 = now.plus(14, ChronoUnit.DAYS);
    //     java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
    //     Map<String, Object> p = new HashMap<>();
    //     p.put("now", now);
    //     p.put("soon7", soon7);
    //     p.put("soon14", soon14);
    //     p.put("today", today);
    //     p.put("in30", today.plusDays(30));
    //     int n = 0;
    //     n += exec(INS + "SELECT 'MAINTENANCE_DUE', 'WARNING', 'Maintenance due soon', "
    //             + "a.asset_tag || ' is due for maintenance on ' || to_char(a.next_maintenance_due, 'YYYY-MM-DD'), 'ASSET', a.id, r.role, "
    //             + "'MAINT_DUE:' || a.id || ':' || to_char(a.next_maintenance_due, 'YYYY-MM-DD') || ':' || r.role "
    //             + "FROM assets a " + ROLES
    //             + "WHERE a.lifecycle_status <> 'RETIRED' AND a.next_maintenance_due > :now AND a.next_maintenance_due <= :soon7 "
    //             + "ON CONFLICT (dedupe_key) DO NOTHING", p);
    //     n += exec(INS + "SELECT 'MAINTENANCE_OVERDUE', 'CRITICAL', 'Maintenance overdue', "
    //             + "a.asset_tag || ' is overdue for maintenance and cannot be booked', 'ASSET', a.id, r.role, "
    //             + "'MAINT_OVERDUE:' || a.id || ':' || COALESCE(to_char(a.hours_overdue_since, 'YYYYMMDDHH24MI'), to_char(a.next_maintenance_due, 'YYYYMMDD')) || ':' || r.role "
    //             + "FROM assets a " + ROLES
    //             + "WHERE a.lifecycle_status <> 'RETIRED' AND (a.next_maintenance_due <= :now "
    //             + "OR a.usage_hours_since_maintenance >= a.maintenance_threshold_hours) "
    //             + "ON CONFLICT (dedupe_key) DO NOTHING", p);
    //     n += exec(INS + "SELECT 'CALIBRATION_DUE', 'WARNING', 'Calibration due soon', "
    //             + "a.asset_tag || ' calibration is due on ' || to_char(a.next_calibration_due, 'YYYY-MM-DD'), 'ASSET', a.id, r.role, "
    //             + "'CAL_DUE:' || a.id || ':' || to_char(a.next_calibration_due, 'YYYY-MM-DD') || ':' || r.role "
    //             + "FROM assets a " + ROLES
    //             + "WHERE a.lifecycle_status <> 'RETIRED' AND a.calibration_required AND a.next_calibration_due > :now "
    //             + "AND a.next_calibration_due <= :soon14 ON CONFLICT (dedupe_key) DO NOTHING", p);
    //     n += exec(INS + "SELECT 'CALIBRATION_OVERDUE', 'CRITICAL', 'Calibration overdue', "
    //             + "a.asset_tag || ' calibration was due on ' || to_char(a.next_calibration_due, 'YYYY-MM-DD'), 'ASSET', a.id, r.role, "
    //             + "'CAL_OVERDUE:' || a.id || ':' || to_char(a.next_calibration_due, 'YYYY-MM-DD') || ':' || r.role "
    //             + "FROM assets a " + ROLES
    //             + "WHERE a.lifecycle_status <> 'RETIRED' AND a.calibration_required AND a.next_calibration_due <= :now "
    //             + "ON CONFLICT (dedupe_key) DO NOTHING", p);
    //     n += exec(INS + "SELECT 'WARRANTY_EXPIRY', 'WARNING', 'Warranty expiring', "
    //             + "a.asset_tag || ' warranty ends on ' || to_char(a.warranty_end, 'YYYY-MM-DD'), 'ASSET', a.id, 'ADMIN', "
    //             + "'WARRANTY:' || a.id || ':' || to_char(a.warranty_end, 'YYYY-MM-DD') "
    //             + "FROM assets a WHERE a.lifecycle_status <> 'RETIRED' AND a.warranty_end >= :today AND a.warranty_end <= :in30 "
    //             + "ON CONFLICT (dedupe_key) DO NOTHING", p);
    //     n += exec(INS + "SELECT 'CONTRACT_EXPIRY', 'WARNING', a.contract_type || ' contract expiring', "
    //             + "a.asset_tag || ' ' || a.contract_type || ' ends on ' || to_char(a.contract_end, 'YYYY-MM-DD'), 'ASSET', a.id, 'ADMIN', "
    //             + "'CONTRACT:' || a.id || ':' || to_char(a.contract_end, 'YYYY-MM-DD') "
    //             + "FROM assets a WHERE a.lifecycle_status <> 'RETIRED' AND a.contract_type <> 'NONE' "
    //             + "AND a.contract_end >= :today AND a.contract_end <= :in30 ON CONFLICT (dedupe_key) DO NOTHING", p);
    //     return n;
    // }

    @Transactional
    public int scan() {
        Instant now = clock.instant();
        Instant soon7 = now.plus(7, ChronoUnit.DAYS);
        Instant soon14 = now.plus(14, ChronoUnit.DAYS);

        java.time.LocalDate today =
                java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);

        int n = 0;

        // 1. Maintenance due within 7 days
        n += exec(INS + "SELECT 'MAINTENANCE_DUE', 'WARNING', 'Maintenance due soon', "
                + "a.asset_tag || ' is due for maintenance on ' || to_char(a.next_maintenance_due, 'YYYY-MM-DD'), 'ASSET', a.id, r.role, "
                + "'MAINT_DUE:' || a.id || ':' || to_char(a.next_maintenance_due, 'YYYY-MM-DD') || ':' || r.role "
                + "FROM assets a " + ROLES
                + "WHERE a.lifecycle_status <> 'RETIRED' AND a.next_maintenance_due > :now AND a.next_maintenance_due <= :soon7 "
                + "ON CONFLICT (dedupe_key) DO NOTHING",
                Map.of("now", now, "soon7", soon7));

        // 2. Maintenance overdue
        n += exec(INS + "SELECT 'MAINTENANCE_OVERDUE', 'CRITICAL', 'Maintenance overdue', "
                + "a.asset_tag || ' is overdue for maintenance and cannot be booked', 'ASSET', a.id, r.role, "
                + "'MAINT_OVERDUE:' || a.id || ':' || COALESCE(to_char(a.hours_overdue_since, 'YYYYMMDDHH24MI'), to_char(a.next_maintenance_due, 'YYYYMMDD')) || ':' || r.role "
                + "FROM assets a " + ROLES
                + "WHERE a.lifecycle_status <> 'RETIRED' AND (a.next_maintenance_due <= :now "
                + "OR a.usage_hours_since_maintenance >= a.maintenance_threshold_hours) "
                + "ON CONFLICT (dedupe_key) DO NOTHING",
                Map.of("now", now));

        // 3. Calibration due within 14 days
        n += exec(INS + "SELECT 'CALIBRATION_DUE', 'WARNING', 'Calibration due soon', "
                + "a.asset_tag || ' calibration is due on ' || to_char(a.next_calibration_due, 'YYYY-MM-DD'), 'ASSET', a.id, r.role, "
                + "'CAL_DUE:' || a.id || ':' || to_char(a.next_calibration_due, 'YYYY-MM-DD') || ':' || r.role "
                + "FROM assets a " + ROLES
                + "WHERE a.lifecycle_status <> 'RETIRED' AND a.calibration_required AND a.next_calibration_due > :now "
                + "AND a.next_calibration_due <= :soon14 ON CONFLICT (dedupe_key) DO NOTHING",
                Map.of("now", now, "soon14", soon14));

        // 4. Calibration overdue
        n += exec(INS + "SELECT 'CALIBRATION_OVERDUE', 'CRITICAL', 'Calibration overdue', "
                + "a.asset_tag || ' calibration was due on ' || to_char(a.next_calibration_due, 'YYYY-MM-DD'), 'ASSET', a.id, r.role, "
                + "'CAL_OVERDUE:' || a.id || ':' || to_char(a.next_calibration_due, 'YYYY-MM-DD') || ':' || r.role "
                + "FROM assets a " + ROLES
                + "WHERE a.lifecycle_status <> 'RETIRED' AND a.calibration_required AND a.next_calibration_due <= :now "
                + "ON CONFLICT (dedupe_key) DO NOTHING",
                Map.of("now", now));

        // 5. Warranty expiry within 30 days
        n += exec(INS + "SELECT 'WARRANTY_EXPIRY', 'WARNING', 'Warranty expiring', "
                + "a.asset_tag || ' warranty ends on ' || to_char(a.warranty_end, 'YYYY-MM-DD'), 'ASSET', a.id, 'ADMIN', "
                + "'WARRANTY:' || a.id || ':' || to_char(a.warranty_end, 'YYYY-MM-DD') "
                + "FROM assets a WHERE a.lifecycle_status <> 'RETIRED' AND a.warranty_end >= :today AND a.warranty_end <= :in30 "
                + "ON CONFLICT (dedupe_key) DO NOTHING",
                Map.of("today", today, "in30", today.plusDays(30)));

        // 6. Contract expiry within 30 days
        n += exec(INS + "SELECT 'CONTRACT_EXPIRY', 'WARNING', a.contract_type || ' contract expiring', "
                + "a.asset_tag || ' ' || a.contract_type || ' ends on ' || to_char(a.contract_end, 'YYYY-MM-DD'), 'ASSET', a.id, 'ADMIN', "
                + "'CONTRACT:' || a.id || ':' || to_char(a.contract_end, 'YYYY-MM-DD') "
                + "FROM assets a WHERE a.lifecycle_status <> 'RETIRED' AND a.contract_type <> 'NONE' "
                + "AND a.contract_end >= :today AND a.contract_end <= :in30 "
                + "ON CONFLICT (dedupe_key) DO NOTHING",
                Map.of("today", today, "in30", today.plusDays(30)));

        return n;
    }

}
