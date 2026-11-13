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

public interface EquipmentDocumentRepository extends JpaRepository<EquipmentDocument, Long> {
    /** Metadata only: the content column is never loaded for a listing. */
    @Query("select new com.hospital.planner.dto.DocumentDto(d.id, d.entityType, d.entityId, d.docKind, d.filename, d.contentType, d.sizeBytes, d.uploadedBy, d.uploadedAt) "
            + "from EquipmentDocument d where d.entityType = :type and d.entityId = :entityId order by d.uploadedAt desc")
    List<com.hospital.planner.dto.DocumentDto> listFor(@Param("type") String type, @Param("entityId") Long entityId);
}
