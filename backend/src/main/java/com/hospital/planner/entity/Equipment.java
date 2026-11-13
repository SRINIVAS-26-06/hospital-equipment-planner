package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Table "assets". Availability is calculated, so only lifecycle is stored; the legacy "status" column is left unmapped. */
@Entity
@Table(name = "assets")
public class Equipment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "asset_tag", nullable = false) public String assetTag;
    @Column(nullable = false) public String name;
    @Column(nullable = false) public String category;
    @ManyToOne(optional = false) @JoinColumn(name = "department_id") public Department department;
    @Enumerated(EnumType.STRING) @Column(name = "lifecycle_status", nullable = false) public LifecycleStatus lifecycleStatus = LifecycleStatus.ACTIVE;
    @Column(name = "maintenance_interval_days", nullable = false) public int maintenanceIntervalDays = 90;
    @Column(name = "maintenance_threshold_hours", nullable = false) public double maintenanceThresholdHours = 500;
    @Column(name = "usage_hours_since_maintenance", nullable = false) public double usageHoursSinceMaintenance = 0;
    @Column(name = "last_maintenance_at") public Instant lastMaintenanceAt;
    @Column(name = "next_maintenance_due") public Instant nextMaintenanceDue;
    @Column(name = "hours_overdue_since") public Instant hoursOverdueSince;
    @Column(name = "retired_at") public Instant retiredAt;
    @Column(name = "retired_reason") public String retiredReason;
    @Column(name = "in_service_from") public Instant inServiceFrom;
    public String manufacturer;
    public String model;
    @Column(name = "serial_number") public String serialNumber;
    @Column(name = "purchase_date") public LocalDate purchaseDate;
    @Column(name = "purchase_cost", precision = 14, scale = 2) public BigDecimal purchaseCost;
    public String supplier;
    @Column(name = "warranty_start") public LocalDate warrantyStart;
    @Column(name = "warranty_end") public LocalDate warrantyEnd;
    @Column(name = "contract_type", nullable = false) public String contractType = "NONE";
    @Column(name = "contract_vendor") public String contractVendor;
    @Column(name = "contract_start") public LocalDate contractStart;
    @Column(name = "contract_end") public LocalDate contractEnd;
    public String location;
    public String notes;
    @Column(name = "calibration_required", nullable = false) public boolean calibrationRequired;
    @Column(name = "calibration_interval_days") public Integer calibrationIntervalDays;
    @Column(name = "last_calibration_at") public Instant lastCalibrationAt;
    @Column(name = "next_calibration_due") public Instant nextCalibrationDue;
    @Column(name = "calibration_failed", nullable = false) public boolean calibrationFailed;
}
