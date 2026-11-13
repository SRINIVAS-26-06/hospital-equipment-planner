package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.NotificationDto;
import com.hospital.planner.entity.Notification;
import com.hospital.planner.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final String VISIBLE = "(n.audienceRole = 'ALL' or n.audienceRole = :role or n.audienceUser = :me)";
    private final NotificationRepository repo;
    private final EntityManager em;

    public NotificationService(NotificationRepository repo, EntityManager em) {
        this.repo = repo;
        this.em = em;
    }

    /** role = ADMIN | ENGINEER | STAFF | VIEWER | ALL. A repeated dedupeKey is silently ignored. */
    @Transactional
    public void toRole(String role, String type, String severity, String title, String message, String entityType, Long entityId, String dedupeKey) {
        repo.insertIgnoringDuplicates(type, severity, title, message, entityType, entityId, role, null, dedupeKey);
    }

    @Transactional
    public void toUser(String username, String type, String severity, String title, String message, String entityType, Long entityId, String dedupeKey) {
        if (username == null) return;
        repo.insertIgnoringDuplicates(type, severity, title, message, entityType, entityId, null, username, dedupeKey);
    }

    private <T> TypedQuery<T> visible(String select, boolean unreadOnly, Class<T> type) {
        String jpql = select + " from Notification n where " + VISIBLE + (unreadOnly ? " and :me not member of n.readBy" : "");
        return em.createQuery(jpql, type).setParameter("role", CurrentUser.role()).setParameter("me", CurrentUser.username());
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationDto> list(boolean unreadOnly, int page, int size) {
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        long total = visible("select count(n)", unreadOnly, Long.class).getSingleResult();
        String me = CurrentUser.username();
        TypedQuery<Notification> q = em.createQuery("select n from Notification n where " + VISIBLE + (unreadOnly ? " and :me not member of n.readBy" : "")
                + " order by n.createdAt desc, n.id desc", Notification.class).setParameter("role", CurrentUser.role()).setParameter("me", me);
        q.setFirstResult(pg * sz);
        q.setMaxResults(sz);
        List<NotificationDto> items = q.getResultList().stream().map(n -> NotificationDto.of(n, me)).toList();
        return new PageResult<>(items, pg, sz, total, (int) ((total + sz - 1) / sz));
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return visible("select count(n)", true, Long.class).getSingleResult();
    }

    @Transactional
    public void markRead(long id) {
        List<Notification> found = em.createQuery("select n from Notification n where n.id = :id and " + VISIBLE, Notification.class)
                .setParameter("id", id).setParameter("role", CurrentUser.role()).setParameter("me", CurrentUser.username()).getResultList();
        for (Notification n : found) n.readBy.add(CurrentUser.username());
    }

    @Transactional
    public int markAllRead() {
        List<Notification> unread = visible("select n", true, Notification.class).getResultList();
        for (Notification n : unread) n.readBy.add(CurrentUser.username());
        return unread.size();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        return Map.of("unread", unreadCount());
    }
}
