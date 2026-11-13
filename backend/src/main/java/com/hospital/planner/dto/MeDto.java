package com.hospital.planner.dto;

import java.util.List;

public record MeDto(String username, String fullName, String role, List<String> roles, Object departmentId, Object departmentName) {}
