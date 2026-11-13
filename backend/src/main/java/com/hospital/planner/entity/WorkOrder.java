package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Breakdown report / repair work order. REPORTED -> TRIAGED -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> VERIFIED -> CLOSED. */
@Entity
@Table(name = "work_orders")
public class WorkOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
    @Column(nullable = false) public String title;
    @Column(nullable = false) public String description;
    @Column(nullable = false) public String priority = "MEDIUM";
    @Column(nullable = false) public String severity = "MINOR";
    @Column(nullable = false) public boolean unsafe;
    @Column(nullable = false) public String status = "REPORTED";
    @Column(name = "reported_by", nullable = false) public String reportedBy;
    @Column(name = "reported_at", nullable = false) public Instant reportedAt;
    @Column(name = "triaged_by") public String triagedBy;
    @Column(name = "triaged_at") public Instant triagedAt;
    @Column(name = "assigned_engineer") public String assignedEngineer;
    @Column(name = "started_at") public Instant startedAt;
    @Column(name = "resolved_at") public Instant resolvedAt;
    @Column(name = "verified_by") public String verifiedBy;
    @Column(name = "verified_at") public Instant verifiedAt;
    @Column(name = "closed_by") public String closedBy;
    @Column(name = "closed_at") public Instant closedAt;
    @Column(name = "repair_details") public String repairDetails;
    @Column(name = "parts_replaced") public String partsReplaced;
    @Column(name = "resolution_remarks") public String resolutionRemarks;
    @Column(name = "downtime_hours", precision = 10, scale = 2) public BigDecimal downtimeHours;
}
