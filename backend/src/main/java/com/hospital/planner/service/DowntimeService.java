package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.entity.DowntimeEvent;
import com.hospital.planner.entity.Equipment;
import com.hospital.planner.entity.LifecycleStatus;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.DowntimeEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Engineers (and admins) record unplanned downtime that is not a breakdown work order (power loss, relocation, ...). */
@Service
public class DowntimeService {
    public static final Set<String> REASONS = Set.of("POWER_FAILURE", "RELOCATION", "SOFTWARE_UPDATE", "AWAITING_PARTS", "OTHER");
    private final DowntimeEventRepository repo;
    private final AvailabilityService availability;
    private final AuditService audit;
    private final EntityManager em;
    private final Clock clock;

    public DowntimeService(DowntimeEventRepository repo, AvailabilityService availability, AuditService audit, EntityManager em, Clock clock) {
        this.repo = repo;
        this.availability = availability;
        this.audit = audit;
        this.em = em;
        this.clock = clock;
    }

    private Map<String, Object> view(DowntimeEvent d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id); m.put("assetId", d.equipment.id); m.put("assetTag", d.equipment.assetTag); m.put("startAt", d.startAt); m.put("endAt", d.endAt); m.put("reason", d.reason);
        return m;
    }

    @Transactional
    public Map<String, Object> record(long assetId, Instant start, Instant end, String reason) {
        if (!end.isAfter(start)) throw ApiException.bad("endAt must be after startAt");
        if (end.isAfter(clock.instant().plusSeconds(60))) throw ApiException.unprocessable("FUTURE_END", "Record downtime after it has ended");
        if (!REASONS.contains(reason)) throw ApiException.unprocessable("INVALID_REASON", "reason must be one of " + REASONS);
        Equipment a = availability.lock(assetId);
        if (a.lifecycleStatus == LifecycleStatus.RETIRED) throw ApiException.conflict("RETIRED", a.assetTag + " is retired");
        DowntimeEvent d = new DowntimeEvent();
        d.equipment = a;
        d.startAt = start;
        d.endAt = end;
        d.reason = reason;
        repo.saveAndFlush(d);
        audit.log("DOWNTIME_RECORDED", "ASSET", assetId, null, Map.of("downtimeId", d.id, "reason", reason, "by", CurrentUser.username()));
        return view(d);
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(Long assetId, int page, int size) {
        String w = assetId == null ? "" : " where d.equipment.id = :assetId";
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(d) from DowntimeEvent d" + w, Long.class);
        TypedQuery<DowntimeEvent> data = em.createQuery("select d from DowntimeEvent d join fetch d.equipment" + w + " order by d.startAt desc, d.id desc", DowntimeEvent.class);
        if (assetId != null) { count.setParameter("assetId", assetId); data.setParameter("assetId", assetId); }
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        List<Map<String, Object>> items = data.getResultList().stream().map(this::view).toList();
        return new PageResult<>(items, pg, sz, total, (int) ((total + sz - 1) / sz));
    }
}
