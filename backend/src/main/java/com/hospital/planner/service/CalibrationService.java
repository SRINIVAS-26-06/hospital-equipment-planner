package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.CalibrationDto;
import com.hospital.planner.entity.*;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.CalibrationRepository;
import com.hospital.planner.repository.EquipmentRepository;
import com.hospital.planner.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCHEDULED -> COMPLETED(PASS|FAIL) (or CANCELLED). A FAIL restricts the equipment (CALIBRATION_FAILED) and cancels affected bookings
 * until a later PASS is recorded.
 */
@Service
public class CalibrationService {
    private final CalibrationRepository calibrations;
    private final EquipmentRepository equipment;
    private final UserRepository users;
    private final AvailabilityService availability;
    private final BookingService bookings;
    private final AuditService audit;
    private final NotificationService notify;
    private final EntityManager em;
    private final Clock clock;

    public CalibrationService(CalibrationRepository calibrations, EquipmentRepository equipment, UserRepository users, AvailabilityService availability,
                              BookingService bookings, AuditService audit, NotificationService notify, EntityManager em, Clock clock) {
        this.calibrations = calibrations;
        this.equipment = equipment;
        this.users = users;
        this.availability = availability;
        this.bookings = bookings;
        this.audit = audit;
        this.notify = notify;
        this.em = em;
        this.clock = clock;
    }

    private Calibration lockedLoad(long id) {
        Calibration c = calibrations.findById(id).orElseThrow(() -> ApiException.notFound("Calibration " + id));
        availability.lock(c.equipment.id);
        em.refresh(c);
        return c;
    }

    private void requireEngineer(String username) {
        if (username != null && !users.existsByUsernameAndRoleAndActiveTrue(username, "ENGINEER")) {
            throw ApiException.unprocessable("UNKNOWN_ENGINEER", "'" + username + "' is not an active engineer");
        }
    }

    private void assertActor(Calibration c, String what) {
        if (!CurrentUser.isAdmin() && !CurrentUser.username().equals(c.assignedEngineer)) throw ApiException.forbidden("Only the assigned engineer or an ADMIN can " + what);
    }

    CalibrationDto dto(Calibration c) {
        boolean actor = CurrentUser.isAdmin() || CurrentUser.username().equals(c.assignedEngineer);
        boolean open = "SCHEDULED".equals(c.status);
        return new CalibrationDto(c.id, c.equipment.id, c.scheduledFor, c.status, c.assignedEngineer, c.performedAt, c.result, c.certificateNumber, c.remarks,
                c.nextDueDate, c.createdBy, c.createdAt, c.equipment.assetTag, c.equipment.name, c.equipment.department.name, actor && open, actor && open,
                (CurrentUser.isAdmin() || CurrentUser.is("ENGINEER")) && open && c.assignedEngineer == null);
    }

    @Transactional(readOnly = true)
    public CalibrationDto get(long id) {
        return dto(calibrations.findById(id).orElseThrow(() -> ApiException.notFound("Calibration " + id)));
    }

