package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.common.Times;
import com.hospital.planner.dto.EquipmentDto;
import com.hospital.planner.dto.EquipmentRequest;
import com.hospital.planner.dto.HistoryDto;
import com.hospital.planner.entity.*;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquipmentService {
    private static final Set<String> CONTRACTS = Set.of("NONE", "AMC", "CMC");

    private final EquipmentRepository equipment;
    private final DepartmentRepository departments;
    private final AssetAliasRepository aliases;
    private final TransferRepository transfers;
    private final DowntimeEventRepository downtime;
    private final UsageRepository usage;
    private final DepartmentHistoryDao history;
    private final AvailabilityService availability;
    private final BookingService bookings;
    private final AuditService audit;
    private final EntityManager em;
    private final Clock clock;

    public EquipmentService(EquipmentRepository equipment, DepartmentRepository departments, AssetAliasRepository aliases, TransferRepository transfers,
                            DowntimeEventRepository downtime, UsageRepository usage, DepartmentHistoryDao history, AvailabilityService availability,
                            BookingService bookings, AuditService audit, EntityManager em, Clock clock) {
        this.equipment = equipment;
        this.departments = departments;
        this.aliases = aliases;
        this.transfers = transfers;
        this.downtime = downtime;
        this.usage = usage;
        this.history = history;
        this.availability = availability;
        this.bookings = bookings;
        this.audit = audit;
        this.em = em;
        this.clock = clock;
    }

    private EquipmentDto dto(Equipment e, String state, List<String> aliasList) {
        return EquipmentDto.of(e, state, Availability.hardBlocked(state), aliasList);
    }

    private EquipmentDto dto(Equipment e) {
        return dto(e, availability.stateAt(e.id, clock.instant()), aliases.findForAsset(e.id).stream().map(a -> a.alias).toList());
    }

    // ---------- search / view ----------

    @Transactional(readOnly = true)
    public PageResult<EquipmentDto> search(String q, Long departmentId, String category, String lifecycle, String state, Integer maintenanceDueDays,
                                           Integer calibrationDueDays, Integer warrantyDays, String sort, boolean desc, int page, int size) {
        Instant now = clock.instant();
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            w.append(" and (lower(e.assetTag) like :q or lower(e.name) like :q or lower(e.serialNumber) like :q or lower(e.model) like :q "
                    + "or lower(e.manufacturer) like :q or lower(e.location) like :q or lower(e.category) like :q)");
            p.put("q", "%" + q.trim().toLowerCase() + "%");
        }
        if (departmentId != null) { w.append(" and e.department.id = :dept"); p.put("dept", departmentId); }
        if (category != null && !category.isBlank()) { w.append(" and e.category = :cat"); p.put("cat", category); }
        if (lifecycle != null && !lifecycle.isBlank()) {
            try {
                p.put("lc", LifecycleStatus.valueOf(lifecycle));
            } catch (IllegalArgumentException ex) {
                throw ApiException.bad("Unknown lifecycle " + lifecycle);
            }
            w.append(" and e.lifecycleStatus = :lc");
        } else {
            w.append(" and e.lifecycleStatus <> :retired");        // retired equipment only when asked for
            p.put("retired", LifecycleStatus.RETIRED);
        }
        if (maintenanceDueDays != null) { w.append(" and e.nextMaintenanceDue <= :mdue"); p.put("mdue", now.plus(maintenanceDueDays, ChronoUnit.DAYS)); }
        if (calibrationDueDays != null) { w.append(" and e.calibrationRequired = true and e.nextCalibrationDue <= :cdue"); p.put("cdue", now.plus(calibrationDueDays, ChronoUnit.DAYS)); }
        if (warrantyDays != null) {
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            w.append(" and e.warrantyEnd between :today and :wdue");
            p.put("today", today);
            p.put("wdue", today.plusDays(warrantyDays));
        }
        TypedQuery<Equipment> q1 = em.createQuery("select e from Equipment e join fetch e.department" + w, Equipment.class);
        p.forEach(q1::setParameter);
        List<Equipment> all = q1.getResultList();
        // availability is calculated for the matching rows in one query, then filtered / sorted / paged here
        Map<Long, String> states = availability.statesFor(all.stream().map(e -> e.id).toList(), now);
        List<Equipment> matched = all.stream().filter(e -> state == null || state.isBlank() || state.equals(states.get(e.id))).toList();
        Comparator<Equipment> cmp = switch (sort == null ? "assetTag" : sort) {
            case "name" -> Comparator.comparing(e -> e.name, String.CASE_INSENSITIVE_ORDER);
            case "department" -> Comparator.comparing(e -> e.department.name, String.CASE_INSENSITIVE_ORDER);
            case "category" -> Comparator.comparing(e -> e.category, String.CASE_INSENSITIVE_ORDER);
            case "availability" -> Comparator.comparing(e -> states.getOrDefault(e.id, ""));
            case "nextMaintenanceDue" -> Comparator.comparing((Equipment e) -> e.nextMaintenanceDue, Comparator.nullsLast(Comparator.naturalOrder()));
            case "nextCalibrationDue" -> Comparator.comparing((Equipment e) -> e.nextCalibrationDue, Comparator.nullsLast(Comparator.naturalOrder()));
            case "warrantyEnd" -> Comparator.comparing((Equipment e) -> e.warrantyEnd, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(e -> e.assetTag);
        };
        List<Equipment> sorted = new ArrayList<>(matched);
        sorted.sort(desc ? cmp.reversed().thenComparing(e -> e.id) : cmp.thenComparing(e -> e.id));
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        int from = Math.min(pg * sz, sorted.size());
        List<EquipmentDto> items = sorted.subList(from, Math.min(from + sz, sorted.size())).stream()
                .map(e -> dto(e, states.getOrDefault(e.id, "AVAILABLE"), null)).toList();
        return new PageResult<>(items, pg, sz, sorted.size(), (sorted.size() + sz - 1) / sz);
    }

    @Transactional(readOnly = true)
    public EquipmentDto get(long id) {
        return dto(availability.get(id));
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return em.createQuery("select distinct e.category from Equipment e order by e.category", String.class).getResultList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> availabilityAt(long id, Instant at) {
        Instant when = at == null ? clock.instant() : at;
        String s = availability.stateAt(id, when);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetId", id);
        m.put("at", when);
        m.put("availability", s);
        m.put("hardBlocked", Availability.hardBlocked(s));
        return m;
    }

    // ---------- create / update ----------

    private void validate(EquipmentRequest d, boolean create) {
        if (create && (d.assetTag() == null || d.assetTag().isBlank())) throw ApiException.bad("assetTag is required");
        if (create && d.departmentId() == null) throw ApiException.bad("departmentId is required");
        if (d.name() == null || d.name().isBlank()) throw ApiException.bad("name is required");
        if (d.category() == null || d.category().isBlank()) throw ApiException.bad("category is required");
        if (!CONTRACTS.contains(d.contractType() == null ? "NONE" : d.contractType())) throw ApiException.unprocessable("INVALID_CONTRACT_TYPE", "contractType must be NONE, AMC or CMC");
        if (d.warrantyStart() != null && d.warrantyEnd() != null && d.warrantyEnd().isBefore(d.warrantyStart())) throw ApiException.unprocessable("INVALID_WARRANTY", "warrantyEnd must not be before warrantyStart");
        if (d.contractStart() != null && d.contractEnd() != null && d.contractEnd().isBefore(d.contractStart())) throw ApiException.unprocessable("INVALID_CONTRACT", "contractEnd must not be before contractStart");
        if (d.maintenanceIntervalDays() != null && d.maintenanceIntervalDays() < 1) throw ApiException.bad("maintenanceIntervalDays must be positive");
        if (d.maintenanceThresholdHours() != null && d.maintenanceThresholdHours() <= 0) throw ApiException.bad("maintenanceThresholdHours must be positive");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void apply(Equipment e, EquipmentRequest d) {
        e.name = d.name().trim();
        e.category = d.category().trim();
        e.manufacturer = d.manufacturer();
        e.model = d.model();
        e.serialNumber = blankToNull(d.serialNumber());
        e.purchaseDate = d.purchaseDate();
        e.purchaseCost = d.purchaseCost();
        e.supplier = d.supplier();
        e.warrantyStart = d.warrantyStart();
        e.warrantyEnd = d.warrantyEnd();
        e.contractType = d.contractType() == null ? "NONE" : d.contractType();
        e.contractVendor = d.contractVendor();
        e.contractStart = d.contractStart();
        e.contractEnd = d.contractEnd();
        e.location = d.location();
        e.notes = d.notes();
        if (d.maintenanceIntervalDays() != null) e.maintenanceIntervalDays = d.maintenanceIntervalDays();
        if (d.maintenanceThresholdHours() != null) e.maintenanceThresholdHours = d.maintenanceThresholdHours();
        if (d.calibrationRequired() != null) e.calibrationRequired = d.calibrationRequired();
        e.calibrationIntervalDays = d.calibrationIntervalDays();
        if (d.nextMaintenanceDue() != null) e.nextMaintenanceDue = d.nextMaintenanceDue();
        if (d.nextCalibrationDue() != null) e.nextCalibrationDue = d.nextCalibrationDue();
    }

    private static Map<String, Object> snapshot(Equipment e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name); m.put("category", e.category); m.put("manufacturer", e.manufacturer); m.put("model", e.model);
        m.put("serialNumber", e.serialNumber); m.put("location", e.location); m.put("warrantyEnd", e.warrantyEnd);
        m.put("contractType", e.contractType); m.put("contractEnd", e.contractEnd); m.put("notes", e.notes);
        m.put("maintenanceIntervalDays", e.maintenanceIntervalDays); m.put("maintenanceThresholdHours", e.maintenanceThresholdHours);
        m.put("calibrationRequired", e.calibrationRequired); m.put("calibrationIntervalDays", e.calibrationIntervalDays);
        m.put("nextMaintenanceDue", e.nextMaintenanceDue); m.put("nextCalibrationDue", e.nextCalibrationDue);
        return m;
    }

    @Transactional
    public EquipmentDto create(EquipmentRequest d) {
        validate(d, true);
        String tag = AssetIdNormalizer.normalize(d.assetTag());
        if (tag == null) throw ApiException.bad("Invalid asset tag");
        if (equipment.findByAssetTag(tag).isPresent() || aliases.existsById(tag)) throw ApiException.conflict("DUPLICATE_ASSET", "Asset tag already registered: " + tag);
        Department dept = departments.findById(d.departmentId()).filter(x -> x.active)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_DEPARTMENT", "Department " + d.departmentId() + " does not exist or is inactive"));
        Instant now = clock.instant();
        Equipment e = new Equipment();
        e.assetTag = tag;
        e.department = dept;
        e.inServiceFrom = now;
        e.lastMaintenanceAt = now;
        apply(e, d);
        e.nextMaintenanceDue = d.nextMaintenanceDue() != null ? d.nextMaintenanceDue() : now.plus(e.maintenanceIntervalDays, ChronoUnit.DAYS);
        if (e.calibrationRequired && d.nextCalibrationDue() == null && d.calibrationIntervalDays() != null) {
            e.nextCalibrationDue = now.plus(d.calibrationIntervalDays(), ChronoUnit.DAYS);
        }
        try {
            equipment.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("DUPLICATE_SERIAL", "An asset with this serial number already exists");
        }
        history.open(e.id, dept.id, now);
        if (d.aliases() != null) {
            for (String raw : d.aliases()) {
                String n = AssetIdNormalizer.normalize(raw);
                if (n != null && !n.equals(tag) && !aliases.existsById(n)) {
                    AssetAlias a = new AssetAlias();
                    a.alias = n;
                    a.equipment = e;
                    aliases.save(a);
                }
            }
        }
        audit.log("EQUIPMENT_CREATED", "ASSET", e.id, null, snapshot(e));
        return dto(e);
    }

    @Transactional
    public EquipmentDto update(long id, EquipmentRequest d) {
        validate(d, false);
        Equipment e = availability.lock(id);
        if (e.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", "Retired equipment cannot be edited");
        Map<String, Object> before = snapshot(e);
        apply(e, d);
        try {
            equipment.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("DUPLICATE_SERIAL", "An asset with this serial number already exists");
        }
        var diff = AuditService.diff(before, snapshot(e));
        audit.log("EQUIPMENT_UPDATED", "ASSET", id, diff.get("old"), diff.get("new"));
        return dto(e);
    }

    // ---------- transfer / retire / lifecycle ----------

    @Transactional
    public Map<String, Object> transfer(long id, long toDeptId, String reason) {
        Equipment e = availability.lock(id);
        if (e.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", "Retired equipment cannot be transferred");
        Department to = departments.findById(toDeptId).filter(d -> d.active)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_DEPARTMENT", "Department " + toDeptId + " does not exist or is inactive"));
        Department from = e.department;
        if (from.id.equals(toDeptId)) throw ApiException.conflict("SAME_DEPARTMENT", "Equipment already belongs to that department");
        Instant now = clock.instant();
        String state = availability.stateAt(id, now);
        if ("IN_USE".equals(state)) throw ApiException.conflict("ASSET_IN_USE", "Equipment is in use");
        if ("BOOKED".equals(state)) throw ApiException.conflict("ASSET_BOOKED_NOW", "Equipment has an active booking right now");
        if ("UNDER_MAINTENANCE".equals(state)) throw ApiException.conflict("UNDER_MAINTENANCE", "Equipment is under maintenance right now");

        List<Booking> cancelled = bookings.cancelAffected(id, BookingStatus.CANCELLED_TRANSFER, "Equipment transferred to another department", false);
        history.closeCurrent(id, now);
        history.open(id, toDeptId, now);
        e.department = to;
        Transfer t = new Transfer();
        t.equipment = e;
        t.fromDepartment = from;
        t.toDepartment = to;
        t.transferredAt = now;
        transfers.save(t);
        equipment.saveAndFlush(e);
        audit.log("EQUIPMENT_TRANSFERRED", "ASSET", id, Map.of("departmentId", from.id), Map.of("departmentId", toDeptId, "reason", String.valueOf(reason)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", id);
        out.put("fromDepartmentId", from.id);
        out.put("toDepartmentId", toDeptId);
        out.put("effectiveFrom", now);
        out.put("cancelledBookingIds", cancelled.stream().map(b -> b.id).toList());
        return out;
    }

    @Transactional
    public EquipmentDto retire(long id, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.bad("A retirement reason is required");
        Equipment e = availability.lock(id);
        if (e.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("ALREADY_RETIRED", "Equipment is already retired");
        if (usage.findOpenSession(id).isPresent()) throw ApiException.conflict("ASSET_IN_USE", "End the active usage session before retiring");
        if (em.createQuery("select count(m) from Maintenance m where m.equipment.id = :a and m.status = 'IN_PROGRESS'", Long.class).setParameter("a", id).getSingleResult() > 0) {
            throw ApiException.conflict("MAINTENANCE_IN_PROGRESS", "Complete or cancel the maintenance in progress before retiring");
        }
        Instant now = clock.instant();
        bookings.cancelAffected(id, BookingStatus.CANCELLED_RETIRED, "Equipment retired", true);
        em.createQuery("update Maintenance m set m.status = 'CANCELLED' where m.equipment.id = :a and m.status = 'SCHEDULED'").setParameter("a", id).executeUpdate();
        em.createQuery("update Calibration c set c.status = 'CANCELLED' where c.equipment.id = :a and c.status = 'SCHEDULED'").setParameter("a", id).executeUpdate();
        em.clear();
        e = availability.lock(id);
        for (DowntimeEvent d : downtimeOpen(id)) d.endAt = now;
        LifecycleStatus before = e.lifecycleStatus;
        e.lifecycleStatus = LifecycleStatus.RETIRED;
        e.retiredAt = now;
        e.retiredReason = reason.trim();
        equipment.saveAndFlush(e);
        audit.log("EQUIPMENT_RETIRED", "ASSET", id, Map.of("lifecycleStatus", before.name()), Map.of("lifecycleStatus", "RETIRED", "reason", reason.trim()));
        return dto(e);
    }

    private List<DowntimeEvent> downtimeOpen(long id) {
        return em.createQuery("select d from DowntimeEvent d where d.equipment.id = :a and d.endAt is null", DowntimeEvent.class).setParameter("a", id).getResultList();
    }

    /** ACTIVE <-> OUT_OF_SERVICE. Retirement has its own endpoint and cannot be undone here. */
    @Transactional
    public EquipmentDto setLifecycle(long id, String status, String reason) {
        if (!"ACTIVE".equals(status) && !"OUT_OF_SERVICE".equals(status)) throw ApiException.unprocessable("INVALID_STATUS", "status must be ACTIVE or OUT_OF_SERVICE (use retire to retire)");
        Equipment e = availability.lock(id);
        if (e.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", "Retired equipment cannot change status");
        LifecycleStatus target = LifecycleStatus.valueOf(status);
        if (e.lifecycleStatus == target) throw ApiException.conflict("NO_CHANGE", "Equipment is already " + status);
        Instant now = clock.instant();
        LifecycleStatus before = e.lifecycleStatus;
        if (target == LifecycleStatus.OUT_OF_SERVICE) {
            if (reason == null || reason.isBlank()) throw ApiException.bad("A reason is required to take equipment out of service");
            if (usage.findOpenSession(id).isPresent()) throw ApiException.conflict("ASSET_IN_USE", "End the active usage session first");
            bookings.cancelAffected(id, BookingStatus.CANCELLED_REPAIR, "Equipment out of service: " + reason.trim(), true);
            DowntimeEvent d = new DowntimeEvent();
            d.equipment = e;
            d.startAt = now;
            d.reason = "OUT_OF_SERVICE";
            downtime.save(d);
        } else {
            for (DowntimeEvent d : downtime.findOpen(id, "OUT_OF_SERVICE")) d.endAt = now;
        }
        e.lifecycleStatus = target;
        equipment.saveAndFlush(e);
        audit.log("EQUIPMENT_STATUS_CHANGED", "ASSET", id, Map.of("lifecycleStatus", before.name()), Map.of("lifecycleStatus", status, "reason", String.valueOf(reason)));
        return dto(e);
    }

    // ---------- history ----------

    private static String when(Instant t) {
        return t == null ? "" : t.toString().replace('T', ' ').substring(0, 16);
    }

    /** Everything that happened to one piece of equipment, newest first. */
    @Transactional(readOnly = true)
    public List<HistoryDto> history(long id, int limit) {
        availability.get(id);
        int n = Math.max(1, Math.min(limit, 500));
        List<HistoryDto> out = new ArrayList<>();
        for (Booking b : em.createQuery("select b from Booking b where b.equipment.id = :id", Booking.class).setParameter("id", id).getResultList()) {
            out.add(new HistoryDto(b.createdAt, "BOOKING", "Booking " + b.status.name().toLowerCase(), when(b.startAt) + " to " + when(b.endAt), b.requestedBy, b.id));
        }
        Instant now = clock.instant();
        for (Usage u : em.createQuery("select u from Usage u where u.equipment.id = :id", Usage.class).setParameter("id", id).getResultList()) {
            out.add(new HistoryDto(u.startAt, "USAGE", u.endAt == null ? "Usage in progress" : "Usage session",
                    u.endAt == null ? "since " + when(u.startAt) : (Math.round(java.time.Duration.between(u.startAt, u.endAt).toSeconds() / 36.0) / 100.0) + " h", u.usedBy, u.id));
        }
        for (Maintenance m : em.createQuery("select m from Maintenance m where m.equipment.id = :id", Maintenance.class).setParameter("id", id).getResultList()) {
            out.add(new HistoryDto(m.actualStart != null ? m.actualStart : m.startAt, "MAINTENANCE", m.kind + " maintenance " + m.status.toLowerCase(),
                    "priority " + m.priority.toLowerCase() + (m.assignedEngineer == null ? "" : ", engineer " + m.assignedEngineer), m.assignedEngineer, m.id));
        }
        for (WorkOrder w : em.createQuery("select w from WorkOrder w where w.equipment.id = :id", WorkOrder.class).setParameter("id", id).getResultList()) {
            out.add(new HistoryDto(w.reportedAt, "BREAKDOWN", "Breakdown: " + w.title + " (" + w.status.toLowerCase() + ")",
                    w.severity + " severity" + (w.unsafe ? ", unsafe" : ""), w.reportedBy, w.id));
        }
        for (Calibration c : em.createQuery("select c from Calibration c where c.equipment.id = :id", Calibration.class).setParameter("id", id).getResultList()) {
            out.add(new HistoryDto(c.performedAt != null ? c.performedAt : c.scheduledFor, "CALIBRATION",
                    "Calibration " + c.status.toLowerCase() + (c.result == null ? "" : " - " + c.result), c.certificateNumber == null ? "" : "certificate " + c.certificateNumber,
                    c.assignedEngineer, c.id));
        }
        for (Transfer t : transfers.findForAsset(id)) {
            out.add(new HistoryDto(t.transferredAt, "TRANSFER", "Transferred department", t.fromDepartment.name + " to " + t.toDepartment.name, null, t.id));
        }
        for (DowntimeEvent d : downtime.findForAsset(id)) {
            if ("OVERDUE_LOCKOUT".equals(d.reason)) continue;
            out.add(new HistoryDto(d.startAt, "DOWNTIME", "Downtime: " + d.reason.replace('_', ' ').toLowerCase(),
                    d.endAt == null ? "ongoing" : (Math.round(java.time.Duration.between(d.startAt, d.endAt).toSeconds() / 36.0) / 100.0) + " h", null, d.id));
        }
        for (AuditLog l : em.createQuery("select l from AuditLog l where l.entityType = 'ASSET' and l.entityId = :id and l.action in "
                + "('EQUIPMENT_CREATED','EQUIPMENT_UPDATED','EQUIPMENT_RETIRED','EQUIPMENT_STATUS_CHANGED')", AuditLog.class).setParameter("id", String.valueOf(id)).getResultList()) {
            out.add(new HistoryDto(l.occurredAt, "AUDIT", l.action, "", l.username, l.id));
        }
        out.sort(Comparator.comparing((HistoryDto h) -> h.at(), Comparator.nullsLast(Comparator.<Instant>naturalOrder())).reversed());
        return out.size() > n ? out.subList(0, n) : out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> departmentHistory(long id) {
        availability.get(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : history.forAsset(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Times.num(r[0]));
            m.put("departmentId", Times.num(r[1]));
            m.put("departmentName", r[2]);
            m.put("effectiveFrom", Times.instant(r[3]));
            m.put("effectiveTo", Times.instant(r[4]));
            out.add(m);
        }
        return out;
    }

    public String actor() {
        return CurrentUser.username();
    }
}
