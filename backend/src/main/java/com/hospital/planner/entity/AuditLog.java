package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "at", nullable = false) public Instant occurredAt;
    @Column(nullable = false) public String username;
    @Column(nullable = false) public String action;
    @Column(name = "entity_type", nullable = false) public String entityType;
    @Column(name = "entity_id") public String entityId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "old_value", columnDefinition = "jsonb") public String oldValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "new_value", columnDefinition = "jsonb") public String newValue;
}
