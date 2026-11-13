package com.hospital.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.MaintenanceDto;
import com.hospital.planner.entity.*;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCHEDULED -> IN_PROGRESS -> COMPLETED (or CANCELLED).
 * A SCHEDULED window never affects availability before its start; maintenance counters reset ONLY in complete().
 */
@Service
public class MaintenanceService {
    public static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> DEFAULT_CHECKLIST = List.of("Visual inspection", "Functional test", "Electrical safety check", "Cleaning and disinfection");

    public record ChecklistItem(String item, boolean done) {}

    private final MaintenanceRepository maintenance;
    private final BookingRepository bookings;
    private final UsageRepository usage;
    private final UserRepository users;
    private final EquipmentRepository equipment;
    private final DowntimeEventRepository downtime;
    private final AvailabilityService availability;
    private final AuditService audit;
    private final NotificationService notify;
    private final ObjectMapper mapper;
    private final EntityManager em;
    private final Clock clock;

    public MaintenanceService(MaintenanceRepository maintenance, BookingRepository bookings, UsageRepository usage, UserRepository users,
                              EquipmentRepository equipment, DowntimeEventRepository downtime, AvailabilityService availability,
                              AuditService audit, NotificationService notify, ObjectMapper mapper, EntityManager em, Clock clock) {
        this.maintenance = maintenance;
        this.bookings = bookings;
        this.usage = usage;
        this.users = users;
        this.equipment = equipment;
        this.downtime = downtime;
        this.availability = availability;
        this.audit = audit;
        this.notify = notify;
        this.mapper = mapper;
        this.em = em;
        this.clock = clock;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> items(List<ChecklistItem> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChecklistItem c : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", c.item());
            m.put("done", c.done());
            out.add(m);
        }
        return out;
    }

    private List<ChecklistItem> parse(String checklist) {
        List<ChecklistItem> out = new ArrayList<>();
        try {
            for (JsonNode n : mapper.readTree(checklist == null ? "[]" : checklist)) out.add(new ChecklistItem(n.path("item").asText(), n.path("done").asBoolean(false)));
        } catch (Exception e) {
            // an unreadable checklist is treated as empty
        }
        return out;
    }

    private void requireEngineer(String username) {
        if (username != null && !users.existsByUsernameAndRoleAndActiveTrue(username, "ENGINEER")) {
            throw ApiException.unprocessable("UNKNOWN_ENGINEER", "'" + username + "' is not an active engineer");
        }
    }

    private void assertActor(Maintenance m) {
        if (!CurrentUser.isAdmin() && !CurrentUser.username().equals(m.assignedEngineer)) throw ApiException.forbidden("Only the assigned engineer or an ADMIN can do this");
    }

    private Maintenance load(long id) {
        return maintenance.findById(id).orElseThrow(() -> ApiException.notFound("Maintenance " + id));
    }

    /** Locks the equipment, then re-reads the record so that the decision is made on current data. */
    private Maintenance loadLocked(long id) {
        Maintenance m = load(id);
        availability.lock(m.equipment.id);
        em.refresh(m);
        return m;
    }

    MaintenanceDto dto(Maintenance m) {
        boolean actor = CurrentUser.isAdmin() || CurrentUser.username().equals(m.assignedEngineer);
        boolean engineerRole = CurrentUser.isAdmin() || CurrentUser.is("ENGINEER");
        boolean open = "SCHEDULED".equals(m.status) || "IN_PROGRESS".equals(m.status);
        JsonNode cl;
        try {
            cl = mapper.readTree(m.checklist == null ? "[]" : m.checklist);
        } catch (Exception e) {
            cl = mapper.createArrayNode();
        }
        return new MaintenanceDto(m.id, m.equipment.id, m.kind, m.startAt, m.endAt, m.status, m.priority, m.assignedEngineer, cl, m.workPerformed,
                m.remarks, m.partsReplaced, m.actualStart, m.actualEnd, m.nextDueDate, m.createdBy, m.createdAt, m.equipment.assetTag, m.equipment.name,
                m.equipment.department.name, actor && "SCHEDULED".equals(m.status), actor && "IN_PROGRESS".equals(m.status), actor && open, actor && open,
                engineerRole && m.assignedEngineer == null && open);
    }

