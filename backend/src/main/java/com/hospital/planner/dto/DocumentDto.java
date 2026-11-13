package com.hospital.planner.dto;

import java.time.Instant;

public record DocumentDto(Long id, String entityType, Long entityId, String docKind, String filename, String contentType,
                          Long sizeBytes, String uploadedBy, Instant uploadedAt) {}