    @Transactional
    public CalibrationDto schedule(long assetId, Instant scheduledFor, String engineer) {
        Instant now = clock.instant();
        if (scheduledFor == null) throw ApiException.bad("scheduledFor is required");
        if (scheduledFor.isBefore(now.minus(5, ChronoUnit.MINUTES))) throw ApiException.unprocessable("PAST_SCHEDULE", "Calibration cannot be scheduled in the past");
        String assignee = engineer;
        if (CurrentUser.is("ENGINEER")) {
            if (assignee != null && !assignee.equals(CurrentUser.username())) throw ApiException.forbidden("Engineers can only assign calibration to themselves");
            assignee = CurrentUser.username();
        }
        requireEngineer(assignee);
        Equipment a = availability.lock(assetId);
        if (a.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", a.assetTag + " is retired");
        Calibration c = new Calibration();
        c.equipment = a;
        c.scheduledFor = scheduledFor;
        c.assignedEngineer = assignee;
        c.createdBy = CurrentUser.username();
        calibrations.saveAndFlush(c);
        em.refresh(c);
        audit.log("CALIBRATION_SCHEDULED", "CALIBRATION", c.id, null, Map.of("assetId", assetId, "scheduledFor", scheduledFor.toString(), "engineer", String.valueOf(assignee)));
        notify.toUser(assignee, "CALIBRATION_ASSIGNED", "INFO", "Calibration assigned", a.assetTag + " calibration scheduled for " + scheduledFor, "CALIBRATION", c.id, null);
        return dto(c);
    }

    @Transactional
    public CalibrationDto assign(long id, String engineer) {
        Calibration c = lockedLoad(id);
        if (!"SCHEDULED".equals(c.status)) throw ApiException.conflict("INVALID_STATE", "Calibration is " + c.status);
        if (!CurrentUser.isAdmin() && !(c.assignedEngineer == null && CurrentUser.username().equals(engineer))) {
            throw ApiException.forbidden("Engineers can only claim unassigned calibration for themselves");
        }
        requireEngineer(engineer);
        String before = c.assignedEngineer;
        c.assignedEngineer = engineer;
        calibrations.saveAndFlush(c);
        audit.log("CALIBRATION_ASSIGNED", "CALIBRATION", id, Map.of("engineer", String.valueOf(before)), Map.of("engineer", engineer));
        notify.toUser(engineer, "CALIBRATION_ASSIGNED", "INFO", "Calibration assigned", c.equipment.assetTag + " calibration scheduled for " + c.scheduledFor, "CALIBRATION", id, null);
        return dto(c);
    }

    @Transactional
    public CalibrationDto complete(long id, String result, Instant performedAt, String certificateNumber, String remarks, Instant nextDueDate) {
        Calibration c = lockedLoad(id);
        Equipment a = availability.lock(c.equipment.id);
        assertActor(c, "record the result");
        if (!"SCHEDULED".equals(c.status)) throw ApiException.conflict("INVALID_STATE", "Calibration is " + c.status);
        if (!"PASS".equals(result) && !"FAIL".equals(result)) throw ApiException.unprocessable("INVALID_RESULT", "result must be PASS or FAIL");
        Instant now = clock.instant();
        Instant when = performedAt == null ? now : performedAt;
        if (when.isAfter(now.plusSeconds(60))) throw ApiException.unprocessable("FUTURE_DATE", "performedAt cannot be in the future");
        boolean pass = "PASS".equals(result);
        if (pass && (certificateNumber == null || certificateNumber.isBlank())) throw ApiException.unprocessable("CERTIFICATE_REQUIRED", "A certificate number is required for a passing calibration");
        if (!pass && (remarks == null || remarks.isBlank())) throw ApiException.unprocessable("REMARKS_REQUIRED", "Remarks explaining the failure are required");
        Instant nextDue = pass ? (nextDueDate != null ? nextDueDate : (a.calibrationIntervalDays == null ? null : when.plus(a.calibrationIntervalDays, ChronoUnit.DAYS))) : null;
        if (pass && nextDue != null && !nextDue.isAfter(when)) throw ApiException.unprocessable("INVALID_NEXT_DUE", "The next calibration due date must be after the calibration");
        c.status = "COMPLETED";
        c.result = result;
        c.performedAt = when;
        c.certificateNumber = certificateNumber == null ? null : certificateNumber.trim();
        c.remarks = remarks;
        c.nextDueDate = nextDue;
        calibrations.saveAndFlush(c);
        a.lastCalibrationAt = when;
        if (pass) {
            a.calibrationFailed = false;
            a.nextCalibrationDue = nextDue;
            equipment.save(a);
        } else {
            a.calibrationFailed = true;
            equipment.save(a);
            bookings.cancelAffected(a.id, BookingStatus.CANCELLED_REPAIR, "Equipment failed calibration", true);
            for (String role : List.of("ADMIN", "ENGINEER")) {
                notify.toRole(role, "CALIBRATION_FAILED", "CRITICAL", "Calibration failed", a.assetTag + " failed calibration and is restricted until it passes", "ASSET", a.id, null);
            }
        }
        audit.log("CALIBRATION_COMPLETED", "CALIBRATION", id, null, Map.of("result", result, "assetId", a.id));
        return dto(c);
    }

    @Transactional
    public CalibrationDto cancel(long id, String reason) {
        Calibration c = lockedLoad(id);
        assertActor(c, "cancel");
        if (!"SCHEDULED".equals(c.status)) throw ApiException.conflict("INVALID_STATE", "Calibration is " + c.status);
        c.status = "CANCELLED";
        if (reason != null) c.remarks = reason;
        calibrations.saveAndFlush(c);
        audit.log("CALIBRATION_CANCELLED", "CALIBRATION", id, null, Map.of("reason", String.valueOf(reason)));
        return dto(c);
    }

    @Transactional(readOnly = true)
    public PageResult<CalibrationDto> list(Long assetId, Long departmentId, String status, String result, boolean mine, int page, int size) {
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (assetId != null) { w.append(" and c.equipment.id = :assetId"); p.put("assetId", assetId); }
        if (departmentId != null) { w.append(" and c.equipment.department.id = :dept"); p.put("dept", departmentId); }
        if (status != null && !status.isBlank()) { w.append(" and c.status = :status"); p.put("status", status); }
        if (result != null && !result.isBlank()) { w.append(" and c.result = :result"); p.put("result", result); }
        if (mine) { w.append(" and c.assignedEngineer = :me"); p.put("me", CurrentUser.username()); }
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(c) from Calibration c" + w, Long.class);
        TypedQuery<Calibration> data = em.createQuery("select c from Calibration c join fetch c.equipment e join fetch e.department" + w
                + " order by case c.status when 'SCHEDULED' then 0 else 1 end, coalesce(c.performedAt, c.scheduledFor) desc, c.id", Calibration.class);
        p.forEach((k, v) -> { count.setParameter(k, v); data.setParameter(k, v); });
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        return new PageResult<>(data.getResultList().stream().map(this::dto).toList(), pg, sz, total, (int) ((total + sz - 1) / sz));
    }
}
