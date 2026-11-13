package com.hospital.planner.dto;

import com.hospital.planner.entity.Booking;
import java.time.Instant;

public record BookingDto(Long id, Long assetId, Long departmentId, String requestedBy, Instant startAt, Instant endAt, String status,
                         Instant createdAt, String purpose, String cancelledBy, Instant cancelledAt, String cancelReason,
                         String assetTag, String assetName, String departmentName, boolean canCancel) {
    public static BookingDto of(Booking b, boolean canCancel) {
        return new BookingDto(b.id, b.equipment.id, b.department.id, b.requestedBy, b.startAt, b.endAt, b.status.name(), b.createdAt,
                b.purpose, b.cancelledBy, b.cancelledAt, b.cancelReason, b.equipment.assetTag, b.equipment.name, b.department.name, canCancel);
    }
}
