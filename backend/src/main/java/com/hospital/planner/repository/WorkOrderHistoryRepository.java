package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderHistoryRepository extends JpaRepository<WorkOrderHistory, Long> {
    @Query("select h from WorkOrderHistory h where h.workOrder.id = :id order by h.occurredAt, h.id")
    List<WorkOrderHistory> findForOrder(@Param("id") Long id);
}
