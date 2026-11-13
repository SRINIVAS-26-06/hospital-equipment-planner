package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "transfers")
public class Transfer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
    @ManyToOne(optional = false) @JoinColumn(name = "from_dept_id") public Department fromDepartment;
    @ManyToOne(optional = false) @JoinColumn(name = "to_dept_id") public Department toDepartment;
    @Column(name = "transferred_at", nullable = false) public Instant transferredAt;
}
