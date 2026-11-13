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

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {
    /** Row lock that serialises every state change on one piece of equipment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id = :id")
    Optional<Equipment> lockById(@Param("id") Long id);

    Optional<Equipment> findByAssetTag(String assetTag);

    /** Availability is calculated in one SQL expression (verified on PostgreSQL), shared by every caller. */
    @Query(value = "SELECT CAST(" + com.hospital.planner.service.Availability.STATE_SQL + " AS text) FROM assets a WHERE a.id = :id", nativeQuery = true)
    String stateAt(@Param("id") Long id, @Param("now") Instant now);

    @Query(value = "SELECT a.id, CAST(" + com.hospital.planner.service.Availability.STATE_SQL + " AS text) FROM assets a WHERE a.id IN (:ids)", nativeQuery = true)
    List<Object[]> statesFor(@Param("ids") java.util.Collection<Long> ids, @Param("now") Instant now);

    @Query(value = "SELECT s, count(*) FROM (SELECT CAST(" + com.hospital.planner.service.Availability.STATE_SQL + " AS text) AS s FROM assets a) t GROUP BY s", nativeQuery = true)
    List<Object[]> countByState(@Param("now") Instant now);

    long countByDepartmentIdAndLifecycleStatusNot(Long departmentId, LifecycleStatus status);
}
