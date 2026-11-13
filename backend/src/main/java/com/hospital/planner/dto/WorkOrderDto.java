package com.hospital.planner.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WorkOrderDto(Long id, Long assetId, String title, String description, String priority, String severity, boolean unsafe,
                           String status, String reportedBy, Instant reportedAt, String triagedBy, Instant triagedAt, String assignedEngineer,
                           Instant startedAt, Instant resolvedAt, String verifiedBy, Instant verifiedAt, String closedBy, Instant closedAt,
                           String repairDetails, String partsReplaced, String resolutionRemarks, BigDecimal downtimeHours, String assetTag,
                           String assetName, String departmentName, boolean canTriage, boolean canAssign, boolean canStart, boolean canResolve,
                           boolean canVerify, boolean canReopen, boolean canClose, boolean blocking) {}
