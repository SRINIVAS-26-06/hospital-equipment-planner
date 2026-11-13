package com.hospital.planner.service;

import java.util.Set;

/**
 * Availability is CALCULATED, never stored. Lifecycle (ACTIVE / OUT_OF_SERVICE / RETIRED) is stored.
 * First matching rule wins. Needs a bound :now parameter and the assets table aliased as "a".
 *
 * A SCHEDULED maintenance window only counts once its start time has arrived, so future maintenance
 * never makes equipment unavailable early. An IN_PROGRESS one counts until it is completed.
 */
public final class Availability {
    private Availability() {}

    public static final String BLOCKING_WORK_ORDER =
            "EXISTS (SELECT 1 FROM work_orders w WHERE w.asset_id = a.id AND (w.status IN ('REPORTED','TRIAGED','ASSIGNED','IN_PROGRESS') "
                    + "OR (w.unsafe AND w.status = 'RESOLVED')))";

    public static final String STATE_SQL = "CASE "
            + "WHEN a.lifecycle_status = 'RETIRED' THEN 'RETIRED' "
            + "WHEN a.lifecycle_status = 'OUT_OF_SERVICE' THEN 'OUT_OF_SERVICE' "
            + "WHEN " + BLOCKING_WORK_ORDER + " THEN 'UNDER_REPAIR' "
            + "WHEN a.calibration_failed THEN 'CALIBRATION_FAILED' "
            + "WHEN EXISTS (SELECT 1 FROM maintenance_windows m WHERE m.asset_id = a.id AND (m.status = 'IN_PROGRESS' "
            + "OR (m.status = 'SCHEDULED' AND m.start_at <= :now AND m.end_at > :now))) THEN 'UNDER_MAINTENANCE' "
            + "WHEN a.usage_hours_since_maintenance >= a.maintenance_threshold_hours "
            + "OR (a.next_maintenance_due IS NOT NULL AND a.next_maintenance_due <= :now) THEN 'OVERDUE_MAINTENANCE' "
            + "WHEN EXISTS (SELECT 1 FROM usage_events u WHERE u.asset_id = a.id AND u.end_at IS NULL AND u.start_at <= :now) THEN 'IN_USE' "
            + "WHEN EXISTS (SELECT 1 FROM bookings b WHERE b.asset_id = a.id AND b.status = 'ACTIVE' AND b.start_at <= :now AND b.end_at > :now) THEN 'BOOKED' "
            + "ELSE 'AVAILABLE' END";

    /** States in which new work cannot be assigned to the equipment until something changes. */
    public static final Set<String> HARD_BLOCKED =
            Set.of("RETIRED", "OUT_OF_SERVICE", "UNDER_REPAIR", "CALIBRATION_FAILED", "OVERDUE_MAINTENANCE");

    public static boolean hardBlocked(String state) {
        return HARD_BLOCKED.contains(state);
    }
}
