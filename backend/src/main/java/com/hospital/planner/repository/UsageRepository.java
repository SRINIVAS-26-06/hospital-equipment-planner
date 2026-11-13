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

public interface UsageRepository extends JpaRepository<Usage, Long> {
    @Query("select u from Usage u where u.equipment.id = :asset and u.endAt is null")
    Optional<Usage> findOpenSession(@Param("asset") Long asset);

    @Query("select count(u) from Usage u where u.equipment.id = :asset and u.startAt < :end and (u.endAt is null or u.endAt > :start)")
    long countOverlapping(@Param("asset") Long asset, @Param("start") Instant start, @Param("end") Instant end);

    boolean existsByEquipmentIdAndStartAtAndEndAt(Long asset, Instant start, Instant end);

    @Query("select u from Usage u where u.startAt < :to and (u.endAt is null or u.endAt > :from)")
    List<Usage> findOverlappingWindow(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = "SELECT to_char(date_trunc('day', u.start_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS period, d.name, count(*), "
            + "round(CAST(sum(extract(epoch FROM (COALESCE(u.end_at, :now) - u.start_at)) / 3600.0) AS numeric), 2) "
            + "FROM usage_events u LEFT JOIN departments d ON d.id = u.department_id WHERE u.start_at >= :from AND u.start_at < :to "
            + "AND (CAST(:dept AS bigint) IS NULL OR u.department_id = :dept) GROUP BY 1, 2 ORDER BY 1, 2", nativeQuery = true)
    List<Object[]> hoursByDay(@Param("from") Instant from, @Param("to") Instant to, @Param("now") Instant now, @Param("dept") Long dept);

    @Query(value = "SELECT to_char(date_trunc('month', u.start_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS period, d.name, count(*), "
            + "round(CAST(sum(extract(epoch FROM (COALESCE(u.end_at, :now) - u.start_at)) / 3600.0) AS numeric), 2) "
            + "FROM usage_events u LEFT JOIN departments d ON d.id = u.department_id WHERE u.start_at >= :from AND u.start_at < :to "
            + "AND (CAST(:dept AS bigint) IS NULL OR u.department_id = :dept) GROUP BY 1, 2 ORDER BY 1, 2", nativeQuery = true)
    List<Object[]> hoursByMonth(@Param("from") Instant from, @Param("to") Instant to, @Param("now") Instant now, @Param("dept") Long dept);
}
