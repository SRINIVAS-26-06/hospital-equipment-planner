package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionRowResultRepository extends JpaRepository<IngestionRowResult, Long> {
    @Query("select count(r) from IngestionRowResult r where r.batchId = :b and r.outcome = :o")
    long countByBatchAndOutcome(@Param("b") Long batch, @Param("o") String outcome);

    @Query("select r from IngestionRowResult r where r.batchId = :b and r.outcome = 'REJECTED' order by r.rowNumber")
    List<IngestionRowResult> rejected(@Param("b") Long batch);
}
