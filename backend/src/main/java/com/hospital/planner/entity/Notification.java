package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) public Instant createdAt;
    @Column(nullable = false) public String type;
    @Column(nullable = false) public String severity = "INFO";
    @Column(nullable = false) public String title;
    @Column(nullable = false) public String message;
    @Column(name = "entity_type") public String entityType;
    @Column(name = "entity_id") public Long entityId;
    @Column(name = "audience_role") public String audienceRole;
    @Column(name = "audience_user") public String audienceUser;
    @Column(name = "dedupe_key") public String dedupeKey;
    /** Users who have read it (table notification_reads; its read_at column has a database default). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "notification_reads", joinColumns = @JoinColumn(name = "notification_id"))
    @Column(name = "username")
    public Set<String> readBy = new HashSet<>();
}
