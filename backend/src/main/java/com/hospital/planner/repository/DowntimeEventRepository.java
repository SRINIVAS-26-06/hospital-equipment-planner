package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DowntimeEventRepository extends JpaRepository<DowntimeEvent, Long> {
    @Query("select d from DowntimeEvent d where d.equipment.id = :asset and d.reason = :reason and d.endAt is null")
    List<DowntimeEvent> findOpen(@Param("asset") Long asset, @Param("reason") String reason);

    @Query("select d from DowntimeEvent d where d.equipment.id = :asset order by d.startAt desc")
    List<DowntimeEvent> findForAsset(@Param("asset") Long asset);
}
