package com.hospital.planner.dto;

import com.hospital.planner.entity.User;
import java.time.Instant;

public record UserDto(Long id, String username, String fullName, String email, String role, Long departmentId,
                      String departmentName, boolean active, Instant createdAt) {
    public static UserDto of(User u) {
        return new UserDto(u.id, u.username, u.fullName, u.email, u.role, u.department == null ? null : u.department.id,
                u.department == null ? null : u.department.name, u.active, u.createdAt);
    }
}
