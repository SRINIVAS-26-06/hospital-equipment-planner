package com.hospital.planner.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record MaintenanceDto(Long id, Long assetId, String kind, Instant startAt, Instant endAt, String status, String priority,
                             String assignedEngineer, JsonNode checklist, String workPerformed, String remarks, String partsReplaced,
                             Instant actualStart, Instant actualEnd, Instant nextDueDate, String createdBy, Instant createdAt,
                             String assetTag, String assetName, String departmentName,
                             boolean canStart, boolean canComplete, boolean canEdit, boolean canCancel, boolean canClaim) {}
