package com.hospital.planner.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Effective-dated department ownership (table asset_department_history). Native queries through the EntityManager, because the first
 * period starts at -infinity, which is clipped in SQL and never loaded into a Java Instant.
 */
@Repository
public class DepartmentHistoryDao {
    private final EntityManager em;

    public DepartmentHistoryDao(EntityManager em) {
        this.em = em;
    }

    public void closeCurrent(long assetId, Instant at) {
        em.createNativeQuery("UPDATE asset_department_history SET effective_to = :at WHERE asset_id = :a AND effective_to IS NULL")
                .setParameter("at", at).setParameter("a", assetId).executeUpdate();
    }

    public void open(long assetId, long departmentId, Instant from) {
        em.createNativeQuery("INSERT INTO asset_department_history(asset_id, department_id, effective_from) VALUES (:a, :d, :f)")
                .setParameter("a", assetId).setParameter("d", departmentId).setParameter("f", from).executeUpdate();
    }

    /** Department that owned the equipment at instant t, or null if there is no record. */
    public Long departmentAt(long assetId, Instant t) {
        List<?> r = em.createNativeQuery("SELECT department_id FROM asset_department_history WHERE asset_id = :a AND effective_from <= :t "
                + "AND (effective_to IS NULL OR effective_to > :t)").setParameter("a", assetId).setParameter("t", t).getResultList();
        return r.isEmpty() ? null : ((Number) r.get(0)).longValue();
    }

    /** Rows: asset_id, department_id, department name, clipped from, clipped to (all periods overlapping [from, to)). */
    @SuppressWarnings("unchecked")
    public List<Object[]> periods(Instant from, Instant to) {
        return em.createNativeQuery("SELECT h.asset_id, h.department_id, d.name, GREATEST(h.effective_from, :from), "
                + "LEAST(COALESCE(h.effective_to, :to), :to) FROM asset_department_history h JOIN departments d ON d.id = h.department_id "
                + "WHERE h.effective_from < :to AND COALESCE(h.effective_to, CAST('infinity' AS timestamptz)) > :from ORDER BY h.effective_from")
                .setParameter("from", from).setParameter("to", to).getResultList();
    }

    /** Rows: id, department_id, department name, effective_from (null = from the start of records), effective_to. */
    @SuppressWarnings("unchecked")
    public List<Object[]> forAsset(long assetId) {
        return em.createNativeQuery("SELECT h.id, h.department_id, d.name, CASE WHEN h.effective_from = CAST('-infinity' AS timestamptz) THEN NULL ELSE h.effective_from END, "
                + "h.effective_to FROM asset_department_history h JOIN departments d ON d.id = h.department_id WHERE h.asset_id = :a ORDER BY h.effective_from")
                .setParameter("a", assetId).getResultList();
    }
}
