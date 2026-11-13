package com.hospital.planner.dto;

import com.hospital.planner.entity.Notification;
import java.time.Instant;

public record NotificationDto(Long id, Instant createdAt, String type, String severity, String title, String message,
                              String entityType, Long entityId, boolean isRead) {
    public static NotificationDto of(Notification n, String me) {
        return new NotificationDto(n.id, n.createdAt, n.type, n.severity, n.title, n.message, n.entityType, n.entityId, n.readBy.contains(me));
    }
}
