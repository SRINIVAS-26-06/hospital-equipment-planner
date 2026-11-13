package com.hospital.planner.dto;

import java.time.Instant;

public record CalibrationDto(Long id, Long assetId, Instant scheduledFor, String status, String assignedEngineer, Instant performedAt,
                             String result, String certificateNumber, String remarks, Instant nextDueDate, String createdBy, Instant createdAt,
                             String assetTag, String assetName, String departmentName, boolean canComplete, boolean canCancel, boolean canClaim) {}
