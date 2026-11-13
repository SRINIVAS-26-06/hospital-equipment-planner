package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Table "documents". Content is excluded from list queries; it is only read for a download. */
@Entity
@Table(name = "documents")
public class EquipmentDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "entity_type", nullable = false) public String entityType;
    @Column(name = "entity_id", nullable = false) public Long entityId;
    @Column(name = "doc_kind", nullable = false) public String docKind = "ATTACHMENT";
    @Column(nullable = false) public String filename;
    @Column(name = "content_type", nullable = false) public String contentType;
    @Column(name = "size_bytes", nullable = false) public Long sizeBytes;
    @Basic(fetch = FetchType.LAZY) @Column(nullable = false) public byte[] content;
    @Column(name = "uploaded_by", nullable = false) public String uploadedBy;
    @Column(name = "uploaded_at", nullable = false) public Instant uploadedAt;
}
