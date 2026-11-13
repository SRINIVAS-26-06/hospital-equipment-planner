package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "calibrations")
public class Calibration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
    @Column(name = "scheduled_for", nullable = false) public Instant scheduledFor;
    @Column(nullable = false) public String status = "SCHEDULED";
    @Column(name = "assigned_engineer") public String assignedEngineer;
    @Column(name = "performed_at") public Instant performedAt;
    public String result;
    @Column(name = "certificate_number") public String certificateNumber;
    public String remarks;
    @Column(name = "next_due_date") public Instant nextDueDate;
    @Column(name = "created_by") public String createdBy;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) public Instant createdAt;
}
