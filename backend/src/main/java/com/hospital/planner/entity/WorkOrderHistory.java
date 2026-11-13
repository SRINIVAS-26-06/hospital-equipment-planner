package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "work_order_history")
public class WorkOrderHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "work_order_id") public WorkOrder workOrder;
    @Column(name = "at", nullable = false) public Instant occurredAt;
    @Column(nullable = false) public String actor;
    @Column(name = "from_status") public String fromStatus;
    @Column(name = "to_status", nullable = false) public String toStatus;
    public String note;
}
