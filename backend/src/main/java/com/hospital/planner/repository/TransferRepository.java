package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
    @Query("select t from Transfer t join fetch t.fromDepartment join fetch t.toDepartment where t.equipment.id = :asset")
    List<Transfer> findForAsset(@Param("asset") Long asset);
}
