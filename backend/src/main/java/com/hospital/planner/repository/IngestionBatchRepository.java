package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, Long> {
    long countByFileSha256AndStatusIn(String sha, List<String> statuses);
}
