package com.hospital.planner.dto;

import com.hospital.planner.entity.Usage;
import java.time.Duration;
import java.time.Instant;

public record UsageDto(Long id, Long assetId, String assetTag, String assetName, Instant startAt, Instant endAt, String usedBy, String source,
                       Long batchId, Long bookingId, Long departmentId, String departmentName, double durationHours, boolean canEnd) {
    public static UsageDto of(Usage u, Instant now, boolean canEnd) {
        double hours = Math.round(Duration.between(u.startAt, u.endAt == null ? now : u.endAt).toSeconds() / 36.0) / 100.0;
        return new UsageDto(u.id, u.equipment.id, u.equipment.assetTag, u.equipment.name, u.startAt, u.endAt, u.usedBy, u.source, u.batchId,
                u.bookingId, u.department == null ? null : u.department.id, u.department == null ? null : u.department.name, hours, canEnd);
    }
}
