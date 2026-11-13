package com.hospital.planner.dto;

import java.time.Instant;

public record HistoryDto(Instant at, String type, String title, String detail, String actor, Long refId) {}
