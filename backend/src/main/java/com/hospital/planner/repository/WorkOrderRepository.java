package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    @Query("select count(w) from WorkOrder w where w.equipment.id = :asset and (w.status in ('REPORTED','TRIAGED','ASSIGNED','IN_PROGRESS') or (w.unsafe = true and w.status = 'RESOLVED'))")
    long countBlocking(@Param("asset") Long asset);

    @Query("select w.reportedBy from WorkOrder w where w.id = :id")
    Optional<String> findReporter(@Param("id") Long id);

    @Query("select w.assignedEngineer from WorkOrder w where w.id = :id")
    Optional<String> findEngineer(@Param("id") Long id);

    @Query("select w from WorkOrder w where w.equipment.id = :asset and (w.status in ('REPORTED','TRIAGED','ASSIGNED','IN_PROGRESS') "
            + "or (w.unsafe = true and w.status = 'RESOLVED')) order by w.unsafe desc, w.id")
    List<WorkOrder> findBlocking(@Param("asset") Long asset);
}
