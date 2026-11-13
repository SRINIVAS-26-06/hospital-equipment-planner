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

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Query("select b from Booking b where b.equipment.id = :asset and b.status = :status and b.startAt < :end and b.endAt > :start")
    List<Booking> findOverlapping(@Param("asset") Long asset, @Param("status") BookingStatus status, @Param("start") Instant start, @Param("end") Instant end);

    @Modifying
    @Query("update Booking b set b.status = :to, b.cancelledBy = :by, b.cancelledAt = :at, b.cancelReason = :reason "
            + "where b.equipment.id = :asset and b.status = com.hospital.planner.entity.BookingStatus.ACTIVE and b.endAt > :at")
    int cancelActive(@Param("asset") Long asset, @Param("to") BookingStatus to, @Param("by") String by, @Param("at") Instant at, @Param("reason") String reason);
}
