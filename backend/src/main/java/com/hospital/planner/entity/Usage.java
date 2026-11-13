package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Table "usage_events": one row per usage session (end_at is null while the session is open). */
@Entity
@Table(name = "usage_events")
public class Usage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
    @Column(name = "start_at", nullable = false) public Instant startAt;
    @Column(name = "end_at") public Instant endAt;
    @Column(nullable = false) public String source = "api";
    @Column(name = "used_by") public String usedBy;
    @ManyToOne @JoinColumn(name = "department_id") public Department department;
    @ManyToOne @JoinColumn(name = "user_department_id") public Department userDepartment;
    @Column(name = "batch_id") public Long batchId;
    @Column(name = "booking_id") public Long bookingId;
    @Column(name = "ingested_at", nullable = false, insertable = false, updatable = false) public Instant ingestedAt;
}
