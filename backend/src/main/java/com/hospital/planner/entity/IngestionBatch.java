package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ingestion_batches")
public class IngestionBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public String source;
    public String filename;
    @Column(name = "file_sha256") public String fileSha256;
    @Column(name = "uploaded_by", nullable = false) public String uploadedBy;
    @Column(name = "uploaded_at", nullable = false) public Instant uploadedAt;
    @Column(name = "total_rows", nullable = false) public int totalRows;
    @Column(name = "accepted_rows", nullable = false) public int acceptedRows;
    @Column(name = "rejected_rows", nullable = false) public int rejectedRows;
    @Column(nullable = false) public String status = "PROCESSING";
    @Column(name = "ignored_columns") public String ignoredColumns;
}
