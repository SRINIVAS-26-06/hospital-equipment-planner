package com.hospital.planner.dto;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DepartmentDto(Long id, String name, boolean active, Long assetCount) {}