    @Transactional(readOnly = true)
    public MaintenanceDto get(long id) {
        return dto(load(id));
    }

    @Transactional
    public MaintenanceDto schedule(long assetId, String kind, Instant start, Instant end, String priority, String engineer, List<String> checklist, String notes) {
        Instant now = clock.instant();
        if (start == null || end == null || !end.isAfter(start)) throw ApiException.bad("endAt must be after startAt");
        if (start.isBefore(now.minus(5, ChronoUnit.MINUTES))) throw ApiException.unprocessable("PAST_SCHEDULE", "Maintenance cannot be scheduled in the past");
        String prio = priority == null ? "MEDIUM" : priority;
        if (!PRIORITIES.contains(prio)) throw ApiException.unprocessable("INVALID_PRIORITY", "priority must be one of " + PRIORITIES);
        String assignee = engineer;
        if (CurrentUser.is("ENGINEER")) {
            if (assignee != null && !assignee.equals(CurrentUser.username())) throw ApiException.forbidden("Engineers can only assign maintenance to themselves");
            assignee = CurrentUser.username();
        }
        requireEngineer(assignee);
        Equipment a = availability.lock(assetId);
        if (a.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", a.assetTag + " is retired");
        List<Booking> clash = bookings.findOverlapping(assetId, BookingStatus.ACTIVE, start, end);
        if (!clash.isEmpty()) {
            throw ApiException.conflict("MAINTENANCE_CONFLICTS_WITH_BOOKINGS", "Cancel or move bookings first: " + clash.stream().map(b -> String.valueOf(b.id)).collect(Collectors.joining(",")));
        }
        if (!maintenance.findOpenOverlapping(assetId, start, end).isEmpty()) throw ApiException.conflict("MAINTENANCE_OVERLAP", "Another maintenance window overlaps");
        List<ChecklistItem> cl = new ArrayList<>();
        for (String s : (checklist == null || checklist.isEmpty()) ? DEFAULT_CHECKLIST : checklist) if (s != null && !s.isBlank()) cl.add(new ChecklistItem(s.trim(), false));
        Maintenance m = new Maintenance();
        m.equipment = a;
        m.kind = kind == null || kind.isBlank() ? "PREVENTIVE" : kind.trim().toUpperCase();
        m.startAt = start;
        m.endAt = end;
        m.priority = prio;
        m.assignedEngineer = assignee;
        m.checklist = json(items(cl));
        m.remarks = notes;
        m.createdBy = CurrentUser.username();
        try {
            maintenance.saveAndFlush(m);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("MAINTENANCE_OVERLAP", "Another maintenance window overlaps");
        }
        em.refresh(m);
        audit.log("MAINTENANCE_SCHEDULED", "MAINTENANCE", m.id, null, Map.of("assetId", assetId, "start", start.toString(), "end", end.toString(), "engineer", String.valueOf(assignee)));
        notify.toUser(assignee, "MAINTENANCE_ASSIGNED", "INFO", "Maintenance assigned", a.assetTag + " maintenance scheduled for " + start, "MAINTENANCE", m.id, null);
        return dto(m);
    }

    @Transactional
    public MaintenanceDto assign(long id, String engineer) {
        Maintenance m = loadLocked(id);
        if (!Set.of("SCHEDULED", "IN_PROGRESS").contains(m.status)) throw ApiException.conflict("INVALID_STATE", "Maintenance is " + m.status);
        if (!CurrentUser.isAdmin() && !(m.assignedEngineer == null && CurrentUser.username().equals(engineer))) {
            throw ApiException.forbidden("Engineers can only claim unassigned maintenance for themselves");
        }
        requireEngineer(engineer);
        String before = m.assignedEngineer;
        m.assignedEngineer = engineer;
        maintenance.saveAndFlush(m);
        audit.log("MAINTENANCE_ASSIGNED", "MAINTENANCE", id, Map.of("engineer", String.valueOf(before)), Map.of("engineer", String.valueOf(engineer)));
        notify.toUser(engineer, "MAINTENANCE_ASSIGNED", "INFO", "Maintenance assigned", m.equipment.assetTag + " maintenance scheduled for " + m.startAt, "MAINTENANCE", id, null);
        return dto(m);
    }

    @Transactional
    public MaintenanceDto start(long id) {
        Maintenance m = loadLocked(id);
        assertActor(m);
        if (!"SCHEDULED".equals(m.status)) throw ApiException.conflict("INVALID_STATE", "Only SCHEDULED maintenance can be started (is " + m.status + ")");
        Instant now = clock.instant();
        if (usage.findOpenSession(m.equipment.id).isPresent()) throw ApiException.conflict("ASSET_IN_USE", m.equipment.assetTag + " is in use; wait for the session to end");
        Instant until = m.endAt.isAfter(now) ? m.endAt : now.plus(1, ChronoUnit.HOURS);
        List<Booking> clash = bookings.findOverlapping(m.equipment.id, BookingStatus.ACTIVE, now, until);
        if (!clash.isEmpty()) throw ApiException.conflict("MAINTENANCE_CONFLICTS_WITH_BOOKINGS", "Active or imminent bookings: " + clash.stream().map(b -> String.valueOf(b.id)).collect(Collectors.joining(",")));
        m.status = "IN_PROGRESS";
        m.actualStart = now;
        maintenance.saveAndFlush(m);
        audit.log("MAINTENANCE_STARTED", "MAINTENANCE", id, Map.of("status", "SCHEDULED"), Map.of("status", "IN_PROGRESS"));
        notify.toRole("ADMIN", "MAINTENANCE_STARTED", "INFO", "Maintenance started", m.equipment.assetTag + " is now under maintenance (" + CurrentUser.username() + ")", "MAINTENANCE", id, null);
        return dto(m);
    }

    @Transactional
    public MaintenanceDto updateProgress(long id, List<ChecklistItem> checklist, String workPerformed, String remarks, String partsReplaced) {
        Maintenance m = loadLocked(id);
        assertActor(m);
        if (!Set.of("SCHEDULED", "IN_PROGRESS").contains(m.status)) throw ApiException.conflict("INVALID_STATE", "Maintenance is " + m.status);
        if (checklist != null) m.checklist = json(items(checklist));
        if (workPerformed != null) m.workPerformed = workPerformed;
        if (remarks != null) m.remarks = remarks;
        if (partsReplaced != null) m.partsReplaced = partsReplaced;
        maintenance.saveAndFlush(m);
        audit.log("MAINTENANCE_UPDATED", "MAINTENANCE", id, null, Map.of("fields", "progress"));
        return dto(m);
    }

    @Transactional
    public MaintenanceDto complete(long id, List<ChecklistItem> checklist, String workPerformed, String remarks, String partsReplaced, Instant nextDueDate) {
        Maintenance m = loadLocked(id);
        Equipment a = availability.lock(m.equipment.id);
        assertActor(m);
        if (!"IN_PROGRESS".equals(m.status)) throw ApiException.conflict("INVALID_STATE", "Maintenance must be started before it can be completed (is " + m.status + ")");
        String wp = workPerformed != null ? workPerformed : m.workPerformed;
        if (wp == null || wp.isBlank()) throw ApiException.unprocessable("WORK_PERFORMED_REQUIRED", "Describe the work performed");
        List<ChecklistItem> cl = checklist != null ? checklist : parse(m.checklist);
        if (!cl.stream().allMatch(ChecklistItem::done)) throw ApiException.unprocessable("CHECKLIST_INCOMPLETE", "Every checklist item must be done before completing");
        Instant now = clock.instant();
        Instant nextDue = nextDueDate != null ? nextDueDate : now.plus(a.maintenanceIntervalDays, ChronoUnit.DAYS);
        if (!nextDue.isAfter(now)) throw ApiException.unprocessable("INVALID_NEXT_DUE", "The next maintenance due date must be in the future");

        // remember how long the equipment was locked out as overdue, then reset the counters (completion only)
        Instant byDate = a.nextMaintenanceDue != null && a.nextMaintenanceDue.isBefore(now) ? a.nextMaintenanceDue : null;
        Instant byHours = a.hoursOverdueSince;
        Instant overdueFrom = byDate == null ? byHours : (byHours == null || byDate.isBefore(byHours) ? byDate : byHours);
        if (overdueFrom != null && now.isAfter(overdueFrom)) {
            DowntimeEvent d = new DowntimeEvent();
            d.equipment = a;
            d.startAt = overdueFrom;
            d.endAt = now;
            d.reason = "OVERDUE_LOCKOUT";
            downtime.save(d);
        }
        a.usageHoursSinceMaintenance = 0;
        a.hoursOverdueSince = null;
        a.lastMaintenanceAt = now;
        a.nextMaintenanceDue = nextDue;
        equipment.save(a);
        m.status = "COMPLETED";
        m.actualEnd = now;
        m.workPerformed = wp;
        if (remarks != null) m.remarks = remarks;
        if (partsReplaced != null) m.partsReplaced = partsReplaced;
        m.nextDueDate = nextDue;
        m.checklist = json(items(cl));
        maintenance.saveAndFlush(m);
        audit.log("MAINTENANCE_COMPLETED", "MAINTENANCE", id, null, Map.of("nextDue", nextDue.toString(), "countersReset", true));
        notify.toRole("ADMIN", "MAINTENANCE_COMPLETED", "INFO", "Maintenance completed", a.assetTag + " maintenance completed; next due " + nextDue, "MAINTENANCE", id, null);
        return dto(m);
    }

    @Transactional
    public MaintenanceDto cancel(long id, String reason) {
        Maintenance m = loadLocked(id);
        assertActor(m);
        if (!Set.of("SCHEDULED", "IN_PROGRESS").contains(m.status)) throw ApiException.conflict("INVALID_STATE", "Maintenance is " + m.status);
        String before = m.status;
        m.status = "CANCELLED";
        if (reason != null) m.remarks = reason;
        maintenance.saveAndFlush(m);
        audit.log("MAINTENANCE_CANCELLED", "MAINTENANCE", id, Map.of("status", before), Map.of("status", "CANCELLED", "reason", String.valueOf(reason)));
        notify.toUser(m.assignedEngineer, "MAINTENANCE_CANCELLED", "INFO", "Maintenance cancelled", m.equipment.assetTag + " maintenance was cancelled", "MAINTENANCE", id, null);
        return dto(m);
    }

    @Transactional(readOnly = true)
    public PageResult<MaintenanceDto> list(Long assetId, Long departmentId, String status, String engineer, boolean mine, String priority, Instant from, Instant to, int page, int size) {
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (assetId != null) { w.append(" and m.equipment.id = :assetId"); p.put("assetId", assetId); }
        if (departmentId != null) { w.append(" and m.equipment.department.id = :dept"); p.put("dept", departmentId); }
        if (status != null && !status.isBlank()) { w.append(" and m.status = :status"); p.put("status", status); }
        if (mine) { w.append(" and m.assignedEngineer = :me"); p.put("me", CurrentUser.username()); }
        else if (engineer != null && !engineer.isBlank()) { w.append(" and m.assignedEngineer = :eng"); p.put("eng", engineer); }
        if (priority != null && !priority.isBlank()) { w.append(" and m.priority = :prio"); p.put("prio", priority); }
        if (from != null) { w.append(" and m.endAt > :from"); p.put("from", from); }
        if (to != null) { w.append(" and m.startAt < :to"); p.put("to", to); }
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(m) from Maintenance m" + w, Long.class);
        TypedQuery<Maintenance> data = em.createQuery("select m from Maintenance m join fetch m.equipment e join fetch e.department" + w
                + " order by case m.status when 'IN_PROGRESS' then 0 when 'SCHEDULED' then 1 else 2 end, m.startAt desc, m.id", Maintenance.class);
        p.forEach((k, v) -> { count.setParameter(k, v); data.setParameter(k, v); });
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        return new PageResult<>(data.getResultList().stream().map(this::dto).toList(), pg, sz, total, (int) ((total + sz - 1) / sz));
    }
}
