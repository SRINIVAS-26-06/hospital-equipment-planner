package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ingestion_row_results")
public class IngestionRowResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "batch_id", nullable = false) public Long batchId;
    @Column(name = "row_number", nullable = false) public int rowNumber;
    @Column(name = "asset_ref") public String assetRef;
    @Column(name = "start_text") public String startText;
    @Column(name = "end_text") public String endText;
    @Column(nullable = false) public String outcome;
    public String reason;
    @Column(name = "usage_event_id") public Long usageEventId;
}
