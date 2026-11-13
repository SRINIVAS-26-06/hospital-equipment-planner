package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.WorkOrderDto;
import com.hospital.planner.entity.*;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.UserRepository;
import com.hospital.planner.repository.WorkOrderHistoryRepository;
import com.hospital.planner.repository.WorkOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * REPORTED -> TRIAGED -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> VERIFIED -> CLOSED.
 * Equipment is blocked while a breakdown is REPORTED..IN_PROGRESS; an UNSAFE one stays blocked through RESOLVED until VERIFIED.
 */
@Service
public class WorkOrderService {
    public static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    public static final Set<String> SEVERITIES = Set.of("MINOR", "MAJOR", "CRITICAL");
    private static final Set<String> BLOCKING = Set.of("REPORTED", "TRIAGED", "ASSIGNED", "IN_PROGRESS");

    private final WorkOrderRepository orders;
    private final WorkOrderHistoryRepository historyRepo;
    private final UserRepository users;
    private final AvailabilityService availability;
    private final BookingService bookings;
    private final AuditService audit;
    private final NotificationService notify;
    private final EntityManager em;
    private final Clock clock;

    public WorkOrderService(WorkOrderRepository orders, WorkOrderHistoryRepository historyRepo, UserRepository users, AvailabilityService availability,
                            BookingService bookings, AuditService audit, NotificationService notify, EntityManager em, Clock clock) {
        this.orders = orders;
        this.historyRepo = historyRepo;
        this.users = users;
        this.availability = availability;
        this.bookings = bookings;
        this.audit = audit;
        this.notify = notify;
        this.em = em;
        this.clock = clock;
    }

    private boolean isAssignee(WorkOrder w) {
        return CurrentUser.isAdmin() || CurrentUser.username().equals(w.assignedEngineer);
    }

    private boolean isReporter(WorkOrder w) {
        return CurrentUser.isAdmin() || CurrentUser.username().equals(w.reportedBy);
    }

    private void assertVisible(WorkOrder w) {
        if (CurrentUser.is("STAFF") && !CurrentUser.username().equals(w.reportedBy)) throw ApiException.forbidden("You can only view breakdowns you reported");
    }

    WorkOrderDto dto(WorkOrder w) {
        String st = w.status;
        boolean engineerOrAdmin = CurrentUser.isAdmin() || CurrentUser.is("ENGINEER");
        return new WorkOrderDto(w.id, w.equipment.id, w.title, w.description, w.priority, w.severity, w.unsafe, st, w.reportedBy, w.reportedAt, w.triagedBy,
                w.triagedAt, w.assignedEngineer, w.startedAt, w.resolvedAt, w.verifiedBy, w.verifiedAt, w.closedBy, w.closedAt, w.repairDetails, w.partsReplaced,
                w.resolutionRemarks, w.downtimeHours, w.equipment.assetTag, w.equipment.name, w.equipment.department.name,
                engineerOrAdmin && "REPORTED".equals(st),
                (CurrentUser.isAdmin() && Set.of("TRIAGED", "ASSIGNED").contains(st)) || (CurrentUser.is("ENGINEER") && "TRIAGED".equals(st)),
                isAssignee(w) && "ASSIGNED".equals(st), isAssignee(w) && "IN_PROGRESS".equals(st), isReporter(w) && "RESOLVED".equals(st),
                isReporter(w) && "RESOLVED".equals(st), CurrentUser.isAdmin() && "VERIFIED".equals(st),
                BLOCKING.contains(st) || (w.unsafe && "RESOLVED".equals(st)));
    }

    private void record(WorkOrder w, String from, String to, String note) {
        WorkOrderHistory h = new WorkOrderHistory();
        h.workOrder = w;
        h.occurredAt = clock.instant();
        h.actor = CurrentUser.username();
        h.fromStatus = from;
        h.toStatus = to;
        h.note = note;
        historyRepo.save(h);
    }

