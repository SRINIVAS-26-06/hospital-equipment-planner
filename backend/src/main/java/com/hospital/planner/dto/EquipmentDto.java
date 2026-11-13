package com.hospital.planner.dto;

import com.hospital.planner.entity.Equipment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record EquipmentDto(Long id, String assetTag, String name, String category, Long departmentId, String departmentName,
                           String lifecycleStatus, String availability, boolean hardBlocked, int maintenanceIntervalDays,
                           double maintenanceThresholdHours, double usageHoursSinceMaintenance, Instant lastMaintenanceAt,
                           Instant nextMaintenanceDue, Instant hoursOverdueSince, Instant retiredAt, String retiredReason,
                           Instant inServiceFrom, String manufacturer, String model, String serialNumber, LocalDate purchaseDate,
                           BigDecimal purchaseCost, String supplier, LocalDate warrantyStart, LocalDate warrantyEnd, boolean warrantyActive,
                           String contractType, String contractVendor, LocalDate contractStart, LocalDate contractEnd, String location,
                           String notes, boolean calibrationRequired, Integer calibrationIntervalDays, Instant lastCalibrationAt,
                           Instant nextCalibrationDue, boolean calibrationFailed, List<String> aliases) {
    public static EquipmentDto of(Equipment e, String availability, boolean hardBlocked, List<String> aliases) {
        boolean warranty = e.warrantyEnd != null && !e.warrantyEnd.isBefore(LocalDate.now());
        return new EquipmentDto(e.id, e.assetTag, e.name, e.category, e.department.id, e.department.name, e.lifecycleStatus.name(), availability,
                hardBlocked, e.maintenanceIntervalDays, e.maintenanceThresholdHours, e.usageHoursSinceMaintenance, e.lastMaintenanceAt,
                e.nextMaintenanceDue, e.hoursOverdueSince, e.retiredAt, e.retiredReason, e.inServiceFrom, e.manufacturer, e.model,
                e.serialNumber, e.purchaseDate, e.purchaseCost, e.supplier, e.warrantyStart, e.warrantyEnd, warranty, e.contractType,
                e.contractVendor, e.contractStart, e.contractEnd, e.location, e.notes, e.calibrationRequired, e.calibrationIntervalDays,
                e.lastCalibrationAt, e.nextCalibrationDue, e.calibrationFailed, aliases);
    }
}
