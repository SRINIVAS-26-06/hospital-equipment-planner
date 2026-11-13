package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Unplanned or administrative downtime. end_at is null while it is still running (e.g. out of service). */
@Entity
@Table(name = "downtime_events")
public class DowntimeEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
    @Column(name = "start_at", nullable = false) public Instant startAt;
    @Column(name = "end_at") public Instant endAt;
    @Column(nullable = false) public String reason;
}
