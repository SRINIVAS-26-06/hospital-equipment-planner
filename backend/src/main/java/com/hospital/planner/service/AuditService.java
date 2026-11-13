package com.hospital.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.AuditDto;
import com.hospital.planner.entity.AuditLog;
import com.hospital.planner.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes the audit trail in the caller's transaction, so an action and its audit row commit (or roll back) together. */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditLogRepository repo;
    private final EntityManager em;
    private final ObjectMapper mapper;

    public AuditService(AuditLogRepository repo, EntityManager em, ObjectMapper mapper) {
        this.repo = repo;
        this.em = em;
        this.mapper = mapper;
    }

    private String json(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"unserializable\":true}";
        }
    }

    @Transactional
    public void log(String action, String entityType, Object entityId, Object oldValue, Object newValue) {
        AuditLog a = new AuditLog();
        a.occurredAt = Instant.now();
        a.username = CurrentUser.username();
        a.action = action;
        a.entityType = entityType;
        a.entityId = entityId == null ? null : String.valueOf(entityId);
        a.oldValue = json(oldValue);
        a.newValue = json(newValue);
        repo.save(a);
        log.info("audit user={} action={} {}={}", a.username, action, entityType, entityId);
    }

    /** Keys whose values differ between the two maps, as {old:{..}, new:{..}} for compact audit entries. */
    public static Map<String, Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> o = new LinkedHashMap<>();
        Map<String, Object> n = new LinkedHashMap<>();
        for (String k : after.keySet()) {
            Object a = before.get(k);
            Object b = after.get(k);
            if (a == null ? b != null : !a.equals(b)) {
                o.put(k, a);
                n.put(k, b);
            }
        }
        return Map.of("old", o, "new", n);
    }

    private JsonNode node(String s) {
        try {
            return s == null ? null : mapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public PageResult<AuditDto> search(String username, String entityType, String entityId, String action, Instant from, Instant to,
                                       int page, int size) {
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (username != null && !username.isBlank()) { w.append(" and a.username = :username"); p.put("username", username); }
        if (entityType != null && !entityType.isBlank()) { w.append(" and a.entityType = :entityType"); p.put("entityType", entityType); }
        if (entityId != null && !entityId.isBlank()) { w.append(" and a.entityId = :entityId"); p.put("entityId", entityId); }
        if (action != null && !action.isBlank()) { w.append(" and a.action = :action"); p.put("action", action); }
        if (from != null) { w.append(" and a.occurredAt >= :from"); p.put("from", from); }
        if (to != null) { w.append(" and a.occurredAt < :to"); p.put("to", to); }
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(a) from AuditLog a" + w, Long.class);
        TypedQuery<AuditLog> data = em.createQuery("select a from AuditLog a" + w + " order by a.occurredAt desc, a.id desc", AuditLog.class);
        p.forEach((k, v) -> { count.setParameter(k, v); data.setParameter(k, v); });
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        List<AuditDto> items = data.getResultList().stream()
                .map(a -> new AuditDto(a.id, a.occurredAt, a.username, a.action, a.entityType, a.entityId, node(a.oldValue), node(a.newValue))).toList();
        return new PageResult<>(items, pg, sz, total, (int) ((total + sz - 1) / sz));
    }
}
