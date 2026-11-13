package com.hospital.planner.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record AuditDto(Long id, Instant at, String username, String action, String entityType, String entityId,
                       JsonNode oldValue, JsonNode newValue) {}
