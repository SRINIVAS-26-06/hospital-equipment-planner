package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
    @ManyToOne(optional = false) @JoinColumn(name = "department_id") public Department department;
    @Column(name = "requested_by", nullable = false) public String requestedBy;
    @Column(name = "start_at", nullable = false) public Instant startAt;
    @Column(name = "end_at", nullable = false) public Instant endAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public BookingStatus status = BookingStatus.ACTIVE;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) public Instant createdAt;
    public String purpose;
    @Column(name = "cancelled_by") public String cancelledBy;
    @Column(name = "cancelled_at") public Instant cancelledAt;
    @Column(name = "cancel_reason") public String cancelReason;
}
