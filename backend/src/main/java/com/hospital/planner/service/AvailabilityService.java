package com.hospital.planner.service;

import com.hospital.planner.entity.Equipment;
import com.hospital.planner.entity.LifecycleStatus;
import com.hospital.planner.entity.WorkOrder;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.BookingRepository;
import com.hospital.planner.repository.EquipmentRepository;
import com.hospital.planner.repository.MaintenanceRepository;
import com.hospital.planner.repository.WorkOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Locking, the calculated availability state, and the "may this equipment be booked for [start, end)?" rules. */
@Service
public class AvailabilityService {
    private final EquipmentRepository equipment;
    private final BookingRepository bookings;
    private final MaintenanceRepository maintenance;
    private final WorkOrderRepository workOrders;
    private final Clock clock;

    public AvailabilityService(EquipmentRepository equipment, BookingRepository bookings, MaintenanceRepository maintenance,
                               WorkOrderRepository workOrders, Clock clock) {
        this.equipment = equipment;
        this.bookings = bookings;
        this.maintenance = maintenance;
        this.workOrders = workOrders;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** SELECT ... FOR UPDATE: bookings, maintenance, transfers, usage and repairs on one asset run one at a time. */
    public Equipment lock(long id) {
        return equipment.lockById(id).orElseThrow(() -> ApiException.notFound("Equipment " + id));
    }

    public Equipment get(long id) {
        return equipment.findById(id).orElseThrow(() -> ApiException.notFound("Equipment " + id));
    }

    public String stateAt(long id, Instant at) {
        String s = equipment.stateAt(id, at);
        if (s == null) throw ApiException.notFound("Equipment " + id);
        return s;
    }

    public Map<Long, String> statesFor(Collection<Long> ids, Instant at) {
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object[] r : equipment.statesFor(ids, at)) out.put(((Number) r[0]).longValue(), (String) r[1]);
        return out;
    }

    /** Throws a 409 with a specific code if the equipment may not be booked for [start, end). `a` must be the locked entity. */
    public void assertBookable(Equipment a, Instant start, Instant end) {
        String tag = a.assetTag;
        if (a.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", tag + " is retired");
        if (a.lifecycleStatus == LifecycleStatus.OUT_OF_SERVICE) throw ApiException.conflict("OUT_OF_SERVICE", tag + " is out of service");
        List<WorkOrder> blocking = workOrders.findBlocking(a.id);
        if (!blocking.isEmpty()) {
            WorkOrder w = blocking.get(0);
            throw ApiException.conflict("UNDER_REPAIR", tag + (w.unsafe
                    ? " is flagged unsafe pending repair and verification (work order " + w.id + ")"
                    : " has an unresolved breakdown (work order " + w.id + ")"));
        }
        if (a.calibrationFailed) throw ApiException.conflict("CALIBRATION_FAILED", tag + " failed calibration; a passing calibration is required");
        if (a.usageHoursSinceMaintenance >= a.maintenanceThresholdHours) {
            throw ApiException.conflict("OVERDUE_MAINTENANCE", tag + " reached its usage threshold; complete maintenance first");
        }
        if (a.nextMaintenanceDue != null && end.isAfter(a.nextMaintenanceDue)) {
            throw ApiException.conflict("OVERDUE_MAINTENANCE", "Booking would run past the maintenance due date " + a.nextMaintenanceDue);
        }
        if (!maintenance.findOpenOverlapping(a.id, start, end).isEmpty()) {
            throw ApiException.conflict("MAINTENANCE_LOCKOUT", tag + " is in a maintenance window during that time");
        }
        if (!bookings.findOverlapping(a.id, com.hospital.planner.entity.BookingStatus.ACTIVE, start, end).isEmpty()) {
            throw ApiException.conflict("BOOKING_CONFLICT", tag + " is already booked during that time");
        }
    }
}
