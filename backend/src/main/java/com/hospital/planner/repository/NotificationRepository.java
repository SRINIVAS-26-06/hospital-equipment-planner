package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    /** A repeated dedupe key is silently ignored (unique index on dedupe_key). */
    @Modifying
    @Query(value = "INSERT INTO notifications(type, severity, title, message, entity_type, entity_id, audience_role, audience_user, dedupe_key) "
            + "VALUES (:type, :severity, :title, :message, :entityType, :entityId, :role, :usr, :dedupeKey) ON CONFLICT (dedupe_key) DO NOTHING", nativeQuery = true)
    int insertIgnoringDuplicates(@Param("type") String type, @Param("severity") String severity, @Param("title") String title,
                                 @Param("message") String message, @Param("entityType") String entityType, @Param("entityId") Long entityId,
                                 @Param("role") String role, @Param("usr") String usr, @Param("dedupeKey") String dedupeKey);
}
