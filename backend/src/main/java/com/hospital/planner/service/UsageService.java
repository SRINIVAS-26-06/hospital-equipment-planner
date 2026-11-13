package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.UsageDto;
import com.hospital.planner.entity.*;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UsageService {
    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    public static final int MAX_ROWS = 50_000;
    private static final Duration MAX_SESSION = Duration.ofDays(7);
    private static final DateTimeFormatter SPACE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");

    /** Raw values exactly as supplied; only these three fields (plus source) are ever read from an upload. */
    public record RawRow(int rowNumber, String assetRef, String start, String end) {}

    /** A row that failed validation. No stack trace: it is control flow, not an error. */
    static final class Rejected extends RuntimeException {
        final String reason;

        Rejected(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    private final UsageRepository usage;
    private final EquipmentRepository equipment;
    private final AssetAliasRepository aliases;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final IngestionBatchRepository batches;
    private final IngestionRowResultRepository rowResults;
    private final DepartmentHistoryDao history;
    private final AvailabilityService availability;
    private final AuditService audit;
    private final NotificationService notify;
    private final EntityManager em;
    private final Clock clock;
    private final TransactionTemplate rowTx;

    public UsageService(UsageRepository usage, EquipmentRepository equipment, AssetAliasRepository aliases, UserRepository users,
                        DepartmentRepository departments, IngestionBatchRepository batches, IngestionRowResultRepository rowResults,
                        DepartmentHistoryDao history, AvailabilityService availability, AuditService audit, NotificationService notify,
                        EntityManager em, Clock clock, PlatformTransactionManager txm) {
        this.usage = usage;
        this.equipment = equipment;
        this.aliases = aliases;
        this.users = users;
        this.departments = departments;
        this.batches = batches;
        this.rowResults = rowResults;
        this.history = history;
        this.availability = availability;
        this.audit = audit;
        this.notify = notify;
        this.em = em;
        this.clock = clock;
        this.rowTx = new TransactionTemplate(txm);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private UsageDto dto(Usage u) {
        return UsageDto.of(u, clock.instant(), u.endAt == null && CurrentUser.ownerOrAdmin(u.usedBy));
    }

    // ---------------- sessions ----------------

    @Transactional
    public UsageDto startSession(long assetId) {
        Equipment a = availability.lock(assetId);
        Instant now = clock.instant();
        String state = availability.stateAt(assetId, now);
        Long bookingId = null;
        switch (state) {
            case "AVAILABLE" -> { }
            case "IN_USE" -> throw ApiException.conflict("ALREADY_IN_USE", a.assetTag + " already has a session in progress");
            case "BOOKED" -> {
                Booking b = em.createQuery("select b from Booking b where b.equipment.id = :a and b.status = :s and b.startAt <= :now and b.endAt > :now", Booking.class)
                        .setParameter("a", assetId).setParameter("s", BookingStatus.ACTIVE).setParameter("now", now).getResultList().stream().findFirst()
                        .orElseThrow(() -> ApiException.conflict("BOOKED_BY_OTHER", a.assetTag + " is booked right now"));
                if (!CurrentUser.ownerOrAdmin(b.requestedBy)) throw ApiException.conflict("BOOKED_BY_OTHER", a.assetTag + " is booked by another user right now");
                bookingId = b.id;
            }
            default -> throw ApiException.conflict(state, a.assetTag + " cannot be used right now: " + state.toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        Usage u = new Usage();
        u.equipment = a;
        u.startAt = now;
        u.source = "session";
        u.usedBy = CurrentUser.username();
        u.department = a.department;
        u.userDepartment = users.findByUsernameIgnoreCase(CurrentUser.username()).map(x -> x.department).orElse(null);
        u.bookingId = bookingId;
        try {
            usage.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("ALREADY_IN_USE", a.assetTag + " already has a session in progress");   // unique open-session index
        }
        audit.log("USAGE_STARTED", "USAGE", u.id, null, Map.of("assetId", assetId));
        return dto(u);
    }

    @Transactional
    public UsageDto endSession(long id) {
        Usage u = usage.findById(id).orElseThrow(() -> ApiException.notFound("Usage session " + id));
        Equipment a = availability.lock(u.equipment.id);
        em.refresh(u);
        if (u.endAt != null) throw ApiException.conflict("ALREADY_ENDED", "Session already ended");
        if (!CurrentUser.ownerOrAdmin(u.usedBy)) throw ApiException.forbidden("You can only end your own sessions");
        Instant end = clock.instant();
        if (!end.isAfter(u.startAt)) end = u.startAt.plusSeconds(1);
        u.endAt = end;
        usage.saveAndFlush(u);
        double hours = Duration.between(u.startAt, end).toSeconds() / 3600.0;
        addHours(a, hours, end);
        audit.log("USAGE_ENDED", "USAGE", id, null, Map.of("hours", hours));
        return dto(u);
    }

    /** Adds usage hours to the maintenance counter and records when the usage threshold is first crossed. */
    private void addHours(Equipment a, double hours, Instant at) {
        double before = a.usageHoursSinceMaintenance;
        a.usageHoursSinceMaintenance = before + hours;
        boolean crossed = before < a.maintenanceThresholdHours && a.usageHoursSinceMaintenance >= a.maintenanceThresholdHours && a.hoursOverdueSince == null;
        if (crossed) {
            a.hoursOverdueSince = at;
            for (String role : List.of("ADMIN", "ENGINEER")) {
                notify.toRole(role, "MAINTENANCE_OVERDUE", "CRITICAL", "Maintenance overdue",
                        a.assetTag + " reached its usage threshold and can no longer be booked", "ASSET", a.id,
                        "USAGE_THRESHOLD:" + a.id + ":" + at.getEpochSecond() + ":" + role);
            }
        }
        equipment.save(a);
    }

    // ---------------- batch ingestion (JSON and CSV) ----------------

    /** Not transactional on purpose: every row commits (or is rejected) on its own, so a bad row never rolls back or hides the others. */
    public Map<String, Object> ingestRows(String source, String filename, byte[] fileBytes, List<RawRow> rows, List<String> ignoredColumns) {
        String user = CurrentUser.username();
        String sha = fileBytes == null ? null : sha256(fileBytes);
        boolean duplicateFile = sha != null && batches.countByFileSha256AndStatusIn(sha, List.of("COMPLETED", "PARTIAL")) > 0;
        IngestionBatch batch = new IngestionBatch();
        batch.source = source;
        batch.filename = filename;
        batch.fileSha256 = sha;
        batch.uploadedBy = user;
        batch.uploadedAt = clock.instant();
        batch.totalRows = rows.size();
        batch.ignoredColumns = String.join(",", ignoredColumns);
        batches.saveAndFlush(batch);
        long batchId = batch.id;
        String status = "FAILED";
        try {
            for (RawRow r : rows) processRow(batchId, r, source, user);
            long rej = rowResults.countByBatchAndOutcome(batchId, "REJECTED");
            long acc = rowResults.countByBatchAndOutcome(batchId, "ACCEPTED");
            status = rows.isEmpty() || rej == 0 ? "COMPLETED" : (acc == 0 ? "REJECTED" : "PARTIAL");
        } finally {
            IngestionBatch b = batches.findById(batchId).orElseThrow();
            b.acceptedRows = (int) rowResults.countByBatchAndOutcome(batchId, "ACCEPTED");
            b.rejectedRows = (int) rowResults.countByBatchAndOutcome(batchId, "REJECTED");
            b.status = status;
            batches.saveAndFlush(b);
        }
        audit.log("USAGE_BATCH_INGESTED", "INGESTION_BATCH", batchId, null, Map.of("source", source, "rows", rows.size(), "status", status));
        return batchSummary(batchId, duplicateFile);
    }

    private void processRow(long batchId, RawRow r, String source, String user) {
        String reason = null;
        Long eventId = null;
        try {
            eventId = rowTx.execute(s -> insertRow(batchId, r, source, user));
        } catch (Rejected rej) {
            reason = rej.reason;
        } catch (DataAccessException e) {
            log.warn("usage row {} failed in the database: {}", r.rowNumber(), e.getMostSpecificCause().getMessage());
            reason = "DB_ERROR";
        }
        IngestionRowResult res = new IngestionRowResult();
        res.batchId = batchId;
        res.rowNumber = r.rowNumber();
        res.assetRef = cut(r.assetRef());
        res.startText = cut(r.start());
        res.endText = cut(r.end());
        res.outcome = reason == null ? "ACCEPTED" : "REJECTED";
        res.reason = reason;
        res.usageEventId = eventId;
        rowResults.saveAndFlush(res);
    }

    private static String cut(String s) {
        return s == null ? null : (s.length() > 80 ? s.substring(0, 80) : s);
    }

    private Long insertRow(long batchId, RawRow r, String source, String user) {
        if (r.assetRef() == null || r.assetRef().isBlank()) throw new Rejected("MISSING_ASSET");
        Instant start = parseTime(r.start());
        Instant end = parseTime(r.end());
        if (!end.isAfter(start)) throw new Rejected("INVALID_SPAN");
        Instant now = clock.instant();
        if (end.isAfter(now.plusSeconds(300))) throw new Rejected("FUTURE_END");
        if (Duration.between(start, end).compareTo(MAX_SESSION) > 0) throw new Rejected("IMPLAUSIBLE_DURATION");
        Long assetId = resolve(r.assetRef());
        if (assetId == null) throw new Rejected("UNKNOWN_ASSET");
        Equipment a = availability.lock(assetId);
        if (a.lifecycleStatus == LifecycleStatus.RETIRED && a.retiredAt != null && end.isAfter(a.retiredAt)) throw new Rejected("RETIRED_ASSET");
        if (usage.existsByEquipmentIdAndStartAtAndEndAt(assetId, start, end)) throw new Rejected("DUPLICATE");
        if (usage.countOverlapping(assetId, start, end) > 0) throw new Rejected("OVERLAP");
        Long deptId = history.departmentAt(assetId, start);
        Usage u = new Usage();
        u.equipment = a;
        u.startAt = start;
        u.endAt = end;
        u.source = source;
        u.usedBy = user;
        u.department = deptId == null ? a.department : departments.findById(deptId).orElse(a.department);
        u.userDepartment = users.findByUsernameIgnoreCase(user).map(x -> x.department).orElse(null);
        u.batchId = batchId;
        usage.saveAndFlush(u);
        addHours(a, Duration.between(start, end).toSeconds() / 3600.0, end);
        equipment.flush();
        return u.id;
    }

    private Long resolve(String raw) {
        String norm = AssetIdNormalizer.normalize(raw);
        if (norm == null) return null;
        return equipment.findByAssetTag(norm).map(e -> e.id).or(() -> aliases.findById(norm).map(x -> x.equipment.id)).orElse(null);
    }

    static Instant parseTime(String s) {
        if (s == null || s.isBlank()) throw new Rejected("BAD_TIMESTAMP");
        String t = s.trim();
        try { return Instant.parse(t); } catch (Exception ignored) { /* try the next format */ }
        try { return OffsetDateTime.parse(t).toInstant(); } catch (Exception ignored) { /* try the next format */ }
        try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { /* try the next format */ }
        try { return LocalDateTime.parse(t, SPACE_FORMAT).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { throw new Rejected("BAD_TIMESTAMP"); }
    }

    // ---------------- CSV ----------------

    private static String key(String header) {
        return header == null ? "" : header.replace("\uFEFF", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static final Set<String> ASSET_KEYS = Set.of("assetid", "asset", "assettag", "equipmentid", "equipment", "deviceid");
    private static final Set<String> START_KEYS = Set.of("start", "starttime", "startat", "begin", "from", "usedfrom", "timestampstart");
    private static final Set<String> END_KEYS = Set.of("end", "endtime", "endat", "stop", "to", "usedto", "timestampend");

    /** Reads ONLY the asset, start and end columns; every other column (patient name, MRN, notes ...) is never stored. */
    public Map<String, Object> ingestCsv(String filename, byte[] bytes, String source) {
        List<List<String>> table = CsvReader.parse(new String(bytes, StandardCharsets.UTF_8));
        if (table.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "The file has no rows");
        List<String> header = table.get(0);
        int ia = -1, is = -1, ie = -1;
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String k = key(header.get(i));
            if (ia < 0 && ASSET_KEYS.contains(k)) ia = i;
            else if (is < 0 && START_KEYS.contains(k)) is = i;
            else if (ie < 0 && END_KEYS.contains(k)) ie = i;
            else ignored.add(header.get(i).replace("\uFEFF", "").trim());
        }
        if (ia < 0 || is < 0 || ie < 0) throw ApiException.unprocessable("MISSING_COLUMNS", "The file needs an asset id, a start time and an end time column");
        if (table.size() - 1 > MAX_ROWS) throw ApiException.unprocessable("TOO_MANY_ROWS", "At most " + MAX_ROWS + " rows per upload");
        List<RawRow> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> c = table.get(r);
            rows.add(new RawRow(r, cell(c, ia), cell(c, is), cell(c, ie)));
        }
        return ingestRows(source == null || source.isBlank() ? "csv-upload" : source, filename, bytes, rows, ignored);
    }

    private static String cell(List<String> cells, int i) {
        return i < cells.size() ? cells.get(i) : null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- reads ----------------

    private Map<String, Object> batchSummary(long batchId, boolean duplicateFile) {
        IngestionBatch b = batches.findById(batchId).orElseThrow(() -> ApiException.notFound("Batch " + batchId));
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (IngestionRowResult r : rowResults.rejected(batchId)) {
            if (rejected.size() >= 500) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowNumber", r.rowNumber);
            m.put("index", r.rowNumber - 1);
            m.put("assetRef", r.assetRef);
            m.put("start", r.startText);
            m.put("end", r.endText);
            m.put("reason", r.reason);
            rejected.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("status", b.status);
        out.put("source", b.source);
        out.put("filename", b.filename);
        out.put("uploadedBy", b.uploadedBy);
        out.put("uploadedAt", b.uploadedAt);
        out.put("totalRows", b.totalRows);
        out.put("accepted", b.acceptedRows);
        out.put("rejectedCount", b.rejectedRows);
        out.put("rejected", rejected);
        out.put("ignoredColumns", b.ignoredColumns == null || b.ignoredColumns.isEmpty() ? List.of() : List.of(b.ignoredColumns.split(",")));
        out.put("duplicateFile", duplicateFile);
        return out;
    }

    @Transactional(readOnly = true)
    public PageResult<UsageDto> history(Long assetId, Long departmentId, String user, boolean mine, boolean activeOnly, Instant from, Instant to,
                                        Long batchId, int page, int size) {
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (assetId != null) { w.append(" and u.equipment.id = :assetId"); p.put("assetId", assetId); }
        if (departmentId != null) { w.append(" and u.department.id = :dept"); p.put("dept", departmentId); }
        if (mine) { w.append(" and u.usedBy = :me"); p.put("me", CurrentUser.username()); }
        else if (user != null && !user.isBlank()) { w.append(" and u.usedBy = :user"); p.put("user", user); }
        if (activeOnly) w.append(" and u.endAt is null");
        if (from != null) { w.append(" and (u.endAt is null or u.endAt > :from)"); p.put("from", from); }
        if (to != null) { w.append(" and u.startAt < :to"); p.put("to", to); }
        if (batchId != null) { w.append(" and u.batchId = :batch"); p.put("batch", batchId); }
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(u) from Usage u" + w, Long.class);
        TypedQuery<Usage> data = em.createQuery("select u from Usage u join fetch u.equipment left join fetch u.department" + w + " order by u.startAt desc, u.id desc", Usage.class);
        p.forEach((k, v) -> { count.setParameter(k, v); data.setParameter(k, v); });
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        return new PageResult<>(data.getResultList().stream().map(this::dto).toList(), pg, sz, total, (int) ((total + sz - 1) / sz));
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> batches(int page, int size) {
        String w = CurrentUser.isAdmin() ? "" : " where b.uploadedBy = :me";
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(b) from IngestionBatch b" + w, Long.class);
        TypedQuery<IngestionBatch> data = em.createQuery("select b from IngestionBatch b" + w + " order by b.uploadedAt desc, b.id desc", IngestionBatch.class);
        if (!CurrentUser.isAdmin()) { count.setParameter("me", CurrentUser.username()); data.setParameter("me", CurrentUser.username()); }
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        List<Map<String, Object>> items = new ArrayList<>();
        for (IngestionBatch b : data.getResultList()) items.add(batchRow(b));
        return new PageResult<>(items, pg, sz, total, (int) ((total + sz - 1) / sz));
    }

    private static Map<String, Object> batchRow(IngestionBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id); m.put("source", b.source); m.put("filename", b.filename); m.put("uploadedBy", b.uploadedBy); m.put("uploadedAt", b.uploadedAt);
        m.put("totalRows", b.totalRows); m.put("acceptedRows", b.acceptedRows); m.put("rejectedRows", b.rejectedRows); m.put("status", b.status);
        m.put("ignoredColumns", b.ignoredColumns);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> batchDetail(long id, String outcome, int page, int size) {
        IngestionBatch b = batches.findById(id).orElseThrow(() -> ApiException.notFound("Batch " + id));
        if (!CurrentUser.ownerOrAdmin(b.uploadedBy)) throw ApiException.forbidden("You can only view your own upload batches");
        String w = " where r.batchId = :b" + (outcome != null && !outcome.isBlank() ? " and r.outcome = :o" : "");
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(r) from IngestionRowResult r" + w, Long.class).setParameter("b", id);
        TypedQuery<IngestionRowResult> data = em.createQuery("select r from IngestionRowResult r" + w + " order by r.rowNumber", IngestionRowResult.class).setParameter("b", id);
        if (outcome != null && !outcome.isBlank()) { count.setParameter("o", outcome); data.setParameter("o", outcome); }
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        List<Map<String, Object>> items = new ArrayList<>();
        for (IngestionRowResult r : data.getResultList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowNumber", r.rowNumber); m.put("assetRef", r.assetRef); m.put("startText", r.startText); m.put("endText", r.endText);
            m.put("outcome", r.outcome); m.put("reason", r.reason); m.put("usageEventId", r.usageEventId);
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batchRow(b));
        out.put("rows", new PageResult<>(items, pg, sz, total, (int) ((total + sz - 1) / sz)));
        return out;
    }
}
