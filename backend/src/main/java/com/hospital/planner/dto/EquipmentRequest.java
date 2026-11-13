package com.hospital.planner.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Create / update payload (same JSON fields as before). assetTag, departmentId and aliases are used on create only. */
public record EquipmentRequest(String assetTag, String name, String category, Long departmentId, String manufacturer, String model,
                               String serialNumber, LocalDate purchaseDate, BigDecimal purchaseCost, String supplier,
                               LocalDate warrantyStart, LocalDate warrantyEnd, String contractType, String contractVendor,
                               LocalDate contractStart, LocalDate contractEnd, String location, String notes,
                               Integer maintenanceIntervalDays, Double maintenanceThresholdHours, Boolean calibrationRequired,
                               Integer calibrationIntervalDays, Instant nextMaintenanceDue, Instant nextCalibrationDue,
                               List<String> aliases) {}