    /** Locks the equipment first, then re-reads the order and checks the lifecycle step is legal. */
    private WorkOrder begin(long id, Set<String> allowedFrom) {
        WorkOrder w = orders.findById(id).orElseThrow(() -> ApiException.notFound("Work order " + id));
        availability.lock(w.equipment.id);
        em.refresh(w);
        if (!allowedFrom.contains(w.status)) throw ApiException.conflict("INVALID_STATE", "Work order is " + w.status + "; expected " + String.join(" or ", allowedFrom));
        return w;
    }

    private void alertOps(WorkOrder w, String type, String sev, String title, String msg) {
        for (String role : List.of("ADMIN", "ENGINEER")) notify.toRole(role, type, sev, title, msg, "WORK_ORDER", w.id, null);
    }

    @Transactional
    public WorkOrderDto report(long assetId, String title, String description, String severity, String priority, Boolean unsafe) {
        String sev = severity == null ? "MINOR" : severity;
        if (!SEVERITIES.contains(sev)) throw ApiException.unprocessable("INVALID_SEVERITY", "severity must be one of " + SEVERITIES);
        String prio = priority != null ? priority : switch (sev) { case "CRITICAL" -> "CRITICAL"; case "MAJOR" -> "HIGH"; default -> "LOW"; };
        if (!PRIORITIES.contains(prio)) throw ApiException.unprocessable("INVALID_PRIORITY", "priority must be one of " + PRIORITIES);
        Equipment a = availability.lock(assetId);
        if (a.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", a.assetTag + " is retired");
        boolean isUnsafe = Boolean.TRUE.equals(unsafe);
        WorkOrder w = new WorkOrder();
        w.equipment = a;
        w.title = title.trim();
        w.description = description.trim();
        w.priority = prio;
        w.severity = sev;
        w.unsafe = isUnsafe;
        w.reportedBy = CurrentUser.username();
        w.reportedAt = clock.instant();
        orders.saveAndFlush(w);
        record(w, null, "REPORTED", "Breakdown reported");
        List<Booking> cancelled = isUnsafe ? bookings.cancelAffected(assetId, BookingStatus.CANCELLED_REPAIR, "Equipment reported unsafe", true) : List.of();
        audit.log("WORK_ORDER_REPORTED", "WORK_ORDER", w.id, null, Map.of("assetId", assetId, "severity", sev, "unsafe", isUnsafe, "cancelledBookings", cancelled.size()));
        alertOps(w, "BREAKDOWN_REPORTED", isUnsafe || "CRITICAL".equals(sev) ? "CRITICAL" : "WARNING", "Breakdown reported", a.assetTag + ": " + w.title + (isUnsafe ? " (UNSAFE)" : ""));
        return dto(w);
    }

    @Transactional
    public WorkOrderDto triage(long id, String priority, String severity, Boolean unsafe, String note) {
        if (!CurrentUser.isAdmin() && !CurrentUser.is("ENGINEER")) throw ApiException.forbidden("Only an ADMIN or an ENGINEER can triage a work order");
        WorkOrder w = begin(id, Set.of("REPORTED"));
        if (priority != null && !PRIORITIES.contains(priority)) throw ApiException.unprocessable("INVALID_PRIORITY", "priority must be one of " + PRIORITIES);
        if (severity != null && !SEVERITIES.contains(severity)) throw ApiException.unprocessable("INVALID_SEVERITY", "severity must be one of " + SEVERITIES);
        boolean wasUnsafe = w.unsafe;
        boolean nowUnsafe = unsafe == null ? w.unsafe : unsafe;
        w.status = "TRIAGED";
        w.triagedBy = CurrentUser.username();
        w.triagedAt = clock.instant();
        if (priority != null) w.priority = priority;
        if (severity != null) w.severity = severity;
        w.unsafe = nowUnsafe;
        orders.saveAndFlush(w);
        record(w, "REPORTED", "TRIAGED", note);
        if (nowUnsafe && !wasUnsafe) bookings.cancelAffected(w.equipment.id, BookingStatus.CANCELLED_REPAIR, "Equipment flagged unsafe", true);
        audit.log("WORK_ORDER_TRIAGED", "WORK_ORDER", id, null, Map.of("priority", String.valueOf(priority), "severity", String.valueOf(severity), "unsafe", nowUnsafe));
        return dto(w);
    }

    @Transactional
    public WorkOrderDto assign(long id, String engineer, String note) {
        WorkOrder w = begin(id, Set.of("TRIAGED", "ASSIGNED"));
        if (!CurrentUser.isAdmin() && !(CurrentUser.is("ENGINEER") && CurrentUser.username().equals(engineer))) throw ApiException.forbidden("Engineers can only assign work orders to themselves");
        if (!users.existsByUsernameAndRoleAndActiveTrue(engineer, "ENGINEER")) throw ApiException.unprocessable("UNKNOWN_ENGINEER", "'" + engineer + "' is not an active engineer");
        String from = w.status;
        String before = w.assignedEngineer;
        w.status = "ASSIGNED";
        w.assignedEngineer = engineer;
        orders.saveAndFlush(w);
        record(w, from, "ASSIGNED", "Assigned to " + engineer + (note == null ? "" : ": " + note));
        audit.log("WORK_ORDER_ASSIGNED", "WORK_ORDER", id, Map.of("engineer", String.valueOf(before)), Map.of("engineer", engineer));
        notify.toUser(engineer, "WORK_ORDER_ASSIGNED", "WARNING", "Work order assigned", w.equipment.assetTag + ": " + w.title, "WORK_ORDER", id, null);
        return dto(w);
    }

    @Transactional
    public WorkOrderDto start(long id) {
        WorkOrder w = begin(id, Set.of("ASSIGNED"));
        if (!isAssignee(w)) throw ApiException.forbidden("Only the assigned engineer or an ADMIN can start this work order");
        w.status = "IN_PROGRESS";
        w.startedAt = clock.instant();
        orders.saveAndFlush(w);
        record(w, "ASSIGNED", "IN_PROGRESS", "Repair started");
        audit.log("WORK_ORDER_STARTED", "WORK_ORDER", id, null, Map.of("status", "IN_PROGRESS"));
        return dto(w);
    }

    private static BigDecimal hours(Instant from, Instant to) {
        return BigDecimal.valueOf(Duration.between(from, to).toSeconds()).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public WorkOrderDto resolve(long id, String repairDetails, String partsReplaced, String resolutionRemarks) {
        WorkOrder w = begin(id, Set.of("IN_PROGRESS"));
        if (!isAssignee(w)) throw ApiException.forbidden("Only the assigned engineer or an ADMIN can resolve this work order");
        if (repairDetails == null || repairDetails.isBlank()) throw ApiException.unprocessable("REPAIR_DETAILS_REQUIRED", "Describe the repair performed");
        Instant now = clock.instant();
        w.status = "RESOLVED";
        w.resolvedAt = now;
        w.repairDetails = repairDetails.trim();
        w.partsReplaced = partsReplaced;
        w.resolutionRemarks = resolutionRemarks;
        w.downtimeHours = hours(w.reportedAt, now);
        orders.saveAndFlush(w);
        record(w, "IN_PROGRESS", "RESOLVED", resolutionRemarks);
        audit.log("WORK_ORDER_RESOLVED", "WORK_ORDER", id, null, Map.of("downtimeHours", w.downtimeHours));
        notify.toUser(w.reportedBy, "WORK_ORDER_RESOLVED", "INFO", "Repair completed - please verify", w.equipment.assetTag + " has been repaired. Please verify the fix.", "WORK_ORDER", id, null);
        notify.toRole("ADMIN", "WORK_ORDER_RESOLVED", "INFO", "Work order resolved", w.equipment.assetTag + ": " + w.title, "WORK_ORDER", id, null);
        return dto(w);
    }

    @Transactional
    public WorkOrderDto verify(long id, String note) {
        WorkOrder w = begin(id, Set.of("RESOLVED"));
        if (!isReporter(w)) throw ApiException.forbidden("Only the person who reported the breakdown, or an ADMIN, can verify the fix");
        Instant now = clock.instant();
        w.status = "VERIFIED";
        w.verifiedBy = CurrentUser.username();
        w.verifiedAt = now;
        if (w.unsafe) w.downtimeHours = hours(w.reportedAt, now);
        orders.saveAndFlush(w);
        record(w, "RESOLVED", "VERIFIED", note);
        audit.log("WORK_ORDER_VERIFIED", "WORK_ORDER", id, null, Map.of("status", "VERIFIED"));
        return dto(w);
    }

    @Transactional
    public WorkOrderDto reopen(long id, String note) {
        WorkOrder w = begin(id, Set.of("RESOLVED"));
        if (!isReporter(w)) throw ApiException.forbidden("Only the person who reported the breakdown, or an ADMIN, can reopen it");
        w.status = "IN_PROGRESS";
        w.resolvedAt = null;
        w.downtimeHours = null;
        orders.saveAndFlush(w);
        record(w, "RESOLVED", "IN_PROGRESS", note == null ? "Fix not accepted; reopened" : note);
        audit.log("WORK_ORDER_REOPENED", "WORK_ORDER", id, null, Map.of("status", "IN_PROGRESS"));
        notify.toUser(w.assignedEngineer, "WORK_ORDER_REOPENED", "WARNING", "Work order reopened", w.equipment.assetTag + ": the fix was not accepted", "WORK_ORDER", id, null);
        return dto(w);
    }

    @Transactional
    public WorkOrderDto close(long id, String note) {
        WorkOrder w = begin(id, Set.of("VERIFIED"));
        if (!CurrentUser.isAdmin()) throw ApiException.forbidden("Only an ADMIN can close a work order");
        w.status = "CLOSED";
        w.closedBy = CurrentUser.username();
        w.closedAt = clock.instant();
        orders.saveAndFlush(w);
        record(w, "VERIFIED", "CLOSED", note);
        audit.log("WORK_ORDER_CLOSED", "WORK_ORDER", id, null, Map.of("status", "CLOSED"));
        return dto(w);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long id) {
        WorkOrder w = orders.findById(id).orElseThrow(() -> ApiException.notFound("Work order " + id));
        assertVisible(w);
        List<Map<String, Object>> hist = historyRepo.findForOrder(id).stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", h.occurredAt); m.put("actor", h.actor); m.put("fromStatus", h.fromStatus); m.put("toStatus", h.toStatus); m.put("note", h.note);
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workOrder", dto(w));
        out.put("history", hist);
        return out;
    }

    @Transactional(readOnly = true)
    public PageResult<WorkOrderDto> list(Long assetId, String status, boolean openOnly, boolean mine, boolean reportedByMe, String priority, String severity, int page, int size) {
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (CurrentUser.is("STAFF")) { w.append(" and w.reportedBy = :staffMe"); p.put("staffMe", CurrentUser.username()); }
        if (assetId != null) { w.append(" and w.equipment.id = :assetId"); p.put("assetId", assetId); }
        if (status != null && !status.isBlank()) { w.append(" and w.status = :status"); p.put("status", status); }
        if (openOnly) w.append(" and w.status not in ('VERIFIED','CLOSED')");
        if (mine) { w.append(" and w.assignedEngineer = :me"); p.put("me", CurrentUser.username()); }
        if (reportedByMe) { w.append(" and w.reportedBy = :rep"); p.put("rep", CurrentUser.username()); }
        if (priority != null && !priority.isBlank()) { w.append(" and w.priority = :prio"); p.put("prio", priority); }
        if (severity != null && !severity.isBlank()) { w.append(" and w.severity = :sev"); p.put("sev", severity); }
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(w) from WorkOrder w" + w, Long.class);
        TypedQuery<WorkOrder> data = em.createQuery("select w from WorkOrder w join fetch w.equipment e join fetch e.department" + w
                + " order by case w.status when 'CLOSED' then 2 when 'VERIFIED' then 2 else 0 end, "
                + "case w.priority when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end, w.reportedAt desc, w.id", WorkOrder.class);
        p.forEach((k, v) -> { count.setParameter(k, v); data.setParameter(k, v); });
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        return new PageResult<>(data.getResultList().stream().map(this::dto).toList(), pg, sz, total, (int) ((total + sz - 1) / sz));
    }
}
