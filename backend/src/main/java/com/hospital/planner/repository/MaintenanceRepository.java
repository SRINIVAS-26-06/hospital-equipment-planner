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

public interface MaintenanceRepository extends JpaRepository<Maintenance, Long> {
    @Query("select m from Maintenance m where m.equipment.id = :asset and m.status in ('SCHEDULED','IN_PROGRESS') and m.startAt < :end and m.endAt > :start")
    List<Maintenance> findOpenOverlapping(@Param("asset") Long asset, @Param("start") Instant start, @Param("end") Instant end);

    List<Maintenance> findByAssignedEngineerAndStatusIn(String engineer, List<String> statuses);
}
