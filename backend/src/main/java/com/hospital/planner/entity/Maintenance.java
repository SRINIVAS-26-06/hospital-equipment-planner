package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table "maintenance_windows". Lifecycle: SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED. */
@Entity
@Table(name = "maintenance_windows")
public class Maintenance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
    @Column(nullable = false) public String kind = "PREVENTIVE";
    @Column(name = "start_at", nullable = false) public Instant startAt;
    @Column(name = "end_at", nullable = false) public Instant endAt;
    @Column(nullable = false) public String status = "SCHEDULED";
    @Column(nullable = false) public String priority = "MEDIUM";
    @Column(name = "assigned_engineer") public String assignedEngineer;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") public String checklist = "[]";
    @Column(name = "work_performed") public String workPerformed;
    public String remarks;
    @Column(name = "parts_replaced") public String partsReplaced;
    @Column(name = "actual_start") public Instant actualStart;
    @Column(name = "actual_end") public Instant actualEnd;
    @Column(name = "next_due_date") public Instant nextDueDate;
    @Column(name = "created_by") public String createdBy;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) public Instant createdAt;
}
